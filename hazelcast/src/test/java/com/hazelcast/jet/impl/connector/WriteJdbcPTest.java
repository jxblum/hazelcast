/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.connector;

import com.hazelcast.config.Config;
import com.hazelcast.function.BiConsumerEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.jet.test.IgnoreInJenkinsOnWindows;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.SlowTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.xa.PGXADataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.CommonDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.impl.connector.ExternalDataStoreTestUtil.configureDummyDataStore;
import static com.hazelcast.jet.impl.connector.ExternalDataStoreTestUtil.configureJdbcDataStore;
import static com.hazelcast.jet.pipeline.ExternalDataStoreRef.externalDataStoreRef;
import static com.hazelcast.test.DockerTestUtil.assumeDockerEnabled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Category({SlowTest.class, ParallelJVMTest.class, IgnoreInJenkinsOnWindows.class})
public class WriteJdbcPTest extends SimpleTestInClusterSupport {

    private static final String JDBC_DATA_STORE = "jdbc-data-store";
    private static final String DUMMY_DATA_STORE = "dummy-data-store";

    @ClassRule
    @SuppressWarnings("rawtypes")
    public static PostgreSQLContainer container = new PostgreSQLContainer<>("postgres:12.1")
            .withCommand("postgres -c max_prepared_transactions=10");

    private static final int PERSON_COUNT = 10;

    private static AtomicInteger tableCounter = new AtomicInteger();
    private static HikariDataSource hikariDataSource;
    private String tableName;

    @BeforeClass
    public static void beforeClassCheckDocker() {
        assumeDockerEnabled();
    }

    @BeforeClass
    public static void setupClass() throws SQLException {
        Config config = smallInstanceConfig();
        configureJdbcDataStore(JDBC_DATA_STORE, container.getJdbcUrl(), container.getUsername(), container.getPassword(), config);
        configureDummyDataStore(DUMMY_DATA_STORE, config);
        initialize(2, config);
    }

