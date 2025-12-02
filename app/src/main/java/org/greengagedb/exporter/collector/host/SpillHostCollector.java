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
 * Collector for spill files usage per host.
 *
 * <p>Tracks disk spill usage when queries exceed memory limits,
 * helping identify queries that need memory tuning or hosts with skewed workloads.
 */
@Slf4j
@ApplicationScoped
public class SpillHostCollector extends AbstractEntityCollector<String, HostValueStats> {
    private static final String SQL = """
            WITH per_segment AS (SELECT w.segid     AS content, -- подставь реальный сегмент id
                                        SUM(w.size) AS spill_bytes
                                 FROM gp_toolkit.gp_workfile_usage_per_query w
                                 GROUP BY w.segid),
                 all_host AS (SELECT c.hostname,
                                     sum(COALESCE(p.spill_bytes, 0)) AS spill_bytes
                              FROM gp_segment_configuration c
                                       LEFT JOIN per_segment p
                                                 ON p.content = c.content
                              WHERE c.role = 'p'
                                and c.content >= 0
                              group by c.hostname)
            SELECT hostname,
                   spill_bytes
            FROM all_host
            ORDER BY hostname;""";
    private final AtomicReference<SkewStats> skewStats = new AtomicReference<>(new SkewStats(0.0, 0, 0));

    @Override
    public String getName() {
        return "spill_per_host";
    }

    @Override
    public boolean isEnabled() {
        return config.spillPerHostEnabled();
    }

    @Override
    protected Map<String, HostValueStats> collectEntities(Connection connection,
                                                          GreengageVersion version) throws SQLException {
        Map<String, HostValueStats> entitiesMap = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {
            while (rs.next()) {
                var stats = new HostValueStats(
                        null,
                        rs.getString("hostname"),
                        rs.getDouble("spill_bytes")
                );
                entitiesMap.put(stats.hostname() + ":" + stats.resourceGroupName(), stats);
            }
            skewStats.set(SkewStats.of(entitiesMap.values()));
        }
        return entitiesMap;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<HostValueStats> valueSupplier) {
        var valueStats = valueSupplier.get();
        if (valueStats == null) {
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        meterIds.add(Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "spill_usage_bytes"),
                        () -> {
                            var stats = valueSupplier.get();
                            return stats != null ? stats.value() : Double.NaN;
                        })
                .description("Spill files usage per host and resource group")
                .tag("hostname", valueStats.hostname())
                .register(registry).getId());
        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "max_spill_usage"),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.max() : Double.NaN;
                        })
                .description("Maximum Spill files usage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "avg_spill_usage"),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.avg() : Double.NaN;
                        })
                .description("Average Spill files usage across all hosts")
                .register(registry);
        Gauge.builder(MetricNameBuilder.build(Constants.SUBSYSTEM_HOST, "spill_usage_skew_ratio"),
                        () -> {
                            SkewStats stats = skewStats.get();
                            return stats != null ? stats.skewRatio() : Double.NaN;
                        })
                .description("Mem Spill files skew percentage across all hosts")
                .register(registry);
    }
}
