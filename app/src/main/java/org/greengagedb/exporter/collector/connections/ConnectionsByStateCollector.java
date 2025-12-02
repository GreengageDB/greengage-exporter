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
package org.greengagedb.exporter.collector.connections;

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
 * Collector for connection statistics grouped by state.
 *
 * <p>Tracks connections in different states:
 * <ul>
 *   <li>active - Executing queries</li>
 *   <li>waiting - Active but waiting for locks or other resources</li>
 *   <li>idle - Not executing any queries</li>
 * </ul>
 *
 * <p>Helps identify connection pool health and detect connection leaks.
 */
@Slf4j
@ApplicationScoped
public class ConnectionsByStateCollector extends AbstractEntityCollector<String, ConnectionStats> {
    private static final String CONNECTIONS_BY_STATE_V6 = """
            SELECT
              a.state,
              COUNT(*) AS count
            FROM pg_stat_activity a
            WHERE a.pid <> pg_backend_pid()
            GROUP BY 1
            ORDER BY count DESC""";

    private static final String CONNECTIONS_BY_STATE_V7 = """
            SELECT
              state,
              COUNT(*) AS count
            FROM pg_stat_activity
            WHERE pid <> pg_backend_pid()
              AND backend_type = 'client backend'
            GROUP BY 1
            ORDER BY count DESC""";

    @Override
    public String getName() {
        return "connections_by_state";
    }

    @Override
    public boolean isEnabled() {
        return config.connectionsEnabled();
    }

    @Override
    protected Map<String, ConnectionStats> collectEntities(Connection connection,
                                                           GreengageVersion version) throws SQLException {
        log.debug("Collecting connections by state");

        Map<String, ConnectionStats> connections = new HashMap<>();
        String sql = version.isAtLeastVersion7() ? CONNECTIONS_BY_STATE_V7 : CONNECTIONS_BY_STATE_V6;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String state = rs.getString("state");
                int count = rs.getInt("count");
                state = ValueUtils.getOrUnknown(state);
                ConnectionStats stats = new ConnectionStats(state, count);
                connections.put(state, stats);
            }

            log.debug("Collected connection info for {} states", connections.size());
            return connections;
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String state,
                                                   Supplier<ConnectionStats> statsSupplier) {
        ConnectionStats stats = statsSupplier.get();
        if (stats == null) {
            log.warn("Connection stats supplier returned null for state: {}", state);
            return List.of();
        }

        return List.of(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "connections_total"),
                                () -> {
                                    ConnectionStats s = statsSupplier.get();
                                    return s != null ? (double) s.count() : 0.0;
                                })
                        .description("Total connections by state (active, idle, waiting)")
                        .tag("state", stats.state())
                        .register(registry)
                        .getId()
        );
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Total connections across all states
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "connections_all_states_total"),
                        () -> getEntities().values().stream()
                                .mapToInt(ConnectionStats::count)
                                .sum())
                .description("Total number of connections across all states")
                .register(registry);
    }
}
