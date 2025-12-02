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
package org.greengagedb.exporter.collector.replication;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Collector for replication monitoring metrics.
 * Monitors master-standby replication health, lag, and WAL position.
 *
 * <p>
 * The state tag has been removed from per-standby metrics to avoid creating
 * duplicate
 * metrics when standby state changes. State is now exposed as a separate
 * numeric metric.
 */
@Slf4j
@ApplicationScoped
public class ReplicationMonitorCollector extends AbstractEntityCollector<InstanceKey, ReplicationStats> {
    private static final String REPLICATION_STATS_SQL_V6 = """
            WITH master AS (SELECT now()                                                                                   AS time,
                                   -1                                                                                      AS content,
                                   application_name,
                                   state,
                                   sync_state,
                                   sent_location::text                                                                     AS sent_lsn,
                                   write_location::text                                                                    AS write_lsn,
                                   flush_location::text                                                                    AS flush_lsn,
                                   replay_location::text                                                                   AS replay_lsn,
                                   GREATEST(COALESCE(pg_xlog_location_diff(sent_location, write_location), 0),
                                            0)::bigint                                                                     AS write_lag_bytes,
                                   GREATEST(COALESCE(pg_xlog_location_diff(sent_location, flush_location), 0),
                                            0)::bigint                                                                     AS flush_lag_bytes,
                                   GREATEST(COALESCE(pg_xlog_location_diff(sent_location, replay_location), 0),
                                            0)::bigint                                                                     AS replay_lag_bytes
                            FROM pg_stat_replication
                            WHERE state IN ('streaming', 'catchup')),
                 segments AS (SELECT now()                                                                                   AS time,
                                     gp_execution_segment()                                                                  AS content,
                                     application_name,
                                     state,
                                     sync_state,
                                     sent_location::text                                                                     AS sent_lsn,
                                     write_location::text                                                                    AS write_lsn,
                                     flush_location::text                                                                    AS flush_lsn,
                                     replay_location::text                                                                   AS replay_lsn,
                                     GREATEST(COALESCE(pg_xlog_location_diff(sent_location, write_location), 0),
                                              0)::bigint                                                                     AS write_lag_bytes,
                                     GREATEST(COALESCE(pg_xlog_location_diff(sent_location, flush_location), 0),
                                              0)::bigint                                                                     AS flush_lag_bytes,
                                     GREATEST(COALESCE(pg_xlog_location_diff(sent_location, replay_location), 0),
                                              0)::bigint                                                                     AS replay_lag_bytes
                              FROM gp_dist_random('pg_stat_replication')
                              WHERE state IN ('streaming', 'catchup'))
            SELECT m.*, g.hostname
            FROM master m
                     JOIN gp_segment_configuration g
                          ON g.content = m.content
                              AND g.role = 'p'
            UNION ALL
            SELECT s.*, g.hostname
            FROM segments s
                     JOIN gp_segment_configuration g
                          ON g.content = s.content
                              AND g.role = 'p'
            ORDER BY content, application_name""";
    private static final String REPLICATION_STATS_SQL_V7 = """
            WITH master AS (SELECT now()                                                                   AS time,
                                   -1                                                                      AS content,
                                   application_name,
                                   state,
                                   sync_state,
                                   sent_lsn::text                                                          AS sent_lsn,
                                   write_lsn::text                                                         AS write_lsn,
                                   flush_lsn::text                                                         AS flush_lsn,
                                   replay_lsn::text                                                        AS replay_lsn,
                                   GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, write_lsn), 0), 0)::bigint  AS write_lag_bytes,
                                   GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, flush_lsn), 0), 0)::bigint  AS flush_lag_bytes,
                                   GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, replay_lsn), 0), 0)::bigint AS replay_lag_bytes
                            FROM pg_stat_replication
                            WHERE state IN ('streaming', 'catchup')),
                 segments AS (SELECT now()                                                                   AS time,
                                     gp_execution_segment()                                                  AS content,
                                     application_name,
                                     state,
                                     sync_state,
                                     sent_lsn::text                                                          AS sent_lsn,
                                     write_lsn::text                                                         AS write_lsn,
                                     flush_lsn::text                                                         AS flush_lsn,
                                     replay_lsn::text                                                        AS replay_lsn,
                                     GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, write_lsn), 0), 0)::bigint  AS write_lag_bytes,
                                     GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, flush_lsn), 0), 0)::bigint  AS flush_lag_bytes,
                                     GREATEST(COALESCE(pg_wal_lsn_diff(sent_lsn, replay_lsn), 0), 0)::bigint AS replay_lag_bytes
                              FROM gp_dist_random('pg_stat_replication')
                              WHERE state IN ('streaming', 'catchup'))
            SELECT m.*, g.hostname
            FROM master m
                     JOIN gp_segment_configuration g
                          ON g.content = m.content
                              AND g.role = 'p'
            UNION ALL
            SELECT s.*, g.hostname
            FROM segments s
                     JOIN gp_segment_configuration g
                          ON g.content = s.content
                              AND g.role = 'p'
            ORDER BY content, application_name""";
    private final AtomicReference<Double> maxLagBytes = new AtomicReference<>(0.0);

