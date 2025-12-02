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
package org.greengagedb.exporter.collector.locks;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Collector for locked sessions metrics grouped by lock groupBy.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Number of sessions waiting for locks by lock groupBy</li>
 *   <li>Total count of queries waiting for any lock</li>
 * </ul>
 *
 * <p>Critical for identifying lock contention and deadlock situations.
 */
@Slf4j
@ApplicationScoped
public class LockedSessionsCollector extends AbstractEntityCollector<String, LockTypeStats> {

    public static final String QUERY_WAITING_COUNT_SQL =
            "SELECT count(*) AS waiting_count " +
                    "FROM pg_locks " +
                    "WHERE NOT granted";

    private static final String LOCKED_SESSIONS_SQL_V6 = """
            SELECT
                l.locktype       AS lock_type,
                COUNT(*)         AS locked_sessions_count
            FROM pg_locks l
                     JOIN pg_stat_activity a ON a.pid = l.pid
            WHERE a.waiting
              AND NOT l.granted
            GROUP BY l.locktype
            ORDER BY lock_type""";

    private static final String LOCKED_SESSIONS_SQL_V7 = """
            SELECT
                l.locktype       AS lock_type,
                COUNT(*)         AS locked_sessions_count
            FROM pg_locks l
                     JOIN pg_stat_activity a ON a.pid = l.pid
            WHERE a.wait_event_type = 'Lock'
            GROUP BY l.locktype
            ORDER BY lock_type""";

    private final AtomicInteger queryWaitingCount = new AtomicInteger(0);

    @Override
    public String getName() {
        return "locked_sessions";
    }

    @Override
    public boolean isEnabled() {
        return config.locksEnabled();
    }

    @Override
    protected Map<String, LockTypeStats> collectEntities(Connection connection,
                                                         GreengageVersion version) throws SQLException {
        log.debug("Collecting locked sessions");

        // First, collect waiting queries count
        collectWaitingQueries(connection);

        // Then collect locked sessions by lock groupBy
        Map<String, LockTypeStats> lockStats = new HashMap<>();
        String sql = version.isAtLeastVersion7() ? LOCKED_SESSIONS_SQL_V7 : LOCKED_SESSIONS_SQL_V6;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String lockType = rs.getString("lock_type");
                int count = rs.getInt("locked_sessions_count");

                if (lockType == null || lockType.isEmpty()) {
                    lockType = "unknown";
                }

                LockTypeStats info = new LockTypeStats(lockType, count);
                lockStats.put(lockType, info);
            }

            log.debug("Collected locked sessions for {} lock types", lockStats.size());
            return lockStats;
        }
    }

    /**
     * Collect waiting queries count from pg_locks.
     */
    private void collectWaitingQueries(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(QUERY_WAITING_COUNT_SQL)) {

            if (rs.next()) {
                int count = rs.getInt("waiting_count");
                queryWaitingCount.set(count);
                log.debug("Collected waiting queries count: {}", count);
            }
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String lockType,
                                                   Supplier<LockTypeStats> statsSupplier) {
        return List.of(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "locked_sessions_count"),
                                () -> {
                                    LockTypeStats stats = statsSupplier.get();
                                    return stats != null ? (double) stats.count() : 0.0;
                                })
                        .description("Number of locked sessions by lock groupBy")
                        .tag("lock_type", lockType)
                        .register(registry)
                        .getId()
        );
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Total query waiting count (all lock types)
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "query_waiting_count"),
                        () -> (double) queryWaitingCount.get())
                .description("Total number of queries waiting for locks (all types)")
                .register(registry);

        // Total locked sessions across all lock types
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "locked_sessions_total"),
                        () -> getEntities().values().stream()
                                .mapToInt(LockTypeStats::count)
                                .sum())
                .description("Total number of locked sessions across all lock types")
                .register(registry);
    }

    @Override
    protected boolean shouldRemoveDeletedMetrics() {
        // Lock types are stable (relation, tuple, etc.)
        return false;
    }
}

