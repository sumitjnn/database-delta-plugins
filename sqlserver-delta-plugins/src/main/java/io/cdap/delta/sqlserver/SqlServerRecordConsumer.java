/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.delta.sqlserver;

import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.delta.api.DDLEvent;
import io.cdap.delta.api.DDLOperation;
import io.cdap.delta.api.DMLEvent;
import io.cdap.delta.api.DMLOperation;
import io.cdap.delta.api.DeltaFailureRuntimeException;
import io.cdap.delta.api.DeltaSourceContext;
import io.cdap.delta.api.EventEmitter;
import io.cdap.delta.api.Offset;
import io.cdap.delta.api.SourceTable;
import io.cdap.delta.plugin.common.Records;
import io.debezium.embedded.StopConnectorException;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Sql server record consumer
 */
public class SqlServerRecordConsumer implements Consumer<SourceRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(SqlServerRecordConsumer.class);

  private final DeltaSourceContext context;
  private final EventEmitter emitter;
  // we need this since there is no way to get the db information from the source record
  private final String databaseName;
  // record tables that already had DDL events sent
  private final Set<String> ddlEventSent;
  private final Map<String, SourceTable> sourceTableMap;
  private final boolean replicateExistingData;
  private final Offset latestOffset;


  SqlServerRecordConsumer(DeltaSourceContext context, EventEmitter emitter, String databaseName,
                          Set<String> ddlEventSent, Map<String, SourceTable> sourceTableMap,
                          Offset latestOffset, boolean replicateExistingData) {
    this.context = context;
    this.emitter = emitter;
    this.databaseName = databaseName;
    this.ddlEventSent = ddlEventSent;
    this.sourceTableMap = sourceTableMap;
    this.latestOffset = latestOffset;
    this.replicateExistingData = replicateExistingData;
  }

  @Override
  public void accept(SourceRecord sourceRecord) {
    try {
      context.setOK();
    } catch (IOException e) {
      LOG.warn("Unable to set source state to OK.", e);
    }
    if (sourceRecord.value() == null) {
      return;
    }

    // ignore duplicated CDC event
    // SQLServer connector will relay the last event at the offset
    // to be safe here we check whether it's before or at the same offset
    // snapshotting will resume from beginning, and the whole table that is partly snapshotted
    // is supposed to be dropped first , thus no need to consider
    SqlServerOffset sqlServerOffset = new SqlServerOffset(sourceRecord.sourceOffset(), ddlEventSent);
    if (!sqlServerOffset.isSnapshot() && sqlServerOffset.isBeforeOrAt(latestOffset)) {
      LOG.debug("Got duplicated event {} ", sourceRecord);
      return;
    }

    StructuredRecord val = Records.convert((Struct) sourceRecord.value());
    DMLOperation.Type op;
    String opStr = val.get("op");
    if ("c".equals(opStr) || "r".equals(opStr)) {
      op = DMLOperation.Type.INSERT;
    } else if ("u".equals(opStr)) {
      op = DMLOperation.Type.UPDATE;
    } else if ("d".equals(opStr)) {
      op = DMLOperation.Type.DELETE;
    } else {
      LOG.warn("Skipping unknown operation type '{}'", opStr);
      return;
    }

    String topicName = sourceRecord.topic();
    // the topic name will always be like this: [db.server.name].[schema].[table]
    if (topicName == null) {
      return; // safety check to avoid NPE
    }
    String[] splits = topicName.split("\\.");
    String schemaName = splits[1];
    String tableName = splits[2];
    String sourceTableId = schemaName + "." + tableName;
    // If the map is empty, we should read all DDL/DML events and columns of all tables
    boolean readAllTables = sourceTableMap.isEmpty();
    SourceTable sourceTable = sourceTableMap.get(sourceTableId);
    if (!readAllTables && sourceTable == null) {
      // shouldn't happen
      return;
    }
    if (sourceRecord.key() == null) {
      throw new DeltaFailureRuntimeException(String.format("Table '%s' in database '%s' has no primary key. " +
                                                             "Tables without a primary key are" +
                                                             " not supported.", tableName, databaseName));
    }

    StructuredRecord before = val.get("before");
    StructuredRecord after = val.get("after");
    if (!readAllTables) {
      if (before != null) {
        before = Records.keepSelectedColumns(before, sourceTable.getColumns());
      }
      if (after != null) {
        after = Records.keepSelectedColumns(after, sourceTable.getColumns());
      }
    }
    StructuredRecord value = op == DMLOperation.Type.DELETE ? before : after;

    if (value == null) {
      // this is a safety check to prevent npe warning, it should not be null
      LOG.warn("There is no value in the source record from table {} in database {}", tableName, databaseName);
      return;
    }

    Schema schema = value.getSchema();
    // send the ddl events only if we see the table at the first time
    // Note: the delta app itself have prevented adding CREATE_TABLE operation into DDL blacklist for all the tables.
    if (!ddlEventSent.contains(sourceTableId)) {
      SqlServerOffset ddlRecordOffset = new SqlServerOffset(sourceRecord.sourceOffset(), ddlEventSent);

      DDLEvent.Builder builder = DDLEvent.builder()
        .setDatabaseName(databaseName)
        .setSnapshot(ddlRecordOffset.isSnapshot())
        .setOffset(ddlRecordOffset.getAsOffset());

      StructuredRecord key = Records.convert((Struct) sourceRecord.key());
      List<Schema.Field> fields = key.getSchema().getFields();
      List<String> primaryFields = new ArrayList<>();
      if (fields != null && !fields.isEmpty()) {
        primaryFields = fields.stream().map(Schema.Field::getName).collect(Collectors.toList());
      }

      try {
        if (replicateExistingData) {
          // try to always drop the table before snapshot the schema.
          emitter.emit(builder.setOperation(DDLOperation.Type.DROP_TABLE)
                         .setTableName(tableName)
                         .setSchemaName(schemaName)
                         .build());
        }

        // try to emit create database event before create table event
        emitter.emit(builder.setOperation(DDLOperation.Type.CREATE_DATABASE)
                       .setSchemaName(schemaName)
                       .build());

        emitter.emit(builder.setOperation(DDLOperation.Type.CREATE_TABLE)
                       .setTableName(tableName)
                       .setSchemaName(schemaName)
                       .setSchema(schema)
                       .setPrimaryKey(primaryFields)
                       .build());
      } catch (InterruptedException e) {
        // happens when the event reader is stopped. throwing this exception tells Debezium to stop right away
        throw new StopConnectorException("Interrupted while emitting an event.");
      }
    }

    if (!readAllTables && sourceTable.getDmlBlacklist().contains(op)) {
      // do nothing due to it was not set to read all tables and the DML op has been blacklisted for this table
      return;
    }

    ddlEventSent.add(sourceTableId);
    SqlServerOffset dmlRecordOffset = new SqlServerOffset(sourceRecord.sourceOffset(), ddlEventSent);
    Long ingestTime = val.get("ts_ms");
    DMLEvent.Builder dmlBuilder = DMLEvent.builder()
      .setOffset(dmlRecordOffset.getAsOffset())
      .setOperationType(op)
      .setDatabaseName(databaseName)
      .setSchemaName(schemaName)
      .setTableName(tableName)
      .setRow(value)
      .setSnapshot(dmlRecordOffset.isSnapshot())
      .setTransactionId(null)
      .setIngestTimestamp(ingestTime == null ? 0L : ingestTime);

    // It is required for the source to provide the previous row if the operation is 'UPDATE'
    if (op == DMLOperation.Type.UPDATE) {
      dmlBuilder.setPreviousRow(before);
    }

    try {
      emitter.emit(dmlBuilder.build());
    } catch (InterruptedException e) {
      // happens when the event reader is stopped. throwing this exception tells Debezium to stop right away
      throw new StopConnectorException("Interrupted while emitting an event.");
    }
  }
}
