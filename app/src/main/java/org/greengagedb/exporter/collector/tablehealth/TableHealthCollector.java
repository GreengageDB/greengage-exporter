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
package org.greengagedb.exporter.collector.tablehealth;

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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.greengagedb.exporter.common.ValueUtils.getOrUnknown;

/**
 * Collector for table health metrics including bloat and data skew
 * Overhead on large clusters. Will be removed in future releases and replaced with <a href="https://docs.arenadata.io/en/blog/current/ADB/table-changes-track.html">table-changes-track</a>
 */
@Deprecated(since = "2026-02-01", forRemoval = true)
@Slf4j
@ApplicationScoped
public class TableHealthCollector extends AbstractEntityCollector<String, TableHealthStats> {
    private static final String TABLE_BLOAT_SQL = """
             SELECT
                current_database()  as datname,
                bdinspname        AS schemaname,
                bdirelname        AS relname,
                CASE
                    WHEN bdiexppages = 0 THEN 2
                    WHEN (bdirelpages::numeric / bdiexppages) > 10 THEN 2
                    WHEN (bdirelpages::numeric / bdiexppages) > 4 THEN 1
                    ELSE 0
                    END AS bloat_state
            FROM gp_toolkit.gp_bloat_diag""";
    private static final String TABLE_SKEW_SQL = """
            SELECT
                current_database()  as datname,
                skcnamespace AS schemaname,
                skcrelname  AS tablename,
                round(skccoeff, 1) AS skccoeff
            FROM gp_toolkit.gp_skew_coefficients
            WHERE skccoeff > 0.1
              AND skcnamespace NOT IN ('pg_catalog','information_schema','gp_toolkit')
            ORDER BY skccoeff DESC
            LIMIT 10
            """;

    private static String getKey(String databaseName, String schemaName, String tableName) {
        return databaseName + "." + schemaName + "." + tableName;
    }

    @Override
    public String getName() {
        return "table_health";
    }

    @Override
    public boolean isEnabled() {
        return config.tableHealthEnabled();
    }

    @Override
    protected Map<String, TableHealthStats> collectEntities(Connection connection, GreengageVersion version) throws SQLException {
        log.debug("Collecting table health metrics");
        var entitiesMap = collectTableBloat(connection);
        collectDataSkew(connection, entitiesMap);
        return entitiesMap;
    }

    /**
     * Collect table bloat from gp_toolkit.gp_bloat_diag
     */
    private Map<String, TableHealthStats> collectTableBloat(Connection connection) throws SQLException {
        Map<String, TableHealthStats> entitiesMap = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(TABLE_BLOAT_SQL)) {
            while (rs.next()) {
                String databaseName = getOrUnknown(rs.getString("datname"));
                String schemaName = getOrUnknown(rs.getString("schemaname"));
                String tableName = getOrUnknown(rs.getString("relname"));
                int bloatState = rs.getInt("bloat_state");
                String key = getKey(databaseName, schemaName, tableName);
                TableHealthStats info = entitiesMap.computeIfAbsent(key, k -> {
                    TableHealthStats newInfo = new TableHealthStats();
                    newInfo.setDatabaseName(databaseName);
                    newInfo.setSchemaName(schemaName);
                    newInfo.setTableName(tableName);
                    return newInfo;
                });
                info.setBloatState(bloatState);
            }
            log.debug("Collected bloat stats for {} tables", entitiesMap.size());
            return entitiesMap;
        } catch (SQLException e) {
            log.debug("Failed to collect table bloat statistics", e);
            throw e;
        }
    }

    /**
     * Collect data skew from gp_toolkit.gp_skew_coefficients
     */
    private void collectDataSkew(Connection connection, Map<String, TableHealthStats> entitiesMap) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(TABLE_SKEW_SQL)) {
            while (rs.next()) {
                String databaseName = getOrUnknown(rs.getString("datname"));
                String schemaName = getOrUnknown(rs.getString("schemaname"));
                String tableName = getOrUnknown(rs.getString("tablename"));
                double skewCoeff = rs.getDouble("skccoeff");
                String key = getKey(databaseName, schemaName, tableName);
                TableHealthStats info = entitiesMap.computeIfAbsent(key, k -> {
                    TableHealthStats newInfo = new TableHealthStats();
                    newInfo.setDatabaseName(databaseName);
                    newInfo.setSchemaName(schemaName);
                    newInfo.setTableName(tableName);
                    return newInfo;
                });

                info.setSkewFactor(skewCoeff);
            }

            log.debug("Collected skew stats");
        } catch (SQLException e) {
            log.debug("Failed to collect data skew statistics", e);
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<TableHealthStats> healthSupplier) {
        TableHealthStats healthStats = healthSupplier.get();
        if (healthStats == null) {
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        String schemaName = healthStats.getSchemaName();
        String tableName = healthStats.getTableName();

        // Bloat state:
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "table_bloat_state"),
                        () -> {
                            TableHealthStats h = healthSupplier.get();
                            return h != null ? h.getBloatState() : Double.NaN;
                        })
                .description("Table bloat state (0 = no bloat, 1 = moderate bloat, 2 = severe bloat)")
                .tag("database", healthStats.getDatabaseName())
                .tag("schema", schemaName)
                .tag("table", tableName)
                .register(registry).getId());

        // Skew factor
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "table_skew_factor"),
                        () -> {
                            TableHealthStats h = healthSupplier.get();
                            return h != null ? h.getSkewFactor() : Double.NaN;
                        })
                .description("Table data skew factor (1.0 = no skew, >1.5 = significant skew)")
                .tag("database", healthStats.getDatabaseName())
                .tag("schema", schemaName)
                .tag("table", tableName)
                .register(registry).getId());
        return meterIds;
    }

    @Override
    public CollectorGroup getGroup() {
        return CollectorGroup.PER_DB;
    }
}

