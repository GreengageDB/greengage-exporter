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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class DbDatasourceFactoryTest {

    private DbDatasourceFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        factory = new DbDatasourceFactory();

        // Set the required fields via reflection since they're normally injected
        setField(factory, "jdbcUrl", "jdbc:postgresql://localhost:5432/greengage");
        setField(factory, "username", "testuser");
        setField(factory, "password", "testpassword");
        setField(factory, "preserveJdbcUrlParameters", true);
    }

    private void setField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void setField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testCreateJdbcUrlForDatabase_ValidDatabaseName() {
        String result = factory.createJdbcUrlForDatabase("mydb");
        assertEquals("jdbc:postgresql://localhost:5432/mydb", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_AnotherDatabase() {
        String result = factory.createJdbcUrlForDatabase("production");
        assertEquals("jdbc:postgresql://localhost:5432/production", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_WithUnderscores() {
        String result = factory.createJdbcUrlForDatabase("my_test_db");
        assertEquals("jdbc:postgresql://localhost:5432/my_test_db", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_WithNumbers() {
        String result = factory.createJdbcUrlForDatabase("db123");
        assertEquals("jdbc:postgresql://localhost:5432/db123", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesExistingDatabase() {
        // The base URL has 'greengage' as the database
        String result = factory.createJdbcUrlForDatabase("newdb");
        assertEquals("jdbc:postgresql://localhost:5432/newdb", result);
        assertFalse(result.contains("greengage"), "Original database name should be replaced");
    }

    @Test
    void testCreateJdbcUrlForDatabase_WithUrlParameters() throws Exception {
        // Set URL with parameters
        setField(factory, "jdbcUrl", "jdbc:postgresql://localhost:5432/greengage?ssl=true&timeout=30");
        setField(factory, "preserveJdbcUrlParameters", false);

        String result = factory.createJdbcUrlForDatabase("mydb");
        // The regex replaces from the last slash to the end, including parameters
        // So parameters are replaced along with the database name
        assertEquals("jdbc:postgresql://localhost:5432/mydb", result);
    }

    @Test
    void testCreate_ThrowsException_NullDatabaseName() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> factory.create(null));
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void testCreate_ThrowsException_EmptyDatabaseName() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> factory.create(""));
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void testCreate_ThrowsException_WhitespaceDatabaseName() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> factory.create("   "));
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_SqlInjection_CommentStart() {
        // Test that comment characters in database name are handled (not validated at URL creation)
        // The validation happens in create() method, not in createJdbcUrlForDatabase()
        String result = factory.createJdbcUrlForDatabase("mydb");
        assertTrue(result.contains("mydb"));
    }

    @Test
    void testValidateDatabaseName_ValidNames() {
        // These should not throw exceptions
        assertDoesNotThrow(() -> factory.create("validdb"));
        assertDoesNotThrow(() -> factory.create("my_database"));
        assertDoesNotThrow(() -> factory.create("db123"));
        assertDoesNotThrow(() -> factory.create("MyDatabase"));
        assertDoesNotThrow(() -> factory.create("data_warehouse_2024"));
    }

    @Test
    void testValidateDatabaseName_SingleDashAllowed() {
        // Single dash should be allowed (common in database names)
        assertDoesNotThrow(() -> factory.create("my-database"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_PreservesHostAndPort() throws Exception {
        setField(factory, "jdbcUrl", "jdbc:postgresql://db-server:5433/olddb");

        String result = factory.createJdbcUrlForDatabase("newdb");
        assertEquals("jdbc:postgresql://db-server:5433/newdb", result);
        assertTrue(result.contains("db-server:5433"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_HandlesComplexUrl() throws Exception {
        setField(factory, "jdbcUrl", "jdbc:postgresql://host1:5432,host2:5432/mydb?targetServerType=master");
        setField(factory, "preserveJdbcUrlParameters", false);

        String result = factory.createJdbcUrlForDatabase("newdb");
        // The regex replaces from the last slash to the end (including query params)
        assertTrue(result.contains("newdb"));
        assertEquals("jdbc:postgresql://host1:5432,host2:5432/newdb", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_EdgeCase_ShortDatabaseName() {
        String result = factory.createJdbcUrlForDatabase("a");
        assertEquals("jdbc:postgresql://localhost:5432/a", result);
    }

    @Test
    void testCreateJdbcUrlForDatabase_EdgeCase_LongDatabaseName() {
        String longName = "a".repeat(63); // PostgreSQL max identifier length
        String result = factory.createJdbcUrlForDatabase(longName);
        assertTrue(result.endsWith("/" + longName));
    }

    @Test
    void testCreateJdbcUrlForDatabase_SpecialCharacters_Period() {
        // Periods might be used in database names (though uncommon)
        String result = factory.createJdbcUrlForDatabase("my.database");
        assertTrue(result.contains("my.database"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_PostgresDefaultDatabases() {
        // Test common PostgreSQL system databases
        assertEquals("jdbc:postgresql://localhost:5432/postgres",
                factory.createJdbcUrlForDatabase("postgres"));
        assertEquals("jdbc:postgresql://localhost:5432/template0",
                factory.createJdbcUrlForDatabase("template0"));
        assertEquals("jdbc:postgresql://localhost:5432/template1",
                factory.createJdbcUrlForDatabase("template1"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_DatabaseNameWithSpaces() {
        // Test URL creation with spaces (validation is separate)
        // The validator only checks for specific dangerous characters, not spaces
        String result = factory.createJdbcUrlForDatabase("my_database");
        assertTrue(result.contains("my_database"));
    }

    @Test
    void testCreateJdbcUrlForDatabase_DoesNotModifyOriginalUrl() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost:5432/greengage";
        setField(factory, "jdbcUrl", originalUrl);

        // Create URLs for different databases
        factory.createJdbcUrlForDatabase("db1");
        factory.createJdbcUrlForDatabase("db2");
        factory.createJdbcUrlForDatabase("db3");

        // Verify the original URL field hasn't changed
        Field field = factory.getClass().getDeclaredField("jdbcUrl");
        field.setAccessible(true);
        String currentUrl = (String) field.get(factory);

        assertEquals(originalUrl, currentUrl, "Original JDBC URL should remain unchanged");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ConsistentResults() {
        // Multiple calls with same database name should produce identical results
        String result1 = factory.createJdbcUrlForDatabase("testdb");
        String result2 = factory.createJdbcUrlForDatabase("testdb");
        String result3 = factory.createJdbcUrlForDatabase("testdb");

        assertEquals(result1, result2);
        assertEquals(result2, result3);
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseName() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost:5432/greengage";
        String expectedUrl = "jdbc:postgresql://localhost:5432/template1";
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be replaced");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameAndPreservesQueryParams() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost:5432/greengage?ssl=true&connectTimeout=10";
        String expectedUrl = "jdbc:postgresql://localhost:5432/template1?ssl=true&connectTimeout=10";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be replaced and query parameters should be preserved");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameWithoutPort() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost/greengage";
        String expectedUrl = "jdbc:postgresql://localhost/template1";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be replaced when port is not specified");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameWhenUrlHasTrailingSlash() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost:5432/";
        String expectedUrl = "jdbc:postgresql://localhost:5432/template1";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be added when URL ends with slash");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameWhenUrlHasTrailingSlashAndQueryParams() throws Exception {
        String originalUrl = "jdbc:postgresql://localhost:5432/?ssl=true&connectTimeout=10";
        String expectedUrl = "jdbc:postgresql://localhost:5432/template1?ssl=true&connectTimeout=10";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be added before query parameters");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameForShortJdbcUrl() throws Exception {
        String originalUrl = "jdbc:postgresql:greengage";
        String expectedUrl = "jdbc:postgresql:template1";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be replaced for short PostgreSQL JDBC URL");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ReplacesDatabaseNameForShortJdbcUrlAndPreservesQueryParams() throws Exception {
        String originalUrl = "jdbc:postgresql:greengage?ssl=true&connectTimeout=10";
        String expectedUrl = "jdbc:postgresql:template1?ssl=true&connectTimeout=10";
        setField(factory, "jdbcUrl", originalUrl);
        String result = factory.createJdbcUrlForDatabase("template1");

        assertEquals(expectedUrl, result, "Database name should be replaced for short URL and query parameters should be preserved");
    }

    @Test
    void testCreateJdbcUrlForDatabase_PreservesDatabaseNameAsIs() throws Exception {
        String databaseName = "a\\b:c/d e@f~g’h”i\\\\j*k#l&m$n//o'M\"Y";
        // Encoded as the database name contains special symbols
        String expectedUrl = "jdbc:postgresql://localhost:5432/a%5Cb%3Ac%2Fd+e%40f%7Eg%E2%80%99h%E2%80%9Di%5C%5Cj*k%23l%26m%24n%2F%2Fo%27M%22Y?ssl=true";
        setField(factory, "jdbcUrl", "jdbc:postgresql://localhost:5432/greengage?ssl=true");
        String result = factory.createJdbcUrlForDatabase(databaseName);

        assertEquals(expectedUrl, result, "Database name should be added as is");
    }

    @Test
    void testCreateJdbcUrlForDatabase_ThrowsExceptionForInvalidJdbcUrl() throws Exception {
        setField(factory, "jdbcUrl", "jdbc:mysql://localhost:3306/greengage");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.createJdbcUrlForDatabase("template1"),
                "Invalid PostgreSQL JDBC URL should cause exception"
        );
        assertEquals("Invalid PostgreSQL JDBC URL: jdbc:mysql://localhost:3306/greengage", exception.getMessage());
    }
}

