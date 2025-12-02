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
package org.greengagedb.exporter.collector.cluster;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.AbstractAggregateCollector;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collector for cluster state metrics.
 *
 * <p>Collects cluster-wide information including:
 * <ul>
 *   <li>Cluster accessibility (can queries be distributed)</li>
 *   <li>Greengage version</li>
 *   <li>Master and standby hostnames</li>
 *   <li>Uptime since last restart</li>
 *   <li>Sync replication status</li>
 *   <li>Last configuration reload time</li>
 * </ul>
 *
 * <p>This collector uses {@link AbstractAggregateCollector} because it tracks
 * a single cluster state object, not per-entity metrics.
 */
@Slf4j
@ApplicationScoped
public class ClusterStateCollector extends AbstractAggregateCollector<ClusterState> {

    private static final String CHECK_STATE_SQL =
            "SELECT count(1) FROM gp_dist_random('gp_id')";

    private static final String CLUSTER_STATE_SQL = """
            WITH master AS (
                SELECT hostname FROM gp_segment_configuration
                WHERE content = -1 AND role = 'p'
            ),
            standby AS (
                SELECT hostname FROM gp_segment_configuration
                WHERE content = -1 AND role = 'm'
            ),
            uptime AS (
                SELECT extract(epoch FROM now() - pg_postmaster_start_time()) AS uptime_seconds
            ),
            sync AS (
                SELECT count(*) AS sync_replicas
                FROM pg_stat_replication
                WHERE state = 'streaming'
            ),
            conf_load AS (
                SELECT pg_conf_load_time() AS conf_load_time
            )
            SELECT
                (SELECT hostname FROM master) AS master_host,
                (SELECT hostname FROM standby) AS standby_host,
                (SELECT uptime_seconds FROM uptime) AS uptime_seconds,
                (SELECT sync_replicas FROM sync) AS sync_replicas,
                (SELECT conf_load_time FROM conf_load) AS conf_load_time,
                (SELECT current_setting('max_connections')::int) AS max_connections
            """;

    /**
     * Thread-safe storage for cluster state.
     * Metrics read from this reference via suppliers.
     */
    private final AtomicReference<ClusterState> stateRef = new AtomicReference<>(
            createDefaultState()
    );

    /**
     * Create default state for initialization.
     */
    private static ClusterState createDefaultState() {
        return ClusterState.builder()
                .accessible(false)
                .version("unknown")
                .master("unknown")
                .standby("")
                .uptime(0.0)
                .sync(0.0)
                .configLoadTime(0.0)
                .build();
    }

    @Override
    public String getName() {
        return "cluster_state";
    }

    @Override
    public boolean isEnabled() {
        return config.clusterStateEnabled();
    }

    @Override
    protected ClusterState collectData(Connection connection, GreengageVersion version) {
        log.debug("Collecting cluster state metrics");

        ClusterState.ClusterStateBuilder stateBuilder = ClusterState.builder()
                .accessible(false)
                .version(version.fullVersion())
                .master("unknown")
                .standby("")
                .uptime(0.0)
                .sync(0.0)
                .configLoadTime(0.0);

        // Check cluster accessibility (can we query segments)
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(CHECK_STATE_SQL)) {
            if (rs.next() && rs.getInt(1) > 0) {
                stateBuilder.accessible(true);
            }
        } catch (SQLException e) {
            log.debug("Cluster not accessible (might be single-node): {}", e.getMessage());
        }

        // Get detailed cluster state
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(CLUSTER_STATE_SQL)) {
            if (rs.next()) {
                String master = rs.getString("master_host");
                String standby = rs.getString("standby_host");

                stateBuilder
                        .master(master != null ? master : "unknown")
                        .standby(standby != null ? standby : "")
                        .uptime(rs.getDouble("uptime_seconds"))
                        .sync(rs.getDouble("sync_replicas"))
                        .maxConnections(rs.getInt("max_connections"));

                Timestamp ts = rs.getTimestamp("conf_load_time");
                if (ts != null) {
                    stateBuilder.configLoadTime(ts.getTime() / 1000.0);
                }
            }
        } catch (SQLException e) {
            log.debug("Failed to get detailed cluster info (might not be a cluster): {}",
                    e.getMessage());
        }

        return stateBuilder.build();
    }

    @Override
    protected void updateState(ClusterState data) {
        stateRef.set(data);
    }

    @Override
    protected void registerMetrics(MeterRegistry registry) {
        // Cluster accessibility gauge with dynamic labels
        // Note: Tags are computed dynamically via Iterable supplier for each scrape
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "state"),
                        () -> {
                            ClusterState state = stateRef.get();
                            return state.accessible() ? 1.0 : 0.0;
                        })
                .description("Whether the Greengage database cluster is accessible (can query segments)")
                .tags(() -> getStateTags().iterator())
                .register(registry);

        // Uptime gauge
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "uptime_seconds"),
                        () -> getValueOrDefault(stateRef.get(), ClusterState::uptime))
                .description("Duration that the Greengage database has been running since last restart")
                .register(registry);

        // Sync replication gauge
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "sync"),
                        () -> getValueOrDefault(stateRef.get(), ClusterState::sync))
                .description("Number of sync replicas streaming from master (0=no sync, 1=sync active)")
                .register(registry);

        // Config load time gauge
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "config_last_load_time_seconds"),
                        () -> getValueOrDefault(stateRef.get(), ClusterState::configLoadTime))
                .description("Unix timestamp of the last configuration reload")
                .register(registry);
        // Max connections gauge
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "max_connections"),
                        () -> getValueOrDefault(stateRef.get(), ClusterState::maxConnections))
                .description("Maximum number of allowed connections to the Greengage database")
                .register(registry);
    }

    /**
     * Build dynamic tags based on current cluster state.
     *
     * <p>Tags include version, master hostname, and standby hostname.
     * This allows filtering/grouping by these dimensions in Prometheus/Grafana.
     */
    private Tags getStateTags() {
        ClusterState state = stateRef.get();
        if (state == null) {
            return Tags.of(
                    Tag.of("version", "unknown"),
                    Tag.of("master", "unknown"),
                    Tag.of("standby", "")
            );
        }

        return Tags.of(
                Tag.of("version", state.version() != null ? state.version() : "unknown"),
                Tag.of("master", state.master() != null ? state.master() : "unknown"),
                Tag.of("standby", state.standby() != null ? state.standby() : "")
        );
    }

    @Override
    protected boolean shouldFailOnError() {
        // Continue collecting other metrics even if cluster state fails
        return false;
    }
}

