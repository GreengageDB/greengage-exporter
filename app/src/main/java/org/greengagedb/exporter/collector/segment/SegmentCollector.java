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
package org.greengagedb.exporter.collector.segment;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.AbstractEntityCollector;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;
import org.greengagedb.exporter.common.SegmentUtils;
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

import static org.greengagedb.exporter.common.Constants.SEGMENT_STATUS_DOWN;
import static org.greengagedb.exporter.common.Constants.SEGMENT_STATUS_UP;

/**
 * Collector for segment health and disk usage metrics.
 *
 * <p>Tracks status, role, mode, and disk usage for each segment in the Greengage cluster.
 * Essential for monitoring segment availability and disk capacity.
 *
 * <p>Uses composite key: hostname:dbid for unique segment identification.
 */
@Slf4j
@ApplicationScoped
public class SegmentCollector extends AbstractEntityCollector<String, SegmentStats> {
    private static final String SEGMENT_STATS_SQL = """
            select gsc.dbid,
                   gsc.content,
                   gsc.role,
                   gsc.preferred_role,
                   gsc.mode,
                   gsc.status,
                   gsc.port,
                   gsc.hostname,
                   gsc.address,
                   gsc.datadir
            from gp_segment_configuration gsc
            ORDER BY gsc.content, gsc.role""";

    @Override
    public String getName() {
        return "segment";
    }

    @Override
    public boolean isEnabled() {
        return config.segmentEnabled();
    }

    @Override
    protected Map<String, SegmentStats> collectEntities(Connection connection,
                                                        GreengageVersion version) throws SQLException {
        log.debug("Collecting segment metrics");

        Map<String, SegmentStats> segments = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SEGMENT_STATS_SQL)) {
            while (rs.next()) {
                SegmentStats stats = SegmentStats.builder()
                        .datadir(rs.getString("datadir"))
                        .address(rs.getString("address"))
                        .port(rs.getString("port"))
                        .content(rs.getString("content"))
                        .role(rs.getString("role"))
                        .preferredRole(rs.getString("preferred_role"))
                        .status(rs.getString("status"))
                        .mode(rs.getString("mode"))
                        .hostname(rs.getString("hostname"))
                        .dbid(rs.getString("dbid"))
                        .build();
                String key = stats.hostname() + ":" + stats.dbid();
                segments.put(key, stats);
            }
        }

        log.debug("Collected metrics for {} segments", segments.size());
        return segments;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<SegmentStats> segmentSupplier) {
        SegmentStats segmentStats = segmentSupplier.get();
        if (segmentStats == null) {
            log.warn("Segment supplier returned null for key: {}", key);
            return List.of();
        }

        List<Meter.Id> meterIds = new ArrayList<>();
        List<Tag> tags = List.of(
                Tag.of("dbid", segmentStats.dbid()),
                Tag.of("content", segmentStats.content()),
                Tag.of("hostname", segmentStats.hostname()),
                Tag.of("preferred_role", segmentStats.preferredRole()),
                Tag.of("role", segmentStats.role()),
                Tag.of("port", segmentStats.port()));

        // Segment status gauge
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segment_status"),
                                () -> {
                                    SegmentStats seg = segmentSupplier.get();
                                    return seg != null ? SegmentUtils.getStatusValue(seg.status()) : Double.NaN;
                                })
                        .description("UP(1) if the segment is running, DOWN(0) if the segment has failed or is unreachable")
                        .tags(tags)
                        .register(registry)
                        .getId()
        );

        // Segment role gauge
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segment_role"),
                                () -> {
                                    SegmentStats seg = segmentSupplier.get();
                                    return seg != null ? SegmentUtils.getRoleValue(seg.role()) : Double.NaN;
                                })
                        .description("The segment's current role, either primary(1) or mirror(2)")
                        .tags(tags)
                        .register(registry)
                        .getId()
        );

        // Segment mode gauge
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segment_mode"),
                                () -> {
                                    SegmentStats seg = segmentSupplier.get();
                                    return seg != null ? SegmentUtils.getModeValue(seg.mode()) : Double.NaN;
                                })
                        .description("The replication status for the segment. " +
                                "1.0 = Synchronized, 2.0 = Resyncing, 3.0 = Change Tracking, 4.0 = Not Syncing")
                        .tags(tags)
                        .register(registry)
                        .getId()
        );

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Total segments count
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segments_total"),
                        () -> (double) getEntityCount())
                .description("Total number of segments in the cluster")
                .register(registry);

        // Count of UP segments
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segments_up"),
                        () -> getEntities().values().stream()
                                .filter(s -> SEGMENT_STATUS_UP.equalsIgnoreCase(s.status()))
                                .count())
                .description("Number of segments in UP status")
                .register(registry);

        // Count of DOWN segments
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_CLUSTER, "segments_down"),
                        () -> getEntities().values().stream()
                                .filter(s -> SEGMENT_STATUS_DOWN.equalsIgnoreCase(s.status()))
                                .count())
                .description("Number of segments in DOWN status")
                .register(registry);
    }
}
