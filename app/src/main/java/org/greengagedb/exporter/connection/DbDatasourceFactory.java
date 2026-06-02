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
import io.agroal.api.configuration.supplier.AgroalPropertiesReader;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Factory for creating per-database DataSource instances.
 *
 * <p>This factory creates isolated DataSources for each database, allowing collectors
 * to work with specific databases independently. Each DataSource is configured with:
 * <ul>
 *   <li>Minimal connection pool (1 connection)</li>
 *   <li>Short max lifetime (2 minutes) to avoid stale connections</li>
 *   <li>Database-specific JDBC URL</li>
 * </ul>
 */
@Slf4j
@ApplicationScoped
public class DbDatasourceFactory {

    private static final int CONNECTION_POOL_SIZE = 1;
    private static final int CONNECTION_MAX_LIFETIME_SECONDS = 120;
    private static final String POSTGRES_JDBC_DRIVER_PREFIX = "jdbc:postgresql://";
    private static final String MESSAGE_TEMPLATE = "Created JDBC URL for database '{}'(encoded name is '{}'): {}";

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    // Keep original query parameters because they may contain connection options
    // such as sslmode, connectTimeout, targetServerType, etc.
    @ConfigProperty(name = "app.datasource.preserve-jdbc-url-parameters")
    boolean preserveJdbcUrlParameters;

    /**
     * Create a new DataSource for the specified database.
     *
     * @param databaseName Name of the target database
     * @return Configured AgroalDataSource for the database
     * @throws SQLException If DataSource creation fails
     * @throws IllegalArgumentException If database name is invalid
     */
    public AgroalDataSource create(String databaseName) throws SQLException {
        validateDatabaseName(databaseName);

        Map<String, String> props = new HashMap<>();
        props.put(AgroalPropertiesReader.JDBC_URL, createJdbcUrlForDatabase(databaseName));
        props.put(AgroalPropertiesReader.PRINCIPAL, username);
        props.put(AgroalPropertiesReader.CREDENTIAL, password);
        props.put(AgroalPropertiesReader.MAX_SIZE, String.valueOf(CONNECTION_POOL_SIZE));
        props.put(AgroalPropertiesReader.MIN_SIZE, String.valueOf(CONNECTION_POOL_SIZE));
        props.put(AgroalPropertiesReader.INITIAL_SIZE, String.valueOf(CONNECTION_POOL_SIZE));
        props.put(AgroalPropertiesReader.MAX_LIFETIME_S, String.valueOf(CONNECTION_MAX_LIFETIME_SECONDS));

        try {
            AgroalDataSource dataSource = AgroalDataSource.from(
                    new AgroalPropertiesReader().readProperties(props).get()
            );
            log.debug("Created DataSource for database '{}' with pool size {}", databaseName, CONNECTION_POOL_SIZE);
            return dataSource;
        } catch (SQLException e) {
            log.error("Failed to create DataSource for database '{}': {}", databaseName, e.getMessage());
            throw e;
        }
    }

    /**
     * Create a JDBC URL for the specified database.
     *
     * @param databaseName Name of the target database
     * @return Modified JDBC URL pointing to the specified database
     */
    public String createJdbcUrlForDatabase(String databaseName) {
        Properties props = org.postgresql.Driver.parseURL(jdbcUrl, null);
        if (props == null) {
            throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL: " + jdbcUrl);
        }
        // To support databases with special symbols
        String encodedDatabaseName = URLEncoder.encode(databaseName, StandardCharsets.UTF_8);
        String modifiedUrl = getModifiedUrl(encodedDatabaseName);
        log.debug(MESSAGE_TEMPLATE, databaseName, encodedDatabaseName, modifiedUrl);
        return modifiedUrl;
    }

    private String getModifiedUrl(String encodedDatabaseName) {
        int queryStart = jdbcUrl.indexOf('?');
        String mainPart = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        String queryPart = getQueryPart(queryStart);
        if (mainPart.startsWith(POSTGRES_JDBC_DRIVER_PREFIX)) {
            int slashAfterHost = mainPart.indexOf('/', POSTGRES_JDBC_DRIVER_PREFIX.length());
            if (slashAfterHost < 0) {
                return mainPart + "/" + encodedDatabaseName + queryPart;
            } else {
                return mainPart.substring(0, slashAfterHost + 1) + encodedDatabaseName + queryPart;
            }
        } else {
            return "jdbc:postgresql:" + encodedDatabaseName + queryPart;
        }
    }

    private String getQueryPart(int queryStart) {
        if (preserveJdbcUrlParameters) {
            return queryStart >= 0 ? jdbcUrl.substring(queryStart) : "";
        }
        return "";
    }

    /**
     * Validate the database name to prevent SQL injection or invalid names.
     *
     * @param databaseName Database name to validate
     * @throws IllegalArgumentException If database name is invalid
     */
    private void validateDatabaseName(String databaseName) {
        if (databaseName == null || databaseName.trim().isEmpty()) {
            throw new IllegalArgumentException("Database name cannot be null or empty");
        }
    }
}
