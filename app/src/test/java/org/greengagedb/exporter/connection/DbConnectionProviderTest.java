/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greengagedb.exporter.connection;

import io.agroal.api.AgroalDataSource;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.config.PerDBMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DbConnectionProviderTest {

    private DbConnectionProvider provider;

    @Mock
    private CollectorsConfig collectorsConfig;

    @Mock
    private CollectorsConfig.PerDB perDBConfig;

    @Mock
    private DbDatasourceFactory datasourceFactory;

    @Mock
    private Connection mockConnection;

    @Mock
    private Statement mockStatement;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private AgroalDataSource mockDataSource1;

    @Mock
    private AgroalDataSource mockDataSource2;

    @BeforeEach
    void setUp() throws Exception {
        provider = new DbConnectionProvider();

        // Inject mocked dependencies via reflection
        setField(provider, "collectorsConfig", collectorsConfig);
        setField(provider, "datasourceFactory", datasourceFactory);

        // Setup default mock behavior (lenient to avoid UnnecessaryStubbingException)
        lenient().when(collectorsConfig.perDB()).thenReturn(perDBConfig);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void setupDatabaseQueryResult(String... databaseNames) throws SQLException {
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);

        // Setup ResultSet to return the specified database names
        if (databaseNames.length == 0) {
            when(mockResultSet.next()).thenReturn(false);
        } else {
            Boolean[] nexts = new Boolean[databaseNames.length + 1];
            for (int i = 0; i < databaseNames.length; i++) {
                nexts[i] = true;
            }
            nexts[databaseNames.length] = false; // Last call returns false

            when(mockResultSet.next()).thenReturn(true, nexts);

            // Setup getString to return database names in sequence
            when(mockResultSet.getString("datname"))
                    .thenReturn(databaseNames[0],
                            java.util.Arrays.copyOfRange(databaseNames, 1, databaseNames.length));
        }
    }

    @Test
    void testGetDataSources_ModeALL_ReturnsAllDatabases() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2", "db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);
        when(datasourceFactory.create("db3")).thenReturn(mock(AgroalDataSource.class));

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(3, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory).create("db2");
        verify(datasourceFactory).create("db3");
    }

    @Test
    void testGetDataSources_ModeINCLUDE_ReturnsOnlyIncludedDatabases() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2", "db3", "db4");
        when(perDBConfig.mode()).thenReturn(PerDBMode.INCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of("db1", "db3"));
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db3")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(2, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory).create("db3");
        verify(datasourceFactory, never()).create("db2");
        verify(datasourceFactory, never()).create("db4");
    }

    @Test
    void testGetDataSources_ModeEXCLUDE_ReturnsAllExceptExcluded() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2", "db3", "db4");
        when(perDBConfig.mode()).thenReturn(PerDBMode.EXCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of("db2", "db4"));
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db3")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(2, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory).create("db3");
        verify(datasourceFactory, never()).create("db2");
        verify(datasourceFactory, never()).create("db4");
    }

    @Test
    void testGetDataSources_ModeNONE_ReturnsEmptyList() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2", "db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.NONE);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(0, result.size());
        verify(datasourceFactory, never()).create(anyString());
    }

    @Test
    void testGetDataSources_CacheEnabled_ReusesCachedDataSources() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(true);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);

        // First call
        List<DataSource> result1 = provider.getDataSources(mockConnection);
        assertEquals(2, result1.size());

        // Second call - should reuse cached datasources
        setupDatabaseQueryResult("db1", "db2"); // Reset result set
        List<DataSource> result2 = provider.getDataSources(mockConnection);
        assertEquals(2, result2.size());

        // Verify factory was called only once per database
        verify(datasourceFactory, times(1)).create("db1");
        verify(datasourceFactory, times(1)).create("db2");
    }

    @Test
    void testGetDataSources_CacheDisabled_CreatesNewDataSources() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);

        // First call
        List<DataSource> result1 = provider.getDataSources(mockConnection);
        assertEquals(1, result1.size());

        // Second call - should create new datasources
        setupDatabaseQueryResult("db1");
        List<DataSource> result2 = provider.getDataSources(mockConnection);
        assertEquals(1, result2.size());

        // Verify factory was called twice (once per call)
        verify(datasourceFactory, times(2)).create("db1");
    }

    @Test
    void testGetDataSources_EmptyDatabaseList_ReturnsEmptyList() throws Exception {
        // Setup
        setupDatabaseQueryResult(); // No databases
        lenient().when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(0, result.size());
        verify(datasourceFactory, never()).create(anyString());
    }

    @Test
    void testGetDataSources_IncludeModeWithNonExistentDatabase() throws Exception {
        // Setup - database "db99" is in include list but doesn't exist
        setupDatabaseQueryResult("db1", "db2");
        when(perDBConfig.mode()).thenReturn(PerDBMode.INCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of("db1", "db99"));
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - only db1 should be included (db99 doesn't exist)
        assertEquals(1, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory, never()).create("db99");
    }

    @Test
    void testGetDataSources_ExcludeModeWithNonExistentDatabase() throws Exception {
        // Setup - database "db99" is in exclude list but doesn't exist anyway
        setupDatabaseQueryResult("db1", "db2");
        when(perDBConfig.mode()).thenReturn(PerDBMode.EXCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of("db99"));
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - both db1 and db2 should be included
        assertEquals(2, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory).create("db2");
    }

    @Test
    void testGetDataSources_DataSourceCreationFailure_ContinuesWithOthers() throws Exception {
        // Setup - db2 fails to create
        setupDatabaseQueryResult("db1", "db2", "db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenThrow(new SQLException("Connection failed"));
        when(datasourceFactory.create("db3")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - should have 2 datasources (db1 and db3), skipping failed db2
        assertEquals(2, result.size());
        verify(datasourceFactory).create("db1");
        verify(datasourceFactory).create("db2");
        verify(datasourceFactory).create("db3");
    }

    @Test
    void testGetDataSources_DatabaseQueryFailure_ReturnsEmptyList() throws Exception {
        // Setup - query throws exception
        when(mockConnection.createStatement()).thenThrow(new SQLException("Query failed"));
        lenient().when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(0, result.size());
        verify(datasourceFactory, never()).create(anyString());
    }

    @Test
    void testCleanup_WithTemporaryDataSources() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);

        // Create temporary datasources
        provider.getDataSources(mockConnection);

        // Execute cleanup
        provider.cleanup();

        // Verify datasources were closed
        verify(mockDataSource1).close();
        verify(mockDataSource2).close();
    }

    @Test
    void testCleanup_WithCachedDataSources_DoesNotCloseThem() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(true);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);

        // Create cached datasources
        provider.getDataSources(mockConnection);

        // Execute cleanup
        provider.cleanup();

        // Verify cached datasources were NOT closed
        verify(mockDataSource1, never()).close();
    }

    @Test
    void testCleanup_MultipleCallsSafe() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);

        provider.getDataSources(mockConnection);

        // Execute cleanup multiple times
        provider.cleanup();
        provider.cleanup();
        provider.cleanup();

        // Verify datasource was closed only once
        verify(mockDataSource1, times(1)).close();
    }

    @Test
    void testGetDataSources_IncludeModeWithEmptyList_ReturnsEmpty() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2", "db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.INCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of()); // Empty include list

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify
        assertEquals(0, result.size());
        verify(datasourceFactory, never()).create(anyString());
    }

    @Test
    void testGetDataSources_ExcludeModeWithEmptyList_ReturnsAll() throws Exception {
        // Setup
        setupDatabaseQueryResult("db1", "db2");
        when(perDBConfig.mode()).thenReturn(PerDBMode.EXCLUDE);
        when(perDBConfig.dbList()).thenReturn(Set.of()); // Empty exclude list
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - all databases should be included
        assertEquals(2, result.size());
    }

    @Test
    void testGetDataSources_NullDataSourceFromFactory_SkipsIt() throws Exception {
        // Setup - factory returns null for db2
        setupDatabaseQueryResult("db1", "db2", "db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("db1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(null);
        when(datasourceFactory.create("db3")).thenReturn(mockDataSource2);

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - should only have 2 datasources (skipping null)
        assertEquals(2, result.size());
    }

    @Test
    void testGetDataSources_CaseInsensitiveDatabaseNames() throws Exception {
        // Setup
        setupDatabaseQueryResult("DB1", "db2", "Db3");
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);
        when(perDBConfig.connectionCacheEnabled()).thenReturn(false);

        when(datasourceFactory.create("DB1")).thenReturn(mockDataSource1);
        when(datasourceFactory.create("db2")).thenReturn(mockDataSource2);
        when(datasourceFactory.create("Db3")).thenReturn(mock(AgroalDataSource.class));

        // Execute
        List<DataSource> result = provider.getDataSources(mockConnection);

        // Verify - should handle different cases
        assertEquals(3, result.size());
    }
}

