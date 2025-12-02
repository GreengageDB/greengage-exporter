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
 * Collector for database-wide vacuum statistics.
 */
@Slf4j
@ApplicationScoped
public class DbVacuumStatisticsCollector extends AbstractEntityCollector<String, DbVacuumStats> {
    private static final String SQL = """
            WITH tab AS (SELECT current_database()                         AS datname,
                                n.nspname,
                                c.relname,
                                s.n_live_tup,
                                s.n_dead_tup,
                                GREATEST(s.last_vacuum, s.last_autovacuum) AS last_any_vacuum
                         FROM pg_class c
                                  JOIN pg_namespace n ON n.oid = c.relnamespace
                                  JOIN pg_stat_all_tables s ON s.relid = c.oid
                         WHERE c.relkind = 'r'
                           AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                           AND (s.n_live_tup + s.n_dead_tup) >= ?)
            SELECT datname,
                   MAX(EXTRACT(EPOCH FROM (now() - last_any_vacuum)))::bigint AS max_seconds_since_last_vacuum,
                   AVG(
                           CASE
                               WHEN n_live_tup + n_dead_tup > 0
                                   THEN n_dead_tup::float / (n_live_tup + n_dead_tup)
                               ELSE 0
                               END
                   )                                                          AS avg_dead_tuple_ratio,
                   MAX(
                           CASE
                               WHEN n_live_tup + n_dead_tup > 0
                                   THEN n_dead_tup::float / (n_live_tup + n_dead_tup)
                               ELSE 0
                               END
                   )                                                          AS max_dead_tuple_ratio
            FROM tab
            GROUP BY datname""";

    @Override
    public String getName() {
        return "db_vacuum_statistics";
    }

    @Override
    public boolean isEnabled() {
        return config.dbVacuumStatisticsEnabled();
    }

    @Override
    protected Map<String, DbVacuumStats> collectEntities(Connection connection,
                                                         GreengageVersion version) throws SQLException {
        log.debug("Collecting vacuum statistics");
        Map<String, DbVacuumStats> databases = new HashMap<>();
        try (var stmt = connection.prepareStatement(SQL)) {
            stmt.setLong(1, config.tableVacuumStatisticsTupleThreshold());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String databaseName = getOrUnknown(rs.getString("datname"));
                    double avgDeadTupleRatio = rs.getDouble("avg_dead_tuple_ratio");
                    double maxDeadTupleRatio = rs.getDouble("max_dead_tuple_ratio");
                    long maxSecondsSinceLastVacuum = rs.getLong("max_seconds_since_last_vacuum");
                    databases.put(databaseName, new DbVacuumStats(
                            databaseName,
                            maxSecondsSinceLastVacuum,
                            avgDeadTupleRatio,
                            maxDeadTupleRatio
                    ));
                }
            }
            log.debug("Collected vacuum statistics");
            return databases;
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<DbVacuumStats> vacuumSupplier) {
        var vacuumStats = vacuumSupplier.get();
        if (vacuumStats == null) {
            log.warn("Vacuum stats supplier returned null for key: {}", key);
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        String databaseName = vacuumStats.databaseName();
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "db_max_seconds_since_last_vacuum"),
                                () -> getValueOrDefault(vacuumSupplier.get(), DbVacuumStats::maxSecondsSinceLastVacuum))
                        .description("Maximum seconds since last vacuum (manual or auto) across all tables in the database")
                        .tag("datname", databaseName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "db_avg_dead_tuple_ratio"),
                                () -> getValueOrDefault(vacuumSupplier.get(), DbVacuumStats::avgDeadTupleRatio))
                        .description("Average dead tuple ratio across all tables in the database")
                        .tag("datname", databaseName)
                        .register(registry)
                        .getId()
        );
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_DATABASE, "db_max_dead_tuple_ratio"),
                                () -> getValueOrDefault(vacuumSupplier.get(), DbVacuumStats::maxDeadTupleRatio))
                        .description("Maximum dead tuple ratio across all tables in the database")
                        .tag("datname", databaseName)
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

