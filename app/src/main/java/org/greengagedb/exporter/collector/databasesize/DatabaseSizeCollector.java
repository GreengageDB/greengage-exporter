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
package org.greengagedb.exporter.collector.databasesize;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.AbstractEntityCollector;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Collector for database sizes across all databases in the Greengage cluster.
 *
 * <p>Tracks the size of each database in both megabytes and bytes, allowing
 * monitoring of database growth over time and capacity planning.
 *
 * <p>Uses {@link AbstractEntityCollector} with database name as the entity key.
 */
@Slf4j
@ApplicationScoped
public class DatabaseSizeCollector extends AbstractEntityCollector<String, DatabaseStats> {
    private static final String SQL =
            "SELECT sodddatname AS database_name, " +
                    "       sodddatsize/(1024*1024) AS database_size_mb " +
                    "FROM gp_toolkit.gp_size_of_database";

    @Override
    public String getName() {
        return "database_size";
    }

    @Override
    public boolean isEnabled() {
        return config.databaseSizeEnabled();
    }

    @Override
    protected Map<String, DatabaseStats> collectEntities(Connection connection,
                                                         GreengageVersion version) throws SQLException {
        log.debug("Collecting database size metrics");

        Map<String, DatabaseStats> databases = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {
            while (rs.next()) {
                String dbname = rs.getString("database_name");
                double sizeMb = rs.getDouble("database_size_mb");
                DatabaseStats stats = new DatabaseStats(
                        dbname,
                        sizeMb
                );
                databases.put(dbname, stats);
            }
        }

        log.debug("Collected size info for {} databases", databases.size());
        return databases;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String dbname,
                                                   Supplier<DatabaseStats> dbSupplier) {
        List<Meter.Id> meterIds = new ArrayList<>();
        // Database size in MB
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "database_name_mb_size"),
                                () -> getValueOrNaN(dbSupplier.get(), DatabaseStats::sizeMb))
                        .description("Total MB size of each database name in the file system")
                        .tag("dbname", dbname)
                        .register(registry)
                        .getId()
        );

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Total size across all databases in MB
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "total_database_size_mb"),
                        () -> getEntities().values().stream()
                                .mapToDouble(DatabaseStats::sizeMb)
                                .sum())
                .description("Total size of all databases in megabytes")
                .register(registry);

        // Database count
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "database_count"),
                        () -> (double) getEntityCount())
                .description("Number of databases in the cluster")
                .register(registry);
    }

    @Override
    protected boolean shouldRemoveDeletedMetrics() {
        // Databases are stable, rarely deleted
        return false;
    }
}

