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

/**
 * Collector for memory usage per host and resource group.
 *
 * <p>Tracks memory usage across hosts, allowing detection of memory skew
 * and identifying memory-intensive resource groups.
 */
@Slf4j
@ApplicationScoped
public class RsgHostCollector extends AbstractEntityCollector<RsgKey, RsgHostStats> {
    private static final String SQL_V6 = """
            SELECT
                r.rsgname,
                h.hostname,
                g.num_running,
                g.num_queueing,
                cfg.cpu_rate_limit::int         AS cpu_rate_limit,
                COALESCE(ROUND(h.cpu)::int, 0)  AS cpu_usage,
                cfg.memory_limit::int           AS memory_limit,
                COALESCE(h.memory_used::int, 0) AS memory_usage
            FROM gp_toolkit.gp_resgroup_status g
                     JOIN pg_resgroup r
                          ON g.groupid = r.oid
                     LEFT JOIN gp_toolkit.gp_resgroup_status_per_host h
                               ON h.groupid = g.groupid
                     LEFT JOIN gp_toolkit.gp_resgroup_config cfg
                               ON cfg.groupid = g.groupid
            where h.hostname in (select c.hostname
                                 from gp_segment_configuration c
                                 where c.role = 'p'
                                   and c.content >= 0)
            ORDER BY r.rsgname, h.hostname""";

