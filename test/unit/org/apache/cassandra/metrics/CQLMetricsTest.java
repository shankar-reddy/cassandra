/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.metrics;

import java.io.IOException;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;

import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.service.EmbeddedCassandraService;

import static junit.framework.Assert.assertEquals;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(OrderedJUnit4ClassRunner.class)
public class CQLMetricsTest extends SchemaLoader
{
    private static EmbeddedCassandraService cassandra;

    private static Cluster cluster;
    private static Session session;
    private static PreparedStatement metricsStatement;

    @BeforeClass()
    public static void setup() throws ConfigurationException, IOException
    {
        Schema.instance.clear();

        cassandra = new EmbeddedCassandraService();
        cassandra.start();

        cluster = Cluster.builder().addContactPoint("127.0.0.1").withPort(DatabaseDescriptor.getNativeTransportPort()).build();
        session = cluster.connect();

        session.execute("CREATE KEYSPACE IF NOT EXISTS junit WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };");
        session.execute("CREATE TABLE IF NOT EXISTS junit.metricstest (id int PRIMARY KEY, val text);");
    }

    @Test
    public void testPreparedStatementsCount()
    {
        assertEquals(0, (int) QueryProcessor.metrics.preparedStatementsCount.value());
        metricsStatement = session.prepare("INSERT INTO junit.metricstest (id, val) VALUES (?, ?)");
        assertEquals(1, (int) QueryProcessor.metrics.preparedStatementsCount.value());
    }

    @Test
    public void testRegularStatementsExecuted()
    {
        clearMetrics();

        assertEquals(0, QueryProcessor.metrics.preparedStatementsExecuted.count());
        assertEquals(0, QueryProcessor.metrics.regularStatementsExecuted.count());

        for (int i = 0; i < 10; i++)
            session.execute(String.format("INSERT INTO junit.metricstest (id, val) VALUES (%d, '%s')", i, "val" + i));

        assertEquals(0, QueryProcessor.metrics.preparedStatementsExecuted.count());
        assertEquals(10, QueryProcessor.metrics.regularStatementsExecuted.count());
    }

    @Test
    public void testPreparedStatementsExecuted()
    {
        clearMetrics();

        assertEquals(0, QueryProcessor.metrics.preparedStatementsExecuted.count());
        assertEquals(0, QueryProcessor.metrics.regularStatementsExecuted.count());

        for (int i = 0; i < 10; i++)
            session.execute(metricsStatement.bind(i, "val" + i));

        assertEquals(10, QueryProcessor.metrics.preparedStatementsExecuted.count());
        assertEquals(0, QueryProcessor.metrics.regularStatementsExecuted.count());
    }

    @Test
    public void testPreparedStatementsRatio()
    {
        clearMetrics();

        assertEquals(Double.NaN, QueryProcessor.metrics.preparedStatementsRatio.value());

        for (int i = 0; i < 10; i++)
            session.execute(metricsStatement.bind(i, "val" + i));
        assertEquals(1.0, QueryProcessor.metrics.preparedStatementsRatio.value());

        for (int i = 0; i < 10; i++)
            session.execute(String.format("INSERT INTO junit.metricstest (id, val) VALUES (%d, '%s')", i, "val" + i));
        assertEquals(0.5, QueryProcessor.metrics.preparedStatementsRatio.value());
    }

    private void clearMetrics()
    {
        QueryProcessor.metrics.preparedStatementsExecuted.clear();
        QueryProcessor.metrics.regularStatementsExecuted.clear();
        QueryProcessor.metrics.preparedStatementsEvicted.clear();
    }
}

