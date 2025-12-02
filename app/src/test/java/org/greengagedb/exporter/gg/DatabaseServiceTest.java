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
package org.greengagedb.exporter.gg;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import org.greengagedb.exporter.model.GreengageVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseServiceTest {

    private DatabaseService databaseService;

    @Mock
    private AgroalDataSource dataSource;

    @Mock
    private AgroalDataSourceConfiguration dataSourceConfig;

    @Mock
    private AgroalConnectionPoolConfiguration poolConfig;

    @Mock
    private Connection mockConnection;

    @Mock
    private Statement mockStatement;

    @Mock
    private ResultSet mockResultSet;

    @BeforeEach
    void setUp() {
        databaseService = new DatabaseService(dataSource);
    }

    @Test
    void testConstructor_NullDataSource_ThrowsException() {
        assertThrows(NullPointerException.class, () -> new DatabaseService(null));
    }

    @Test
    void testGetUrl_Exception_ReturnsUnavailable() {
        // Setup - throw exception when trying to get URL
        when(dataSource.getConfiguration()).thenThrow(new RuntimeException("Config error"));

        // Execute
        String url = databaseService.getUrl();

        // Verify
        assertEquals("unavailable", url);
    }

    @Test
    void testGetPoolledConnection_Success() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);

        // Execute
        Connection connection = databaseService.getPoolledConnection();

        // Verify
        assertNotNull(connection);
        assertEquals(mockConnection, connection);
        verify(dataSource).getConnection();
    }

    @Test
    void testGetPoolledConnection_ThrowsSQLException() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection pool exhausted"));

        // Execute & Verify
        assertThrows(SQLException.class, () -> databaseService.getPoolledConnection());
    }

    @Test
    void testTestConnection_Success() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT 1")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(1);

        // Execute
        boolean result = databaseService.testConnection();

        // Verify
        assertTrue(result);
    }

    @Test
    void testTestConnection_QueryFails_ReturnsFalse() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Execute
        boolean result = databaseService.testConnection();

        // Verify
        assertFalse(result);
    }

    @Test
    void testTestConnection_QueryReturnsNoRows_ReturnsFalse() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT 1")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false); // No rows

        // Execute
        boolean result = databaseService.testConnection();

        // Verify
        assertFalse(result);
    }

    @Test
    void testTestConnection_QueryReturnsWrongValue_ReturnsFalse() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT 1")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(42); // Wrong value

        // Execute
        boolean result = databaseService.testConnection();

        // Verify
        assertFalse(result);
    }

    @Test
    void testTestConnection_UnexpectedException_ReturnsFalse() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenThrow(new RuntimeException("Unexpected error"));

        // Execute
        boolean result = databaseService.testConnection();

        // Verify
        assertFalse(result);
    }

    @Test
    void testDetectVersion_Success() throws Exception {
        // Setup
        String versionString = "PostgreSQL 16.0 (Greengage 6.4.0 build 1234) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute
        GreengageVersion version = databaseService.detectVersion();

        // Verify
        assertNotNull(version);
        assertEquals(6, version.major());
        assertEquals(4, version.minor());
        assertEquals(0, version.patch());
    }

    @Test
    void testDetectVersion_Caching_SecondCallReturnsCached() throws Exception {
        // Setup
        String versionString = "PostgreSQL 16.0 (Greengage 7.1.2 build 5678) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute - first call
        GreengageVersion version1 = databaseService.detectVersion();

        // Execute - second call
        GreengageVersion version2 = databaseService.detectVersion();

        // Verify - should be same instance (cached)
        assertSame(version1, version2);
        assertEquals(7, version1.major());
        assertEquals(1, version1.minor());
        assertEquals(2, version1.patch());

        // Verify database was only queried once due to caching
        verify(mockConnection, times(1)).createStatement();
    }

    @Test
    void testDetectVersion_NoResultSet_ThrowsSQLException() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false); // No rows

        // Execute & Verify
        assertThrows(SQLException.class, () -> databaseService.detectVersion());
    }

    @Test
    void testDetectVersion_InvalidVersionString_ThrowsSQLException() throws SQLException {
        // Setup - version string that doesn't match pattern
        String versionString = "PostgreSQL 16.0 on x86_64-pc-linux-gnu"; // No Greengage version
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute & Verify
        assertThrows(SQLException.class, () -> databaseService.detectVersion());
    }

    @Test
    void testDetectVersion_ConnectionFailure_ThrowsSQLException() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection failed"));

        // Execute & Verify
        assertThrows(SQLException.class, () -> databaseService.detectVersion());
    }

    @Test
    void testDetectVersion_ParsedVersionCorrectly() throws Exception {
        // Setup - test different version formats
        String versionString = "PostgreSQL 16.0 (Greengage 10.25.99 build abc) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute
        GreengageVersion version = databaseService.detectVersion();

        // Verify
        assertNotNull(version);
        assertEquals(10, version.major());
        assertEquals(25, version.minor());
        assertEquals(99, version.patch());
        assertEquals("10.25.99", version.fullVersion());
    }

    @Test
    void testGetUrl_ConfigurationChain_HandlesNulls() {
        // Setup - null somewhere in the configuration chain
        when(dataSource.getConfiguration()).thenReturn(dataSourceConfig);
        when(dataSourceConfig.connectionPoolConfiguration()).thenReturn(poolConfig);
        when(poolConfig.connectionFactoryConfiguration()).thenReturn(null);

        // Execute
        String url = databaseService.getUrl();

        // Verify
        assertEquals("unavailable", url);
    }

    @Test
    void testTestConnection_ResourcesClosed() throws SQLException {
        // Setup
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT 1")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(1);

        // Execute
        databaseService.testConnection();

        // Verify resources are closed (try-with-resources)
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void testDetectVersion_ResourcesClosed() throws Exception {
        // Setup
        String versionString = "PostgreSQL 16.0 (Greengage 6.0.0 build 1) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute
        databaseService.detectVersion();

        // Verify resources are closed
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    void testDetectVersion_MinimumSupportedVersion() throws Exception {
        // Setup - test version 6.0.0 (minimum supported)
        String versionString = "PostgreSQL 16.0 (Greengage 6.0.0 build 1) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute
        GreengageVersion version = databaseService.detectVersion();

        // Verify
        assertNotNull(version);
        assertTrue(version.isSupported(), "Version 6.0.0 should be supported");
        assertEquals(6, version.major());
    }

    @Test
    void testDetectVersion_Version7Features() throws Exception {
        // Setup - test version 7.x to ensure isAtLeastVersion7() works
        String versionString = "PostgreSQL 16.0 (Greengage 7.0.0 build 1) on x86_64-pc-linux-gnu";
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        when(mockStatement.executeQuery("SELECT version()")).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn(versionString);

        // Execute
        GreengageVersion version = databaseService.detectVersion();

        // Verify
        assertNotNull(version);
        assertTrue(version.isAtLeastVersion7(), "Version 7.0.0 should be at least version 7");
        assertTrue(version.isSupported(), "Version 7.0.0 should be supported");
    }
}

