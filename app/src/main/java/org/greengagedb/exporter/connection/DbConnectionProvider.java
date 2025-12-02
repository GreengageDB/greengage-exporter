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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.config.PerDBMode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

/**
 * Provides per-database connections for collectors that need to work with individual databases.
 *
 * <p>This provider manages a pool of DataSources, one for each database that needs to be monitored.
 * It supports two modes:
 * <ul>
 *   <li>Cached mode: DataSources are created once and reused across scrapes</li>
 *   <li>Non-cached mode: DataSources are created for each scrape and closed afterward</li>
 * </ul>
 */
@Slf4j
@ApplicationScoped
public class DbConnectionProvider {
    private static final String SQL = """
            SELECT datname
            FROM pg_database
            WHERE datallowconn
              AND datistemplate = false;
            """;

    private final Map<String, AgroalDataSource> dataSourceCache = new HashMap<>();
    private final List<AgroalDataSource> temporaryDataSources = new ArrayList<>();

    @Inject
    CollectorsConfig collectorsConfig;

    @Inject
    DbDatasourceFactory datasourceFactory;

    /**
     * Get a list of DataSources for all allowed databases.
     *
     * @param baseConnection Connection to the database for querying available databases
     * @return List of DataSources, one for each allowed database
     */
    public List<DataSource> getDataSources(Connection baseConnection) {
        Set<String> allDbs = fetchAllDatabases(baseConnection);
        if (allDbs.isEmpty()) {
            log.warn("No databases found");
            return Collections.emptyList();
        }

        Set<String> allowedDbs = filterDatabases(allDbs, collectorsConfig.perDB());
        if (allowedDbs.isEmpty()) {
            if (collectorsConfig.perDB().mode() != PerDBMode.NONE) {
                log.warn("No databases allowed after filtering. Available databases: {}", allDbs);
            } else {
                log.debug("PerDB mode is NONE, skipping all databases.");
            }
            return Collections.emptyList();
        }

        boolean cacheEnabled = collectorsConfig.perDB().connectionCacheEnabled();
        List<DataSource> dataSources = new ArrayList<>(allowedDbs.size());

        for (String dbName : allowedDbs) {
            try {
                AgroalDataSource dataSource = createOrGetDataSource(dbName, cacheEnabled);
                if (dataSource != null) {
                    dataSources.add(dataSource);
                }
            } catch (Exception e) {
                log.error("Error creating DataSource for database: {}", dbName, e);
            }
        }

        return dataSources;
    }

    /**
     * Create a new DataSource or retrieve it from cache.
     */
    private AgroalDataSource createOrGetDataSource(String dbName, boolean cacheEnabled) {
        if (cacheEnabled) {
            return dataSourceCache.computeIfAbsent(dbName, name -> {
                try {
                    log.debug("Creating cached DataSource for database: {}", name);
                    return datasourceFactory.create(name);
                } catch (Exception e) {
                    log.error("Error creating cached DataSource for database: {}", name, e);
                    return null;
                }
            });
        } else {
            try {
                log.debug("Creating temporary DataSource for database: {}", dbName);
                AgroalDataSource dataSource = datasourceFactory.create(dbName);
                temporaryDataSources.add(dataSource);
                return dataSource;
            } catch (Exception e) {
                log.error("Error creating temporary DataSource for database: {}", dbName, e);
                return null;
            }
        }
    }

    /**
     * Cleanup temporary DataSources created during the scrape.
     * This method should be called after all per-database collectors have finished.
     */
    public void cleanup() {
        if (!temporaryDataSources.isEmpty()) {
            log.debug("Cleaning up {} temporary DataSources", temporaryDataSources.size());
            temporaryDataSources.forEach(AgroalDataSource::close);
            temporaryDataSources.clear();
        }
    }


    /**
     * Filter databases based on the configured mode (ALL, INCLUDE, or EXCLUDE).
     */
    private Set<String> filterDatabases(Set<String> allDbs, CollectorsConfig.PerDB perDB) {
        return switch (perDB.mode()) {
            case ALL -> allDbs;
            case INCLUDE -> {
                Set<String> included = new HashSet<>(perDB.dbList());
                included.retainAll(allDbs);
                yield included;
            }
            case EXCLUDE -> {
                Set<String> excluded = new HashSet<>(allDbs);
                excluded.removeAll(perDB.dbList());
                yield excluded;
            }
            case NONE -> Collections.emptySet();
        };
    }

    /**
     * Fetch all databases from the PostgreSQL system catalog.
     */
    private Set<String> fetchAllDatabases(Connection baseConnection) {
        try (var stmt = baseConnection.createStatement();
             var rs = stmt.executeQuery(SQL)) {
            Set<String> databases = new HashSet<>();
            while (rs.next()) {
                String dbName = rs.getString("datname");
                databases.add(dbName);
            }
            log.debug("Found {} databases", databases.size());
            return databases;
        } catch (Exception e) {
            log.error("Error fetching database list", e);
            return Collections.emptySet();
        }
    }
}
