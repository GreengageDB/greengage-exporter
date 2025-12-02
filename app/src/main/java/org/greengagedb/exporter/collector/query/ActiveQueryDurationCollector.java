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
package org.greengagedb.exporter.collector.query;

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
import java.util.function.Supplier;

/**
 * Collector for active query duration distribution metrics.
 *
 * <p>This collector tracks the number of active queries grouped by duration buckets:
 * <ul>
 *   <li>0-10 seconds</li>
 *   <li>10-60 seconds</li>
 *   <li>60-180 seconds (1-3 minutes)</li>
 *   <li>180-600 seconds (3-10 minutes)</li>
 *   <li>600+ seconds (10+ minutes)</li>
 * </ul>
 *
 * <p>This helps identify:
 * <ul>
 *   <li>Long-running queries that may need optimization</li>
 *   <li>Patterns in query execution time</li>
 *   <li>Potential performance issues</li>
 * </ul>
 *
 * <p>Only active queries (state='active') from non-autovacuum processes are counted.
 */
@Slf4j
@ApplicationScoped
public class ActiveQueryDurationCollector extends AbstractEntityCollector<String, QueryDurationStats> {

    private static final String SQL = """
            WITH q AS (
                SELECT EXTRACT(EPOCH FROM (now() - query_start)) AS duration_seconds
                FROM pg_stat_activity
                WHERE pid <> pg_backend_pid()
                  AND state = 'active'
                  AND application_name <> 'autovacuum'
            )
            SELECT
                count(*) AS total_active_queries,
                sum(CASE WHEN duration_seconds >= 0 AND duration_seconds < 10 THEN 1 ELSE 0 END) AS cnt_0_10,
                sum(CASE WHEN duration_seconds >= 10 AND duration_seconds < 60 THEN 1 ELSE 0 END) AS cnt_10_60,
                sum(CASE WHEN duration_seconds >= 60 AND duration_seconds < 180 THEN 1 ELSE 0 END) AS cnt_60_180,
                sum(CASE WHEN duration_seconds >= 180 AND duration_seconds < 600 THEN 1 ELSE 0 END) AS cnt_180_600,
                sum(CASE WHEN duration_seconds >= 600 THEN 1 ELSE 0 END) AS cnt_600_plus
            FROM q
            """;

    @Override
    public String getName() {
        return "active_query_duration";
    }

    @Override
    public boolean isEnabled() {
        return config.activeQueryDuration();
    }

    @Override
    protected Map<String, QueryDurationStats> collectEntities(
            Connection connection,
            GreengageVersion version) throws SQLException {

        log.debug("Collecting active query duration statistics");

        Map<String, QueryDurationStats> buckets = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {

            if (rs.next()) {
                // Create stats for each bucket
                buckets.put("0_10",
                        new QueryDurationStats("0_10", rs.getInt("cnt_0_10")));
                buckets.put("10_60",
                        new QueryDurationStats("10_60", rs.getInt("cnt_10_60")));
                buckets.put("60_180",
                        new QueryDurationStats("60_180", rs.getInt("cnt_60_180")));
                buckets.put("180_600",
                        new QueryDurationStats("180_600", rs.getInt("cnt_180_600")));
                buckets.put("600_plus",
                        new QueryDurationStats("600_plus", rs.getInt("cnt_600_plus")));

                log.debug("Collected {} duration buckets", buckets.size());
            }
        } catch (SQLException e) {
            log.error("Failed to collect active query duration statistics: {}", e.getMessage(), e);
            throw e;
        }

        return buckets;
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(
            MeterRegistry registry,
            String bucketKey,
            Supplier<QueryDurationStats> statsSupplier) {

        QueryDurationStats stats = statsSupplier.get();
        if (stats == null) {
            log.warn("Stats supplier returned null for bucket: {}", bucketKey);
            return List.of();
        }

        List<Meter.Id> meterIds = new ArrayList<>();

        // Register gauge for this duration bucket
        Meter.Id meterId = Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_QUERY, "active_queries_duration_bucket"),
                        () -> {
                            QueryDurationStats currentStats = statsSupplier.get();
                            return currentStats != null ? currentStats.count() : 0.0;
                        })
                .tag("bucket", stats.bucket())
                .description("Number of active queries in duration bucket " + stats.bucket() + " seconds")
                .register(registry)
                .getId();

        meterIds.add(meterId);

        log.debug("Registered metric for duration bucket: {}", stats.bucket());

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Register total active queries count (sum of all buckets)
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_QUERY, "active_queries_total"),
                        () -> getEntities().values().stream()
                                .mapToInt(QueryDurationStats::count)
                                .sum())
                .description("Total number of active queries (all duration buckets)")
                .register(registry);

        // Register count of slow queries (>180 seconds)
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_QUERY, "active_queries_slow"),
                        () -> {
                            Map<String, QueryDurationStats> entities = getEntities();
                            int slowCount = 0;

                            QueryDurationStats bucket180_600 = entities.get("180_600");
                            if (bucket180_600 != null) {
                                slowCount += bucket180_600.count();
                            }

                            QueryDurationStats bucket600Plus = entities.get("600_plus");
                            if (bucket600Plus != null) {
                                slowCount += bucket600Plus.count();
                            }

                            return slowCount;
                        })
                .description("Number of slow active queries (duration > 180 seconds)")
                .register(registry);
    }

    @Override
    protected boolean shouldFailOnError() {
        // Continue collecting other metrics even if query stats fail
        return false;
    }
}

