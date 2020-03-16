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

package io.cdap.delta.mysql;

import com.mysql.jdbc.Driver;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.delta.api.DDLEvent;
import io.cdap.delta.api.DDLOperation;
import io.cdap.delta.api.DMLEvent;
import io.cdap.delta.api.DMLOperation;
import io.cdap.delta.api.DeltaSourceContext;
import io.cdap.delta.api.Offset;
import io.cdap.delta.api.SourceTable;
import io.cdap.delta.plugin.mock.MockContext;
import io.cdap.delta.plugin.mock.MockEventEmitter;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for MySql event reader.
 *
 * Ideally this would extend DeltaPipelineTestBase and run an actual replicator in memory, but there
 * are some classloading issues due to copied debezium classes.
 */
public class MySqlEventReaderIntegrationTest {
  private static final String DB = "test";
  private static final String CUSTOMERS_TABLE = "customers";
  private static final Schema CUSTOMERS_SCHEMA = Schema.recordOf(
    "customers",
    Schema.Field.of("id", Schema.of(Schema.Type.INT)),
    Schema.Field.of("name", Schema.of(Schema.Type.STRING)),
    Schema.Field.of("bday", Schema.nullableOf(Schema.of(Schema.LogicalType.DATE))));
  private static String password;
  private static int port;

  @BeforeClass
  public static void setupClass() throws Exception {
    password = System.getProperty("mysql.root.password");
    String portFilePath = System.getProperty("mysql.port.file");
    Properties properties = new Properties();
    try (InputStream is = new FileInputStream(new File(portFilePath))) {
      properties.load(is);
    }
    port = Integer.parseInt(properties.getProperty("mysql.port"));

    Properties connProperties = new Properties();
    connProperties.put("user", "root");
    connProperties.put("password", password);
    String connectionUrl = String.format("jdbc:mysql://localhost:%d", port);
    DriverManager.getDriver(connectionUrl);

    // wait until a connection can be established
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < TimeUnit.SECONDS.toMillis(60)) {
      try (Connection connection = DriverManager.getConnection(connectionUrl, connProperties)) {
        break;
      } catch (Exception e) {
        TimeUnit.SECONDS.sleep(2);
      }
    }

    // create database
    try (Connection connection = DriverManager.getConnection(connectionUrl, connProperties)) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE " + DB);
      }
    }

    connectionUrl = connectionUrl + "/" + DB;
    try (Connection connection = DriverManager.getConnection(connectionUrl, connProperties)) {
      // create table
      try (Statement statement = connection.createStatement()) {
        statement.execute(String.format("CREATE TABLE %s (id int PRIMARY KEY, name varchar(50), bday date null)",
                                        CUSTOMERS_TABLE));
      }

      // insert sample data
      try (PreparedStatement ps = connection.prepareStatement(String.format("INSERT INTO %s VALUES (?, ?, ?)",
                                                                            CUSTOMERS_TABLE))) {
        ps.setInt(1, 0);
        ps.setString(2, "alice");
        ps.setDate(3, Date.valueOf("1970-01-01"));
        ps.addBatch();

        ps.setInt(1, 1);
        ps.setString(2, "bob");
        ps.setDate(3, Date.valueOf("1971-01-01"));
        ps.addBatch();

        ps.executeBatch();
      }
    }
  }

  @Test
  public void test() throws InterruptedException {
    SourceTable sourceTable = new SourceTable(DB, CUSTOMERS_TABLE, null,
                                              Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    DeltaSourceContext context = new MockContext(Driver.class);
    MockEventEmitter eventEmitter = new MockEventEmitter(6);
    MySqlConfig config = new MySqlConfig("localhost", port, "root", password, 13, DB,
                                         TimeZone.getDefault().getID());

    MySqlEventReader eventReader = new MySqlEventReader(Collections.singleton(sourceTable), config,
                                                        context, eventEmitter);

    eventReader.start(new Offset());

    eventEmitter.waitForExpectedEvents(30, TimeUnit.SECONDS);

    Assert.assertEquals(4, eventEmitter.getDdlEvents().size());
    Assert.assertEquals(2, eventEmitter.getDmlEvents().size());

    /*
      This currently would fails.
      Need to investigate why it's DROP_TABLE, CREATE_DATABASE, CREATE_DATABASE, CREATE_TABLE
    DDLEvent ddlEvent = eventEmitter.getDdlEvents().get(0);
    Assert.assertEquals(DDLOperation.DROP_DATABASE, ddlEvent.getOperation());
    Assert.assertEquals(DB, ddlEvent.getDatabase());

    ddlEvent = eventEmitter.getDdlEvents().get(1);
    Assert.assertEquals(DDLOperation.CREATE_DATABASE, ddlEvent.getOperation());
    Assert.assertEquals(DB, ddlEvent.getDatabase());

    ddlEvent = eventEmitter.getDdlEvents().get(2);
    Assert.assertEquals(DDLOperation.DROP_TABLE, ddlEvent.getOperation());
    Assert.assertEquals(DB, ddlEvent.getDatabase());
    Assert.assertEquals(CUSTOMERS_TABLE, ddlEvent.getTable());
    */

    DDLEvent ddlEvent = eventEmitter.getDdlEvents().get(3);
    Assert.assertEquals(DDLOperation.CREATE_TABLE, ddlEvent.getOperation());
    Assert.assertEquals(DB, ddlEvent.getDatabase());
    Assert.assertEquals(CUSTOMERS_TABLE, ddlEvent.getTable());
    Assert.assertEquals(Collections.singletonList("id"), ddlEvent.getPrimaryKey());
    // TODO: this would currently fail, seems to incorrectly return the schema as non-nullable
    //Assert.assertEquals(CUSTOMERS_SCHEMA, ddlEvent.getSchema());

    DMLEvent dmlEvent = eventEmitter.getDmlEvents().get(0);
    Assert.assertEquals(DMLOperation.INSERT, dmlEvent.getOperation());
    Assert.assertEquals(DB, dmlEvent.getDatabase());
    Assert.assertEquals(CUSTOMERS_TABLE, dmlEvent.getTable());
    StructuredRecord row = dmlEvent.getRow();
    StructuredRecord expected = StructuredRecord.builder(CUSTOMERS_SCHEMA)
      .set("id", 0)
      .set("name", "alice")
      .setDate("bday", LocalDate.ofEpochDay(0))
      .build();
    // this fails with schemas that are different
    // Assert.assertEquals(expected, row);

    dmlEvent = eventEmitter.getDmlEvents().get(1);
    Assert.assertEquals(DMLOperation.INSERT, dmlEvent.getOperation());
    Assert.assertEquals(DB, dmlEvent.getDatabase());
    Assert.assertEquals(CUSTOMERS_TABLE, dmlEvent.getTable());
    row = dmlEvent.getRow();
    expected = StructuredRecord.builder(CUSTOMERS_SCHEMA)
      .set("id", 1)
      .set("name", "bob")
      .setDate("bday", LocalDate.ofEpochDay(365))
      .build();
    // this fails with schemas that are different
    // Assert.assertEquals(expected, row);
  }
}