    @Override
    public String getName() {
        return "replication_monitor";
    }

    @Override
    public boolean isEnabled() {
        return config.replicationMonitorEnabled();
    }

    @Override
    protected Map<InstanceKey, ReplicationStats> collectEntities(Connection connection,
                                                                 GreengageVersion version) throws SQLException {
        log.debug("Collecting replication monitoring metrics");
        Map<InstanceKey, ReplicationStats> entitiesMap = new HashMap<>();
        String sql = version.isAtLeastVersion7() ? REPLICATION_STATS_SQL_V7 : REPLICATION_STATS_SQL_V6;

        double currentMaxLagBytes = 0.0;
        double currentMaxLagSeconds = 0.0;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String applicationName = rs.getString("application_name");
                String state = rs.getString("state");
                String syncState = rs.getString("sync_state");

                // Lag in bytes
                long writeLag = rs.getLong("write_lag_bytes");
                long flushLag = rs.getLong("flush_lag_bytes");
                long replayLag = rs.getLong("replay_lag_bytes");

                if (applicationName == null || applicationName.isEmpty()) {
                    applicationName = "unknown";
                }

                ReplicationStats info = new ReplicationStats(
                        applicationName,
                        state,
                        syncState,
                        writeLag,
                        flushLag,
                        replayLag
                );
                entitiesMap.put(new InstanceKey(rs.getInt("content"), rs.getString("hostname")), info);
                // Track maximum lag for aggregate metrics
                currentMaxLagBytes = Math.max(currentMaxLagBytes, replayLag);
            }

            // Update aggregate values
            maxLagBytes.set(currentMaxLagBytes);

            log.debug("Collected replication stats for {} standbys, max lag: {} bytes, {} seconds",
                    entitiesMap.size(), currentMaxLagBytes, currentMaxLagSeconds);
        } catch (SQLException e) {
            log.debug("Failed to collect replication statistics (might not be master or no standby)", e);
        }

        return entitiesMap;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   InstanceKey key,
                                                   Supplier<ReplicationStats> statsSupplier) {
        // Replication lag in bytes (replay lag)
        List<Meter.Id> meterIds = new ArrayList<>();
        var applicationName = statsSupplier.get().applicationName();
        Set<Tag> tags = new HashSet<>(key.toTags());
        tags.add(Tag.of("application_name", applicationName));
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_lag_bytes"),
                        () -> {
                            ReplicationStats stats = statsSupplier.get();
                            return stats != null ? (double) stats.replayLagBytes() : Double.NaN;
                        })
                .description("Replication lag in bytes (replay lag)")
                .tags(tags)
                .register(registry).getId());

        // Replication state as numeric (separate from lag metrics to avoid duplicate metrics on state change)
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_state"),
                        () -> {
                            ReplicationStats stats = statsSupplier.get();
                            return stats != null ? stats.getStateNumeric() : Double.NaN;
                        })
                .description("Replication state: 1=streaming, 2=catchup, 3=backup, 0=unknown")
                .tags(tags)
                .register(registry).getId());
        // Sync state as numeric
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_sync_state"),
                        () -> {
                            ReplicationStats stats = statsSupplier.get();
                            return stats != null ? stats.getSyncStateNumeric() : Double.NaN;
                        })
                .description("Replication sync state: 2=sync, 1=async, 0.5=potential, 0=unknown")
                .tags(tags)
                .register(registry).getId());
        // Write lag (additional detail)
        meterIds.add(Gauge
                .builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_write_lag_bytes"),
                        () -> {
                            ReplicationStats stats = statsSupplier.get();
                            return stats != null ? (double) stats.writeLagBytes() : Double.NaN;
                        })
                .description("Replication write lag in bytes")
                .tags(tags)
                .register(registry).getId());

        // Flush lag (additional detail)
        meterIds.add(Gauge
                .builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_flush_lag_bytes"),
                        () -> {
                            ReplicationStats stats = statsSupplier.get();
                            return stats != null ? (double) stats.flushLagBytes() : Double.NaN;
                        })
                .description("Replication flush lag in bytes")
                .tags(tags)
                .register(registry).getId());
        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Maximum replication lag in bytes across all standbys
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "replication_max_lag_bytes"),
                        maxLagBytes::get)
                .description("Maximum replication lag in bytes across all segments")
                .register(registry);
    }
}