    private static final String SQL_V7 = """
            SELECT r.rsgname,
                   h.hostname,
                   g.num_running,
                   g.num_queueing,
                   cfg.cpu_max_percent::int             AS cpu_rate_limit,
                   COALESCE(ROUND(h.cpu_usage)::int, 0) AS cpu_usage,
                   cfg.memory_limit::int                AS memory_limit,
                   COALESCE(h.memory_usage::int, 0)     AS memory_usage
            FROM gp_toolkit.gp_resgroup_status g
                     JOIN pg_resgroup r ON g.groupid = r.oid
                     LEFT JOIN gp_toolkit.gp_resgroup_status_per_host h on h.groupid = g.groupid
                     LEFT JOIN gp_toolkit.gp_resgroup_config cfg ON cfg.groupid = g.groupid
            where h.hostname in (select c.hostname
                                 from gp_segment_configuration c
                                 where c.role = 'p'
                                   and c.content >= 0)
            ORDER BY r.rsgname, h.hostname""";
    private final AtomicReference<SkewStats> cpuStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));
    private final AtomicReference<SkewStats> memStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));

    @Override
    public String getName() {
        return "rsg_per_host";
    }

    @Override
    public boolean isEnabled() {
        return config.rsgPerHostEnabled();
    }

    @Override
    protected Map<RsgKey, RsgHostStats> collectEntities(Connection connection,
                                                        GreengageVersion version) throws SQLException {
        Map<RsgKey, RsgHostStats.HostValues> hostValuesMap = new HashMap<>();
        Map<RsgKey, RsgHostStats.RsgValues> rsgValuesMap = new HashMap<>();
        var sql = version.isAtLeastVersion7() ? SQL_V7 : SQL_V6;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                var stats = new RsgHostValues(
                        rs.getString("rsgname"),
                        rs.getString("hostname"),
                        rs.getInt("num_running"),
                        rs.getInt("num_queueing"),
                        rs.getInt("cpu_rate_limit"),
                        rs.getInt("cpu_usage"),
                        rs.getInt("memory_limit"),
                        rs.getInt("memory_usage")
                );
                var hostKey = new RsgKey(RsgKey.GroupType.HOST, stats.hostname() + ":" + stats.resourceGroupName());
                hostValuesMap.put(hostKey, RsgHostStats.HostValues.of(stats));
                var rsgKey = new RsgKey(RsgKey.GroupType.RESOURCE_GROUP, stats.resourceGroupName());
                if (!rsgValuesMap.containsKey(rsgKey)) {
                    rsgValuesMap.put(rsgKey, RsgHostStats.RsgValues.of(stats));
                }
            }
            memStats.set(SkewStats.of(
                    hostValuesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.memoryUsage()))
                            .toList()
            ));
            cpuStats.set(SkewStats.of(
                    hostValuesMap.values().stream()
                            .map(it -> HostValueStats.of(it.hostname(), it.cpuUsage()))
                            .toList()
            ));
        }
        Map<RsgKey, RsgHostStats> entitiesMap = new HashMap<>();
        hostValuesMap.forEach((key, value) -> entitiesMap.put(key, new RsgHostStats(value)));
        rsgValuesMap.forEach((key, value) -> entitiesMap.put(key, new RsgHostStats(value)));
        return entitiesMap;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   RsgKey key,
                                                   Supplier<RsgHostStats> valueSupplier) {
        var valueStats = valueSupplier.get();
        if (valueStats == null) {
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        if (key.groupBy() == RsgKey.GroupType.HOST) {
            RsgHostStats.HostValues hostValues = valueStats.hostValues();
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "mem_usage_mb"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.hostValues().memoryUsage();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("Mem usage per host and resource group")
                    .tag("resourceGroupName", hostValues.resourceGroupName())
                    .tag("hostname", hostValues.hostname())
                    .tag("limit", hostValues.memoryLimit() > 0 ? String.valueOf(hostValues.memoryLimit()) : "unlimited")
                    .register(registry).getId());
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "cpu_usage_percentage"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.hostValues().cpuUsage();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("CPU usage percentage per host and resource group")
                    .tag("resourceGroupName", hostValues.resourceGroupName())
                    .tag("limit", hostValues.cpuRateLimit() > 0 ? String.valueOf(hostValues.cpuRateLimit()) : "unlimited")
                    .tag("hostname", hostValues.hostname())
                    .register(registry).getId());
        } else if (key.groupBy() == RsgKey.GroupType.RESOURCE_GROUP) {
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "num_running_sessions"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.rsgValues().numRunning();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("Number of running sessions per resource group")
                    .tag("resourceGroupName", valueStats.rsgValues().resourceGroupName())
                    .register(registry).getId());
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "num_queueing_sessions"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.rsgValues().numQueueing();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("Number of queueing sessions per resource group")
                    .tag("resourceGroupName", valueStats.rsgValues().resourceGroupName())
                    .register(registry).getId());
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "mem_limit_mb"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.rsgValues().memoryLimit();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("Mem limit per resource group")
                    .tag("resourceGroupName", valueStats.rsgValues().resourceGroupName())
                    .register(registry).getId());
            meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "cpu_rate_limit_percentage"),
                            () -> {
                                var stats = valueSupplier.get();
                                if (stats != null) {
                                    return stats.rsgValues().cpuRateLimit();
                                } else {
                                    return Double.NaN;
                                }
                            })
                    .description("CPU rate limit percentage per resource group")
                    .tag("resourceGroupName", valueStats.rsgValues().resourceGroupName())
                    .register(registry).getId());
        }

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "max_mem_usage"),
                        () -> {
                            SkewStats stats = memStats.get();
                            return stats != null ? stats.max() : Double.NaN;
                        })
                .description("Maximum Mem usage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "avg_mem_usage"),
                        () -> {
                            SkewStats stats = memStats.get();
                            return stats != null ? stats.avg() : Double.NaN;
                        })
                .description("Average Mem usage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "mem_usage_skew_ratio"),
                        () -> {
                            SkewStats stats = memStats.get();
                            return stats != null ? stats.skewRatio() : Double.NaN;
                        })
                .description("Mem usage skew percentage across all hosts")
                .register(registry);

        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "max_cpu_usage"),
                        () -> {
                            SkewStats stats = cpuStats.get();
                            return stats != null ? stats.max() : Double.NaN;
                        })
                .description("Maximum CPU usage percentage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "avg_cpu_usage"),
                        () -> {
                            SkewStats stats = cpuStats.get();
                            return stats != null ? stats.avg() : Double.NaN;
                        })
                .description("Average CPU usage percentage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "cpu_usage_skew_ratio"),
                        () -> {
                            SkewStats stats = cpuStats.get();
                            return stats != null ? stats.skewRatio() : Double.NaN;
                        })
                .description("CPU usage skew percentage across all hosts")
                .register(registry);
    }
}
