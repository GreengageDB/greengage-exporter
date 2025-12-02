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
package org.greengagedb.exporter.collector.host;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class DiskHostCollector extends AbstractEntityCollector<String, DiskValueStats> {
    private static final String SQL = """
            select distinct
                  gdu.dfhostname,
                  gdu.dftotal_kb,
                  gdu.dfused_kb,
                  gdu.dfavail_kb,
                  gdu.dfpercent
              from ggexporter.gp_segment_disk_usage gdu
              ORDER BY gdu.dfhostname""";
    private final AtomicReference<SkewStats> totalSkewStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));
    private final AtomicReference<SkewStats> usedSkewStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));
    private final AtomicReference<SkewStats> availSkewStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));
    private final AtomicReference<SkewStats> availPercentSkewStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));

    @Override
    public String getName() {
        return "disk_per_host";
    }

    @Override
    public boolean isEnabled() {
        return config.diskPerHostEnabled();
    }

    @Override
    protected Map<String, DiskValueStats> collectEntities(Connection connection,
                                                          GreengageVersion version) throws SQLException {
        Map<String, DiskValueStats> entitiesMap = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {
            while (rs.next()) {
                var stats = new DiskValueStats(
                        rs.getString("dfhostname"),
                        rs.getDouble("dftotal_kb"),
                        rs.getDouble("dfused_kb"),
                        rs.getDouble("dfavail_kb"),
                        rs.getDouble("dfpercent")
                );
                entitiesMap.put(stats.hostname(), stats);
            }
            totalSkewStats.set(SkewStats.of(
                    entitiesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.total()))
                            .collect(Collectors.toSet())
            ));
            usedSkewStats.set(SkewStats.of(
                    entitiesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.used()))
                            .collect(Collectors.toSet())
            ));
            availSkewStats.set(SkewStats.of(
                    entitiesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.available()))
                            .collect(Collectors.toSet())
            ));
            availPercentSkewStats.set(SkewStats.of(
                    entitiesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.usedPercent()))
                            .collect(Collectors.toSet())
            ));
        }
        return entitiesMap;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<DiskValueStats> valueSupplier) {
        var valueStats = valueSupplier.get();
        if (valueStats == null) {
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "disk_total_kb"),
                        () -> {
                            var stats = valueSupplier.get();
                            return stats != null ? stats.total() : Double.NaN;
                        })
                .description("Disk total KB per host")
                .tag("hostname", valueStats.hostname())
                .register(registry).getId());
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "disk_used_kb"),
                        () -> {
                            var stats = valueSupplier.get();
                            return stats != null ? stats.used() : Double.NaN;
                        })
                .description("Disk used KB per host")
                .tag("hostname", valueStats.hostname())
                .register(registry).getId());
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "disk_available_kb"),
                        () -> {
                            var stats = valueSupplier.get();
                            return stats != null ? stats.available() : Double.NaN;
                        })
                .description("Disk available KB per host")
                .tag("hostname", valueStats.hostname())
                .register(registry).getId());
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "disk_usage_percent"),
                        () -> {
                            var stats = valueSupplier.get();
                            return stats != null ? stats.usedPercent() : Double.NaN;
                        })
                .description("Disk usage percent per host")
                .tag("hostname", valueStats.hostname())
                .register(registry).getId());

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        registerSkewMetric(registry,
                "disk_total_kb",
                "Maximum Disk total KB across all hosts",
                totalSkewStats,
                "Average Disk total KB across all hosts",
                "Disk total KB skew ratio across all hosts");
        registerSkewMetric(registry,
                "disk_used_kb",
                "Maximum Disk used KB across all hosts",
                usedSkewStats,
                "Average Disk used KB across all hosts",
                "Disk used KB skew ratio across all hosts");
        registerSkewMetric(registry,
                "disk_available_kb",
                "Maximum Disk available KB across all hosts",
                availSkewStats,
                "Average Disk available KB across all hosts",
                "Disk available KB skew ratio across all hosts");
        registerSkewMetric(registry,
                "disk_usage_percent",
                "Maximum Disk usage percent across all hosts",
                availPercentSkewStats,
                "Average Disk usage percent across all hosts",
                "Disk usage percent skew ratio across all hosts");
    }

    private void registerSkewMetric(MeterRegistry registry,
                                    String metric,
                                    String maxDesc,
                                    AtomicReference<SkewStats> skewStats,
                                    String avgDesc,
                                    String minDesc) {
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "max_" + metric),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.max() : Double.NaN;
                        })
                .description(maxDesc)
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "avg_" + metric),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.avg() : Double.NaN;
                        })
                .description(avgDesc)
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, metric + "_skew_ratio"),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.skewRatio() : Double.NaN;
                        })
                .description(minDesc)
                .register(registry);
    }

    @Override
    protected boolean shouldFailOnError() {
        return false;
    }
}
