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
package org.greengagedb.exporter.collector.backup;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.AbstractEntityCollector;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;

/**
 * Collector for database size and related metrics
 */
@Slf4j
@ApplicationScoped
public class GpBackupHistoryCollector extends AbstractEntityCollector<GpBackupHistoryKey, GpBackupHistoryStats> {
    private static final String SUCCESS_STATUS = "success";
    private static final String FAILURE_STATUS = "failure";
    private static final Set<String> COMPLETED_STATUSES = Set.of(SUCCESS_STATUS, FAILURE_STATUS);
    private static final String STATS_SQL = """
            WITH last_backups AS (SELECT database_name,
                                         incremental,
                                         status,
                                         MAX(timestamp) AS start_ts,
                                         MAX(end_time)  AS end_ts
                                  FROM backups
                                  GROUP BY database_name, incremental, status),
                 counters AS (SELECT database_name,
                                     incremental,
                                     status,
                                     count(*) AS count
                              FROM backups
                              GROUP BY database_name, incremental, status)
            SELECT lb.database_name,
                   lb.incremental,
                   lower(lb.status)                AS status,
                   c.count,
                   strftime(
                           '%s',
                           substr(end_ts, 1, 4) || '-' ||
                           substr(end_ts, 5, 2) || '-' ||
                           substr(end_ts, 7, 2) || ' ' ||
                           substr(end_ts, 9, 2) || ':' ||
                           substr(end_ts, 11, 2) || ':' ||
                           substr(end_ts, 13, 2)
                   )
                       -
                   strftime(
                           '%s',
                           substr(start_ts, 1, 4) || '-' ||
                           substr(start_ts, 5, 2) || '-' ||
                           substr(start_ts, 7, 2) || ' ' ||
                           substr(start_ts, 9, 2) || ':' ||
                           substr(start_ts, 11, 2) || ':' ||
                           substr(start_ts, 13, 2)
                   )                               AS duration_seconds,
                   (strftime(
                            '%s', datetime()) - strftime(
                            '%s',
                            substr(end_ts, 1, 4) || '-' ||
                            substr(end_ts, 5, 2) || '-' ||
                            substr(end_ts, 7, 2) || ' ' ||
                            substr(end_ts, 9, 2) || ':' ||
                            substr(end_ts, 11, 2) || ':' ||
                            substr(end_ts, 13, 2)
                                                )) AS seconds_since_completion
            FROM last_backups lb
                     JOIN counters c
                          ON lb.database_name = c.database_name AND lb.incremental = c.incremental AND lb.status = c.status""";
    @Inject
    @DataSource("gpbackup_history")
    AgroalDataSource dataSource;

    private static String getBackupType(GpBackupHistoryKey key) {
        return key.incremental() == 0 ? "full" : "incremental";
    }

    private static String getBackupStatus(GpBackupHistoryKey key) {
        if (COMPLETED_STATUSES.contains(key.status())) {
            return key.status();
        }
        return "in_progress";
    }

    @Override
    public String getName() {
        return "gpbackup_history";
    }

    @Override
    public boolean isEnabled() {
        return config.gpBackupHistoryEnabled();
    }

    @Override
    protected Map<GpBackupHistoryKey, GpBackupHistoryStats> collectEntities(Connection connection,
                                                                            GreengageVersion version) throws SQLException {
        Map<GpBackupHistoryKey, GpBackupHistoryStats> entitiesMap = new HashMap<>();
        try (var conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(STATS_SQL)) {
            while (rs.next()) {
                String dbname = rs.getString("database_name");
                int incremental = rs.getInt("incremental");
                String status = rs.getString("status");
                int count = rs.getInt("count");
                double durationSeconds = rs.getDouble("duration_seconds");
                double secondsSinceCompletion = rs.getDouble("seconds_since_completion");
                GpBackupHistoryStats info = new GpBackupHistoryStats(
                        dbname,
                        incremental,
                        status,
                        count,
                        durationSeconds,
                        secondsSinceCompletion);
                entitiesMap.put(info.key(), info);
            }
        } catch (Exception e) {
            log.error("Error collecting gpbackup history stats: {}", e.getMessage());
            throw e;
        }
        return entitiesMap;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   GpBackupHistoryKey key,
                                                   Supplier<GpBackupHistoryStats> dbSupplier) {
        // Backup count
        List<Meter.Id> meterIds = new ArrayList<>();
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_GPBACKUP, "backup_count"),
                        () -> {
                            GpBackupHistoryStats stats = dbSupplier.get();
                            return stats != null ? stats.count() : Double.NaN;
                        })
                .description("Total number of backups for the database and incremental/status. Status can be success/failure/in_progress. Backup type can be full/incremental")
                .tags("database", key.databaseName(),
                        "type", getBackupType(key),
                        "status", getBackupStatus(key))
                .register(registry)
                .getId());
        // Duration of last backup in seconds. For successful and failed backups.
        if (Set.of(FAILURE_STATUS, SUCCESS_STATUS).contains(key.status())) {
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_GPBACKUP, "last_backup_duration_seconds"),
                            () -> {
                                GpBackupHistoryStats stats = dbSupplier.get();
                                return stats != null ? stats.durationSeconds() : Double.NaN;
                            })
                    .description("Duration of the last backup in seconds. Status can be success/failure. Backup type can be full/incremental")
                    .tags("database", key.databaseName(),
                            "incremental", String.valueOf(key.incremental()),
                            "status", key.status())
                    .register(registry)
                    .getId());
        }
        // Seconds since last backup completion. For successful backups only.
        if (SUCCESS_STATUS.equalsIgnoreCase(key.status())) {
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_GPBACKUP, "seconds_since_last_backup_completion"),
                            () -> {
                                GpBackupHistoryStats stats = dbSupplier.get();
                                return stats != null ? stats.secondsSinceCompletion() : Double.NaN;
                            })
                    .description("Seconds since the last backup completion")
                    .tags("database", key.databaseName(),
                            "incremental", getBackupType(key))
                    .register(registry)
                    .getId());
        }
        return meterIds;
    }

    @Override
    protected boolean shouldRemoveDeletedMetrics() {
        return true;
    }
}

