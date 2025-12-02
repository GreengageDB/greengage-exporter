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
package org.greengagedb.exporter.collector.vacuum;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.AbstractEntityCollector;
import org.greengagedb.exporter.collector.CollectorGroup;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.greengagedb.exporter.common.ValueUtils.getOrUnknown;

/**
 * Collector for table vacuum statistics.
 */
@Slf4j
@ApplicationScoped
public class TableVacuumStatisticsCollector extends AbstractEntityCollector<String, TableVacuumStats> {
    private static final String SQL = """
            WITH tab AS (SELECT current_database()                         AS datname,
                                n.nspname                                  AS nspname,
                                c.relname                                  AS relname,
                                s.n_live_tup                               AS n_live_tup,
                                s.n_dead_tup                               AS n_dead_tup,
                                s.vacuum_count                             AS vacuum_count,
                                s.autovacuum_count                         AS autovacuum_count,
                                s.last_vacuum,
                                s.last_autovacuum,
                                GREATEST(s.last_vacuum, s.last_autovacuum) AS last_any_vacuum
                         FROM pg_class c
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                  JOIN pg_stat_all_tables s ON s.relid = c.oid
                         WHERE c.relkind = 'r'
                           AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                           AND (s.n_live_tup + s.n_dead_tup) >= ?)
            SELECT datname,
                   nspname,
                   relname,
                   n_live_tup,
                   n_dead_tup,
                   CASE
                       WHEN n_live_tup + n_dead_tup > 0
                           THEN n_dead_tup::float / (n_live_tup + n_dead_tup)
                       ELSE 0
                       END   AS dead_tuple_ratio,
                   EXTRACT(
                           EPOCH FROM (now() - last_any_vacuum)
                   )::bigint AS seconds_since_last_vacuum,
                   EXTRACT(
                           EPOCH FROM (now() - COALESCE(last_autovacuum, last_vacuum))
                   )::bigint AS seconds_since_last_autovacuum,
                   vacuum_count,
                   autovacuum_count
            FROM tab""";

    @Override
    public String getName() {
        return "table_vacuum_statistics";
    }

    @Override
    public boolean isEnabled() {
        return config.tableVacuumStatisticsEnabled();
    }

    @Override
    protected Map<String, TableVacuumStats> collectEntities(Connection connection,
                                                            GreengageVersion version) throws SQLException {
        log.debug("Collecting vacuum statistics");
        Map<String, TableVacuumStats> tables = new HashMap<>();
        try (var stmt = connection.prepareStatement(SQL)) {
            stmt.setInt(1, config.tableVacuumStatisticsTupleThreshold());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String databaseName = getOrUnknown(rs.getString("datname"));
                    String schemaName = getOrUnknown(rs.getString("nspname"));
                    String tableName = rs.getString("relname");
                    double deadTupleRatio = rs.getDouble("dead_tuple_ratio");
                    long secondsSinceLastVacuum = rs.getLong("seconds_since_last_vacuum");
                    long secondsSinceLastAutovacuum = rs.getLong("seconds_since_last_autovacuum");
                    long vacuumCount = rs.getLong("vacuum_count");
                    long autovacuumCount = rs.getLong("autovacuum_count");
                    var key = databaseName + "." + schemaName + "." + tableName;
                    tables.put(key, new TableVacuumStats(
                            databaseName,
                            schemaName,
                            tableName,
                            deadTupleRatio,
                            secondsSinceLastVacuum,
                            secondsSinceLastAutovacuum,
                            vacuumCount,
                            autovacuumCount
                    ));
                }
            }
            log.debug("Collected vacuum statistics");
            return tables;
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<TableVacuumStats> vacuumSupplier) {
        var vacuumStats = vacuumSupplier.get();
        if (vacuumStats == null) {
            log.warn("Vacuum stats supplier returned null for key: {}", key);
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        String databaseName = vacuumStats.databaseName();
        String schemaName = vacuumStats.schemaName();
        String tableName = vacuumStats.tableName();

        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "table_dead_tuple_ratio"),
                                () -> getValueOrDefault(vacuumSupplier.get(), TableVacuumStats::deadTupleRatio))
                        .description("Ratio of dead tuples to total tuples for this table")
                        .tag("database", databaseName)
                        .tag("schema", schemaName)
                        .tag("table", tableName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "table_seconds_since_last_vacuum"),
                                () -> getValueOrDefault(vacuumSupplier.get(), TableVacuumStats::secondsSinceLastVacuum))
                        .description("Seconds since the last vacuum (manual or auto) for this table")
                        .tag("database", databaseName)
                        .tag("schema", schemaName)
                        .tag("table", tableName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "table_seconds_since_last_autovacuum"),
                                () -> getValueOrDefault(vacuumSupplier.get(), TableVacuumStats::secondsSinceLastAutovacuum))
                        .description("Seconds since the last autovacuum for this table")
                        .tag("database", databaseName)
                        .tag("schema", schemaName)
                        .tag("table", tableName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "table_vacuum_count"),
                                () -> getValueOrDefault(vacuumSupplier.get(), TableVacuumStats::vacuumCount))
                        .description("Total number of manual vacuums for this table")
                        .tag("database", databaseName)
                        .tag("schema", schemaName)
                        .tag("table", tableName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "table_autovacuum_count"),
                                () -> getValueOrDefault(vacuumSupplier.get(), TableVacuumStats::autovacuumCount))
                        .description("Total number of autovacuums for this table")
                        .tag("database", databaseName)
                        .tag("schema", schemaName)
                        .tag("table", tableName)
                        .register(registry)
                        .getId()
        );
        return meterIds;
    }

    @Override
    public CollectorGroup getGroup() {
        return CollectorGroup.PER_DB;
    }
}

