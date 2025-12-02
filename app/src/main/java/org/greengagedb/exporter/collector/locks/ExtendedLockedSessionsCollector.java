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
import org.greengagedb.exporter.common.ValueUtils;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Collector for extended locked sessions metrics grouped by lock type, mode, database, and segment.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Number of sessions waiting for locks by lock type, mode, database, and segment</li>
 *   <li>Maximum wait time for locks by lock type, mode, database, and segment</li>
 * </ul>
 *
 * <p>Critical for identifying lock contention and deadlock situations with detailed insights.
 */
@Slf4j
@ApplicationScoped
public class ExtendedLockedSessionsCollector extends AbstractEntityCollector<LocksKey, Long> {

    private static final String LOCKED_SESSIONS_SQL = """
            WITH waiting_locks AS (
                SELECT *
                FROM pg_locks
                WHERE granted = false
            ),
                 waiting_with_activity AS (
                     SELECT
                         wl.*,
                         db.datname,
                         (now() - a.query_start) AS wait_duration
                     FROM waiting_locks wl
                              LEFT JOIN pg_database db
                                        ON db.oid = wl.database
                              LEFT JOIN pg_stat_activity a
                                        ON a.sess_id = wl.mppsessionid
                 )
            SELECT
                'lock_waiting_queries'::text AS metric_name,
                datname                                AS database,
                locktype,
                mode,
                gp_segment_id::text                    AS gp_segment_id,
                count(*)::double precision             AS value
            FROM waiting_with_activity
            GROUP BY datname, locktype, mode, gp_segment_id
            UNION ALL
            SELECT
                'lock_wait_max_wait_seconds' AS metric_name,
                datname                                AS database,
                locktype,
                mode,
                gp_segment_id::text                    AS gp_segment_id,
                EXTRACT(EPOCH FROM MAX(wait_duration))::double precision AS value
            FROM waiting_with_activity
            GROUP BY datname, locktype, mode, gp_segment_id""";

    @Override
    public String getName() {
        return "extended_locked_sessions";
    }

    @Override
    public boolean isEnabled() {
        return config.extendedLocksEnabled();
    }

    @Override
    protected Map<LocksKey, Long> collectEntities(Connection connection,
                                                  GreengageVersion version) throws SQLException {
        log.debug("Collecting locked sessions");
        Map<LocksKey, Long> lockStats = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(LOCKED_SESSIONS_SQL)) {
            while (rs.next()) {
                String metricName = rs.getString("metric_name");
                String database = rs.getString("database");
                String lockType = rs.getString("locktype");
                String mode = rs.getString("mode");
                String gpSegmentId = rs.getString("gp_segment_id");
                double value = rs.getDouble("value");
                LocksKey key = new LocksKey(
                        LocksKey.MetricType.fromString(metricName),
                        ValueUtils.getOrUnknown(database),
                        ValueUtils.getOrUnknown(lockType),
                        ValueUtils.getOrUnknown(mode),
                        ValueUtils.getOrUnknown(gpSegmentId)
                );
                lockStats.put(key, (long) value);
            }
            log.debug("Collected locked sessions for {} lock types", lockStats.size());
            return lockStats;
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   LocksKey key,
                                                   Supplier<Long> valueSupplier) {
        return List.of(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, key.metric().getName()),
                                () -> {
                                    var value = valueSupplier.get();
                                    return value != null ? value : 0.0;
                                })
                        .description(key.metric().getDescription())
                        .tag("database", key.database())
                        .tag("lock_type", key.lockType())
                        .tag("mode", key.mode())
                        .tag("content", key.segment())
                        .register(registry)
                        .getId()
        );
    }


    @Override
    protected boolean shouldRemoveDeletedMetrics() {
        return true;
    }
}