    @Before
    public void setup() throws SQLException {
        tableName = "T" + tableCounter.incrementAndGet();
        logger.info("Will use table: " + tableName);
        try (Connection connection = ((DataSource) createDataSource(false)).getConnection()) {
            connection.createStatement()
                    .execute("CREATE TABLE " + tableName + "(id int, name varchar(255))");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    @Test
    public void test() throws SQLException {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
                .map(item -> entry(item, item.toString()))
                .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                        () -> createDataSource(false),
                        (stmt, item) -> {
                            stmt.setInt(1, item.getKey());
                            stmt.setString(2, item.getValue());
                        }
                ));

        instance().getJet().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    @Test
    public void test_supplied_closeable_datasource_is_NOT_closed() throws SQLException {
        Pipeline p = Pipeline.create();

        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
                .map(item -> entry(item, item.toString()))
                .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                        WriteJdbcPTest::createHikariDataSource,
                        (stmt, item) -> {
                            stmt.setInt(1, item.getKey());
                            stmt.setString(2, item.getValue());
                        }
                ));
        instance().getJet().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
        assertFalse(hikariDataSource.isClosed());
    }

    private static DataSource createHikariDataSource() {
        hikariDataSource = new HikariDataSource();
        assertThat(hikariDataSource).isInstanceOf(AutoCloseable.class);

        hikariDataSource.setJdbcUrl(container.getJdbcUrl());
        hikariDataSource.setUsername(container.getUsername());
        hikariDataSource.setPassword(container.getPassword());
        return hikariDataSource;
    }

    @Test
    public void test_external_datastore_config() throws SQLException {
        assertEquals(0, rowCount());
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
                .map(item -> entry(item, item.toString()))
                .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                        externalDataStoreRef(JDBC_DATA_STORE),
                        (stmt, item) -> {
                            stmt.setInt(1, item.getKey());
                            stmt.setString(2, item.getValue());
                        }
                ));

        instance().getJet().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    public void testReconnect() throws SQLException {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
                .map(item -> entry(item, item.toString()))
                .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                        failTwiceDataSourceSupplier(), failOnceBindFn()
                ));

        instance().getJet().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_withNonTransientException() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     throw new SQLNonTransientException();
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_withNonTransientExceptionCause() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     throw new SQLException(new SQLNonTransientException());
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_withNonTransientExceptionNext() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     SQLException ex = new SQLException();
                     ex.setNextException(new SQLNonTransientException());
                     throw ex;
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_withNonTransientExceptionNextChain() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     SQLException ex = new SQLException();
                     SQLException next = new SQLException();
                     ex.setNextException(next);
                     next.setNextException(new SQLNonTransientException());
                     throw ex;
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test
    public void testFailJob_withNonTransientExceptionNextChainCycle() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     SQLException ex = new SQLException();
                     SQLException next = new SQLException();
                     ex.setNextException(next);
                     next.setNextException(next); // Cycle for the last exception
                     throw ex;
                 }
         ));

        Job job = instance().getJet().newJob(p);
        assertJobStatusEventually(job, JobStatus.RUNNING, 5);
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_whenGetConnection_withNonTransientException() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> {
                     DataSource spyDataSource = (DataSource) spy(createDataSource(false));
                     when(spyDataSource.getConnection()).thenThrow(new SQLNonTransientException());
                     return spyDataSource;
                 },
                 (stmt, item) -> {
                     // execution doesn't get here
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_whenGetConnection_withNonTransientExceptionCause() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> {
                     DataSource spyDataSource = (DataSource) spy(createDataSource(false));
                     when(spyDataSource.getConnection()).thenThrow(new SQLException(new SQLNonTransientException()));
                     return spyDataSource;
                 },
                 (stmt, item) -> {
                     // execution doesn't get here
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test(expected = CompletionException.class, timeout = 5_000)
    public void testFailJob_whenGetConnection_withNonTransientExceptionNext() {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> {
                     DataSource spyDataSource = (DataSource) spy(createDataSource(false));
                     SQLException ex = new SQLException();
                     ex.setNextException(new SQLNonTransientException());
                     when(spyDataSource.getConnection()).thenThrow(ex);
                     return spyDataSource;
                 },
                 (stmt, item) -> {
                     // execution doesn't get here
                 }
         ));

        instance().getJet().newJob(p).join();
    }

    @Test
    public void test_transactional_withRestarts_graceful_exOnce() throws Exception {
        test_transactional_withRestarts(true, true);
    }

    @Test
    public void test_transactional_withRestarts_forceful_exOnce() throws Exception {
        test_transactional_withRestarts(false, true);
    }

    @Test
    public void test_transactional_withRestarts_graceful_atLeastOnce() throws Exception {
        test_transactional_withRestarts(true, false);
    }

    @Test
    public void test_transactional_withRestarts_forceful_atLeastOnce() throws Exception {
        test_transactional_withRestarts(false, false);
    }

    private void test_transactional_withRestarts(boolean graceful, boolean exactlyOnce) throws Exception {
        Sink<Integer> sink = Sinks.<Integer>jdbcBuilder()
                                  .updateQuery("INSERT INTO " + tableName + " VALUES(?, ?)")
                                  .dataSourceSupplier(() -> createDataSource(true))
                                  .bindFn(
                                          (stmt, item) -> {
                                              stmt.setInt(1, item);
                                              stmt.setString(2, "name-" + item);
                                          })
                                  .exactlyOnce(exactlyOnce)
                                  .build();

        try (Connection conn = ((DataSource) createDataSource(false)).getConnection();
             PreparedStatement stmt = conn.prepareStatement("select id from " + tableName)
        ) {
            SinkStressTestUtil.test_withRestarts(instance(), logger, sink, graceful, exactlyOnce, () -> {
                ResultSet resultSet = stmt.executeQuery();
                List<Integer> actualRows = new ArrayList<>();
                while (resultSet.next()) {
                    actualRows.add(resultSet.getInt(1));
                }
                return actualRows;
            });
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = ((DataSource) createDataSource(false)).getConnection();
             Statement statement = connection.createStatement()) {
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName);
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getInt(1);
        }
    }

    private static CommonDataSource createDataSource(boolean xa) {
        BaseDataSource dataSource = xa ? new PGXADataSource() : new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setDatabaseName(container.getDatabaseName());

        return dataSource;
    }

    private static SupplierEx<DataSource> failTwiceDataSourceSupplier() {
        return new SupplierEx<DataSource>() {
            int remainingFailures = 2;

            @Override
            public DataSource getEx() throws SQLException {
                DataSource realDs = (DataSource) createDataSource(false);
                DataSource mockDs = mock(DataSource.class);
                doAnswer(invocation -> {
                    if (remainingFailures-- > 0) {
                        throw new SQLException("connection failure");
                    }
                    return realDs.getConnection();
                }).when(mockDs).getConnection();
                return mockDs;
            }
        };
    }

    private static BiConsumerEx<PreparedStatement, Entry<Integer, String>> failOnceBindFn() {
        return new BiConsumerEx<PreparedStatement, Entry<Integer, String>>() {
            int remainingFailures = 1;

            @Override
            public void acceptEx(PreparedStatement stmt, Entry<Integer, String> item) throws SQLException {
                if (remainingFailures-- > 0) {
                    throw new SQLException("bindFn failure");
                }
                stmt.setInt(1, item.getKey());
                stmt.setString(2, item.getValue());
            }
        };
    }
}
