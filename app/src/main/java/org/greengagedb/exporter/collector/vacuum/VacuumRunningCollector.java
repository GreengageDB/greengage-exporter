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
package org.greengagedb.exporter.collector.vacuum;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.greengagedb.exporter.common.ValueUtils.getOrUnknown;

/**
 * Collector for currently running vacuum/autovacuum processes.
 */
@Slf4j
@ApplicationScoped
public class VacuumRunningCollector extends AbstractEntityCollector<String, VacuumRunningStats> {
    private static final String SQL = """
            SELECT
                datname,
                pid,
                usename,
                EXTRACT(EPOCH FROM (now() - xact_start))::bigint AS seconds_running
            FROM pg_stat_activity
            WHERE
                (query ILIKE 'vacuum%' or query ILIKE 'autovacuum:%')
              AND state <> 'idle'""";

    private final AtomicBoolean vacuumIsRunning = new AtomicBoolean(false);

    @Override
    public String getName() {
        return "vacuum_running";
    }

    @Override
    public boolean isEnabled() {
        return config.vacuumRunningEnabled();
    }

    @Override
    protected Map<String, VacuumRunningStats> collectEntities(Connection connection,
                                                              GreengageVersion version) throws SQLException {
        log.debug("Collecting vacuum statistics");

        Map<String, VacuumRunningStats> vacuumRunningStatsMap = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(SQL)) {

            while (rs.next()) {
                String databaseName = getOrUnknown(rs.getString("datname"));
                int pid = rs.getInt("pid");
                String userName = getOrUnknown(rs.getString("usename"));
                long secondsRunning = rs.getLong("seconds_running");
                String key = databaseName + "." + pid + "." + userName;
                vacuumRunningStatsMap.put(key, new VacuumRunningStats(
                        databaseName,
                        userName,
                        pid,
                        secondsRunning
                ));
            }
            if (vacuumRunningStatsMap.isEmpty()) {
                vacuumIsRunning.set(false);
                log.debug("No active vacuum/autovacuum processes found");
            } else {
                vacuumIsRunning.set(true);
                log.debug("Found {} active vacuum/autovacuum processes", vacuumRunningStatsMap.size());
            }
            return vacuumRunningStatsMap;
        }
    }

    @Override
    protected List<Meter.Id> registerEntityMetrics(MeterRegistry registry,
                                                   String key,
                                                   Supplier<VacuumRunningStats> vacuumSupplier) {
        var vacuumStats = vacuumSupplier.get();
        if (vacuumStats == null) {
            log.warn("Vacuum stats supplier returned null for key: {}", key);
            return List.of();
        }
        List<Meter.Id> meterIds = new ArrayList<>();
        String databaseName = vacuumStats.databaseName();
        String userName = vacuumStats.userName();
        int pid = vacuumStats.pid();
        meterIds.add(
                Gauge.builder(
                                MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "vacuum_running_seconds"),
                                () -> getValueOrDefault(vacuumSupplier.get(), VacuumRunningStats::secondsRunning))
                        .description("Seconds the vacuum/autovacuum has been running")
                        .tag("datname", databaseName)
                        .tag("usename", userName)
                        .tag("pid", String.valueOf(pid))
                        .register(registry)
                        .getId()
        );

        return meterIds;
    }

    @Override
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Gauge indicating if any vacuum/autovacuum is currently running
        Gauge.builder(
                        MetricNameBuilder.build(Constants.SUBSYSTEM_SERVER, "vacuum_running"),
                        vacuumIsRunning,
                        value -> value.get() ? 1.0 : 0.0)
                .description("Indicates if any vacuum/autovacuum process is currently running (1 = running, 0 = not running)")
                .register(registry);
    }

    @Override
    protected boolean shouldRemoveDeletedMetrics() {
        return true;
    }
}

