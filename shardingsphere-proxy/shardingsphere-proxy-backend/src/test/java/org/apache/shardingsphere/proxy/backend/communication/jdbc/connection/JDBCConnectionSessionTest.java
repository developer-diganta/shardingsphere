/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.communication.jdbc.connection;

import com.google.common.collect.Multimap;
import io.netty.util.DefaultAttributeMap;
import lombok.SneakyThrows;
import org.apache.shardingsphere.infra.config.properties.ConfigurationProperties;
import org.apache.shardingsphere.infra.database.type.dialect.MySQLDatabaseType;
import org.apache.shardingsphere.infra.exception.ShardingSphereException;
import org.apache.shardingsphere.infra.executor.kernel.ExecutorEngine;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.infra.federation.optimizer.context.OptimizerContext;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.mode.metadata.persist.MetaDataPersistService;
import org.apache.shardingsphere.proxy.backend.communication.DatabaseCommunicationEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.datasource.JDBCBackendDataSource;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.transaction.BackendTransactionManager;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.transaction.ShardingSphereTransactionManagerEngine;
import org.apache.shardingsphere.transaction.context.TransactionContexts;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class JDBCConnectionSessionTest {
    
    private static final String SCHEMA_PATTERN = "schema_%s";
    
    @Mock
    private JDBCBackendDataSource backendDataSource;
    
    private final JDBCConnectionSession connectionSession = new JDBCConnectionSession(TransactionType.LOCAL, new DefaultAttributeMap());
    
    @Before
    public void setUp() throws ReflectiveOperationException {
        setContextManager();
        setBackendDataSource();
        connectionSession.setCurrentSchema(String.format(SCHEMA_PATTERN, 0));
    }
    
    private void setContextManager() throws ReflectiveOperationException {
        Field contextManagerField = ProxyContext.getInstance().getClass().getDeclaredField("contextManager");
        contextManagerField.setAccessible(true);
        ContextManager contextManager = mock(ContextManager.class, RETURNS_DEEP_STUBS);
        MetaDataContexts metaDataContexts = new MetaDataContexts(mock(MetaDataPersistService.class), createMetaDataMap(),
                mock(ShardingSphereRuleMetaData.class), mock(ExecutorEngine.class), new ConfigurationProperties(new Properties()), mock(OptimizerContext.class));
        when(contextManager.getMetaDataContexts()).thenReturn(metaDataContexts);
        TransactionContexts transactionContexts = createTransactionContexts();
        when(contextManager.getMetaDataContexts()).thenReturn(metaDataContexts);
        when(contextManager.getTransactionContexts()).thenReturn(transactionContexts);
        contextManagerField.set(ProxyContext.getInstance(), contextManager);
    }
    
    private Map<String, ShardingSphereMetaData> createMetaDataMap() {
        Map<String, ShardingSphereMetaData> result = new HashMap<>(10, 1);
        for (int i = 0; i < 10; i++) {
            String name = String.format(SCHEMA_PATTERN, i);
            ShardingSphereMetaData metaData = mock(ShardingSphereMetaData.class, RETURNS_DEEP_STUBS);
            when(metaData.getResource().getDatabaseType()).thenReturn(new MySQLDatabaseType());
            result.put(name, metaData);
        }
        return result;
    }
    
    private TransactionContexts createTransactionContexts() {
        TransactionContexts result = mock(TransactionContexts.class, RETURNS_DEEP_STUBS);
        for (int i = 0; i < 10; i++) {
            String name = String.format(SCHEMA_PATTERN, i);
            when(result.getEngines().get(name)).thenReturn(new ShardingSphereTransactionManagerEngine());
        }
        return result;
    }
    
    private void setBackendDataSource() throws ReflectiveOperationException {
        Field field = ProxyContext.getInstance().getClass().getDeclaredField("backendDataSource");
        field.setAccessible(true);
        field.set(ProxyContext.getInstance(), backendDataSource);
    }
    
    @After
    public void clean() throws ReflectiveOperationException {
        Field field = ProxyContext.getInstance().getClass().getDeclaredField("backendDataSource");
        field.setAccessible(true);
        Class<?> clazz = field.getType();
        Object datasource = clazz.getDeclaredConstructors()[0].newInstance();
        field.set(ProxyContext.getInstance(), datasource);
    }
    
    @Test
    public void assertGetConnectionCacheIsEmpty() throws SQLException {
        connectionSession.getTransactionStatus().setInTransaction(true);
        when(backendDataSource.getConnections(anyString(), anyString(), eq(2), any())).thenReturn(MockConnectionUtil.mockNewConnections(2));
        List<Connection> actualConnections = connectionSession.getConnections("ds1", 2, ConnectionMode.MEMORY_STRICTLY);
        assertThat(actualConnections.size(), is(2));
        assertThat(connectionSession.getConnectionSize(), is(2));
        assertTrue(connectionSession.getTransactionStatus().isInTransaction());
    }
    
    @Test
    public void assertGetConnectionSizeLessThanCache() throws SQLException {
        connectionSession.getTransactionStatus().setInTransaction(true);
        MockConnectionUtil.setCachedConnections(connectionSession, "ds1", 10);
        List<Connection> actualConnections = connectionSession.getConnections("ds1", 2, ConnectionMode.MEMORY_STRICTLY);
        assertThat(actualConnections.size(), is(2));
        assertThat(connectionSession.getConnectionSize(), is(10));
        assertTrue(connectionSession.getTransactionStatus().isInTransaction());
    }
    
    @Test
    public void assertGetConnectionSizeGreaterThanCache() throws SQLException {
        connectionSession.getTransactionStatus().setInTransaction(true);
        MockConnectionUtil.setCachedConnections(connectionSession, "ds1", 10);
        when(backendDataSource.getConnections(anyString(), anyString(), eq(2), any())).thenReturn(MockConnectionUtil.mockNewConnections(2));
        List<Connection> actualConnections = connectionSession.getConnections("ds1", 12, ConnectionMode.MEMORY_STRICTLY);
        assertThat(actualConnections.size(), is(12));
        assertThat(connectionSession.getConnectionSize(), is(12));
        assertTrue(connectionSession.getTransactionStatus().isInTransaction());
    }
    
    @Test
    public void assertGetConnectionWithConnectionPostProcessors() throws SQLException {
        connectionSession.getTransactionStatus().setInTransaction(true);
        when(backendDataSource.getConnections(anyString(), anyString(), eq(2), any())).thenReturn(MockConnectionUtil.mockNewConnections(2));
        setConnectionPostProcessors();
        List<Connection> actualConnections = connectionSession.getConnections("ds1", 2, ConnectionMode.MEMORY_STRICTLY);
        verify(connectionSession.getConnectionPostProcessors().iterator().next(), times(2)).process(any());
        assertThat(actualConnections.size(), is(2));
        assertTrue(connectionSession.getTransactionStatus().isInTransaction());
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private void setConnectionPostProcessors() {
        ConnectionPostProcessor invocation = mock(ConnectionPostProcessor.class);
        Collection<ConnectionPostProcessor> connectionPostProcessors = new LinkedList<>();
        connectionPostProcessors.add(invocation);
        Field field = connectionSession.getClass().getDeclaredField("connectionPostProcessors");
        field.setAccessible(true);
        field.set(connectionSession, connectionPostProcessors);
    }
    
    @Test
    public void assertMultiThreadsGetConnection() throws SQLException, InterruptedException {
        MockConnectionUtil.setCachedConnections(connectionSession, "ds1", 10);
        when(backendDataSource.getConnections(anyString(), anyString(), eq(2), any())).thenReturn(MockConnectionUtil.mockNewConnections(2));
        Thread thread1 = new Thread(this::assertOneThreadResult);
        Thread thread2 = new Thread(this::assertOneThreadResult);
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }
    
    @SneakyThrows(SQLException.class)
    private void assertOneThreadResult() {
        connectionSession.getTransactionStatus().setInTransaction(true);
        List<Connection> actualConnections = connectionSession.getConnections("ds1", 12, ConnectionMode.MEMORY_STRICTLY);
        assertThat(actualConnections.size(), is(12));
        assertThat(connectionSession.getConnectionSize(), is(12));
        assertTrue(connectionSession.getTransactionStatus().isInTransaction());
    }
    
    @Test(expected = ShardingSphereException.class)
    public void assertFailedSwitchTransactionTypeWhileBegin() throws SQLException {
        BackendTransactionManager transactionManager = new BackendTransactionManager(connectionSession);
        transactionManager.begin();
        connectionSession.getTransactionStatus().setTransactionType(TransactionType.XA);
    }
    
    @Test(expected = ShardingSphereException.class)
    public void assertFailedSwitchSchemaWhileBegin() throws SQLException {
        BackendTransactionManager transactionManager = new BackendTransactionManager(connectionSession);
        transactionManager.begin();
        connectionSession.setCurrentSchema("newSchema");
    }
    
    @Test
    public void assertIsNotSerialExecuteWhenNotInTransaction() {
        connectionSession.getTransactionStatus().setInTransaction(false);
        assertFalse(connectionSession.isSerialExecute());
    }
    
    @Test
    public void assertIsNotSerialExecuteWhenInTransactionAndBaseTransactionType() {
        connectionSession.getTransactionStatus().setInTransaction(false);
        connectionSession.getTransactionStatus().setTransactionType(TransactionType.BASE);
        assertFalse(connectionSession.isSerialExecute());
    }
    
    @Test
    public void assertIsSerialExecuteWhenInTransactionAndLocalTransactionType() {
        connectionSession.getTransactionStatus().setTransactionType(TransactionType.LOCAL);
        connectionSession.getTransactionStatus().setInTransaction(true);
        assertTrue(connectionSession.isSerialExecute());
    }
    
    @Test
    public void assertIsSerialExecuteWhenInTransactionAndXaTransactionType() {
        connectionSession.getTransactionStatus().setTransactionType(TransactionType.XA);
        connectionSession.getTransactionStatus().setInTransaction(true);
        assertTrue(connectionSession.isSerialExecute());
    }
    
    @Test
    public void assertSetFetchSizeAsExpected() throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, SQLException {
        Statement statement = mock(Statement.class);
        Method setFetchSizeMethod = connectionSession.getClass().getDeclaredMethod("setFetchSize", Statement.class);
        setFetchSizeMethod.setAccessible(true);
        setFetchSizeMethod.invoke(connectionSession, statement);
        verify(statement, times(1)).setFetchSize(Integer.MIN_VALUE);
    }
    
    @Test
    public void assertCloseConnectionsCorrectlyWhenNotForceRollback() throws NoSuchFieldException, IllegalAccessException, SQLException {
        Field field = connectionSession.getClass().getDeclaredField("cachedConnections");
        field.setAccessible(true);
        Multimap<String, Connection> cachedConnections = (Multimap<String, Connection>) field.get(connectionSession);
        Connection connection = prepareCachedConnections();
        cachedConnections.put("ignoredDataSourceName", connection);
        ConnectionStatus connectionStatus = mock(ConnectionStatus.class);
        prepareConnectionStatus(connectionStatus);
        connectionSession.closeConnections(false);
        verify(connection, times(1)).close();
        assertTrue(cachedConnections.isEmpty());
        verifyConnectionPostProcessorsEmpty();
    }
    
    @Test
    public void assertCloseConnectionsCorrectlyWhenForceRollbackAndNotInTransaction() throws SQLException {
        ConnectionStatus connectionStatus = mock(ConnectionStatus.class);
        prepareConnectionStatus(connectionStatus);
        connectionSession.getTransactionStatus().setInTransaction(false);
        Connection connection = prepareCachedConnections();
        connectionSession.closeConnections(true);
        verify(connection, never()).rollback();
    }
    
    @Test
    public void assertCloseConnectionsCorrectlyWhenForceRollbackAndInTransaction() throws SQLException {
        ConnectionStatus connectionStatus = mock(ConnectionStatus.class);
        prepareConnectionStatus(connectionStatus);
        connectionSession.getTransactionStatus().setInTransaction(true);
        Connection connection = prepareCachedConnections();
        connectionSession.closeConnections(true);
        verify(connection, times(1)).rollback();
    }
    
    @Test
    public void assertCloseConnectionsCorrectlyWhenSQLExceptionThrown() throws SQLException {
        ConnectionStatus connectionStatus = mock(ConnectionStatus.class);
        prepareConnectionStatus(connectionStatus);
        Connection connection = prepareCachedConnections();
        SQLException sqlException = new SQLException("");
        doThrow(sqlException).when(connection).close();
        assertTrue(connectionSession.closeConnections(false).contains(sqlException));
    }
    
    @Test
    public void assertCreateStorageResourceCorrectlyWhenConnectionModeMemoryStrictly() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        assertThat(connectionSession.createStorageResource(connection, ConnectionMode.MEMORY_STRICTLY, null), is(statement));
        verify(connection, times(1)).createStatement();
    }
    
    @Test
    public void assertGetConnectionsWithoutTransactions() throws SQLException {
        connectionSession.getTransactionStatus().setInTransaction(false);
        List<Connection> connections = MockConnectionUtil.mockNewConnections(1);
        when(backendDataSource.getConnections(anyString(), anyString(), eq(1), any())).thenReturn(connections);
        List<Connection> fetchedConnections = connectionSession.getConnections("ds1", 1, null);
        assertThat(fetchedConnections.size(), is(1));
        assertTrue(fetchedConnections.contains(connections.get(0)));
        assertConnectionsCached("ds1", connections);
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows(ReflectiveOperationException.class)
    private void assertConnectionsCached(final String dataSourceName, final Collection<Connection> connections) {
        Field field = connectionSession.getClass().getDeclaredField("cachedConnections");
        field.setAccessible(true);
        Multimap<String, Connection> cachedConnections = (Multimap<String, Connection>) field.get(connectionSession);
        assertTrue(cachedConnections.containsKey(dataSourceName));
        assertArrayEquals(cachedConnections.get(dataSourceName).toArray(), connections.toArray());
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows(ReflectiveOperationException.class)
    private Connection prepareCachedConnections() {
        Field field = connectionSession.getClass().getDeclaredField("cachedConnections");
        field.setAccessible(true);
        Multimap<String, Connection> cachedConnections = (Multimap<String, Connection>) field.get(connectionSession);
        Connection connection = mock(Connection.class);
        cachedConnections.put("ignoredDataSourceName", connection);
        return connection;
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private void prepareConnectionStatus(final ConnectionStatus connectionStatus) {
        Field field = connectionSession.getClass().getDeclaredField("connectionStatus");
        field.setAccessible(true);
        field.set(connectionSession, connectionStatus);
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows(ReflectiveOperationException.class)
    private void verifyConnectionPostProcessorsEmpty() {
        Field field = connectionSession.getClass().getDeclaredField("connectionPostProcessors");
        field.setAccessible(true);
        Collection<ConnectionPostProcessor> connectionPostProcessors = (Collection<ConnectionPostProcessor>) field.get(connectionSession);
        assertTrue(connectionPostProcessors.isEmpty());
    }
    
    @Test
    public void assertAddDatabaseCommunicationEngine() {
        DatabaseCommunicationEngine expectedEngine = mock(DatabaseCommunicationEngine.class);
        connectionSession.add(expectedEngine);
        Collection<DatabaseCommunicationEngine> actual = getDatabaseCommunicationEngines();
        assertThat(actual.size(), is(1));
        assertThat(actual.iterator().next(), is(expectedEngine));
    }
    
    @Test
    public void assertMarkDatabaseCommunicationEngineInUse() {
        DatabaseCommunicationEngine expectedEngine = mock(DatabaseCommunicationEngine.class);
        connectionSession.add(expectedEngine);
        connectionSession.markResourceInUse(expectedEngine);
        Collection<DatabaseCommunicationEngine> actual = getInUseDatabaseCommunicationEngines();
        assertThat(actual.size(), is(1));
        assertThat(actual.iterator().next(), is(expectedEngine));
    }
    
    @Test
    public void assertUnmarkInUseDatabaseCommunicationEngine() {
        DatabaseCommunicationEngine engine = mock(DatabaseCommunicationEngine.class);
        Collection<DatabaseCommunicationEngine> actual = getInUseDatabaseCommunicationEngines();
        actual.add(engine);
        connectionSession.unmarkResourceInUse(engine);
        assertTrue(actual.isEmpty());
    }
    
    @Test
    public void assertCloseDatabaseCommunicationEngines() throws SQLException {
        DatabaseCommunicationEngine engine = mock(DatabaseCommunicationEngine.class);
        DatabaseCommunicationEngine inUseEngine = mock(DatabaseCommunicationEngine.class);
        SQLException expectedException = mock(SQLException.class);
        doThrow(expectedException).when(engine).close();
        Collection<DatabaseCommunicationEngine> databaseCommunicationEngines = getDatabaseCommunicationEngines();
        Collection<DatabaseCommunicationEngine> inUseDatabaseCommunicationEngines = getInUseDatabaseCommunicationEngines();
        databaseCommunicationEngines.add(engine);
        databaseCommunicationEngines.add(inUseEngine);
        inUseDatabaseCommunicationEngines.add(inUseEngine);
        Collection<SQLException> actual = connectionSession.closeDatabaseCommunicationEngines(false);
        assertThat(actual.size(), is(1));
        assertThat(actual.iterator().next(), is(expectedException));
        assertThat(inUseDatabaseCommunicationEngines.size(), is(1));
        assertThat(databaseCommunicationEngines.size(), is(1));
        verify(engine).close();
        connectionSession.closeDatabaseCommunicationEngines(true);
        verify(inUseEngine).close();
        assertTrue(databaseCommunicationEngines.isEmpty());
        assertTrue(inUseDatabaseCommunicationEngines.isEmpty());
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private Collection<DatabaseCommunicationEngine> getDatabaseCommunicationEngines() {
        Field field = JDBCConnectionSession.class.getDeclaredField("databaseCommunicationEngines");
        field.setAccessible(true);
        return (Collection<DatabaseCommunicationEngine>) field.get(connectionSession);
    }
    
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private Collection<DatabaseCommunicationEngine> getInUseDatabaseCommunicationEngines() {
        Field field = JDBCConnectionSession.class.getDeclaredField("inUseDatabaseCommunicationEngines");
        field.setAccessible(true);
        return (Collection<DatabaseCommunicationEngine>) field.get(connectionSession);
    }
}