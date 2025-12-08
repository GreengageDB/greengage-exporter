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
package org.greengagedb.exporter.collector;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Abstract base class for collectors with aggregate metrics only (no per-entity metrics).
 *
 * <p><b>Use this class when:</b>
 * <ul>
 *   <li>Collecting cluster-wide or global statistics</li>
 *   <li>Metrics don't have dynamic entity-based tags</li>
 *   <li>You have a single state object or simple values to track</li>
 * </ul>
 *
 * <p><b>Examples:</b>
 * <ul>
 *   <li>Cluster state (accessible, uptime, master/standby)</li>
 *   <li>Global connection pool stats</li>
 *   <li>Overall system health indicators</li>
 * </ul>
 *
 * @param <T> Type of data collected (state object, statistics bean, etc.)
 */
@Slf4j
public abstract class AbstractAggregateCollector<T> implements Collector {

    @Inject
    protected CollectorsConfig config;

    @Inject
    protected MeterRegistry registry;

    @PostConstruct
    void init() {
        if (isEnabled()) {
            log.debug("Initializing collector: {}", getName());
            try {
                registerMetrics(registry);
                log.debug("Registered metrics for collector: {}", getName());
            } catch (Exception e) {
                log.error("Failed to register metrics for collector: {}", getName(), e);
                throw e;
            }
        } else {
            log.debug("Collector {} is disabled", getName());
        }
    }

    @Override
    public void collect(Connection connection, GreengageVersion version) throws SQLException {
        Objects.requireNonNull(connection, "Connection must not be null");
        Objects.requireNonNull(version, "Version must not be null");

        try {

            T data = collectData(connection, version);

            if (data != null) {
                updateState(data);
            } else {
                log.warn("Collector {} returned null data, state not updated", getName());
                onNullData();
            }
        } catch (SQLException e) {
            onCollectError(e);
            if (shouldFailOnError()) {
                throw e;
            }
            log.debug("Collector {} failed but continuing due to error handling policy", getName());
        }
    }

    /**
     * Collect data from the database.
     *
     * <p><b>Responsibilities:</b>
     * <ul>
     *   <li>Execute SQL queries to gather metric data</li>
     *   <li>Transform ResultSet into data object</li>
     *   <li>Return complete snapshot of current state</li>
     * </ul>
     *
     * <p><b>DO NOT:</b>
     * <ul>
     *   <li>Register metrics (use {@link #registerMetrics(MeterRegistry)} instead)</li>
     *   <li>Store state directly (return it and let {@link #updateState(Object)} handle it)</li>
     *   <li>Catch and swallow SQLException (let it propagate for proper error handling)</li>
     *   <li>Return null unless no data is available (will trigger {@link #onNullData()})</li>
     * </ul>
     *
     * @param connection Database connection (never null, already open)
     * @param version    Greengage version information (never null)
     * @return Collected data object, or null if no data available
     * @throws SQLException If database operation fails
     */
    protected abstract T collectData(Connection connection, GreengageVersion version)
            throws SQLException;

    /**
     * Update internal state with collected data.
     *
     * <p>This method is called after successful data collection.
     * Implementations should store the data in a thread-safe manner
     * so metrics can read it via suppliers.
     *
     * <p><b>Thread Safety:</b> This method must be thread-safe.
     * Consider using AtomicReference, volatile fields, or synchronized blocks.
     *
     * @param data Collected data (never null)
     */
    protected abstract void updateState(T data);

    /**
     * Register metrics with the meter registry.
     *
     * <p>This method is called once during initialization if the collector is enabled.
     *
     * <p><b>Best Practices:</b>
     * <ul>
     *   <li>Use suppliers to read current state dynamically</li>
     *   <li>Handle null state gracefully (return NaN or 0)</li>
     *   <li>Add meaningful descriptions to metrics</li>
     *   <li>Use consistent naming conventions</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Gauge.builder("my_metric", () -> {
     *     MyState state = stateRef.get();
     *     return state != null ? state.getValue() : Double.NaN;
     * })
     * .description("Description of my metric")
     * .tag("subsystem", "my_subsystem")
     * .register(registry);
     * }</pre>
     *
     * @param registry MeterRegistry to register metrics with (never null)
     */
    protected abstract void registerMetrics(MeterRegistry registry);

    /**
     * Hook called when collection returns null data.
     *
     * <p>Override to handle missing data scenarios (e.g., set default values).
     *
     * <p>By default, this logs a warning. The state is NOT updated when null is returned.
     */
    protected void onNullData() {
        // Default: warning already logged in collect()
    }

    /**
     * Hook called when an error occurs during collection.
     *
     * <p>Override to implement custom error handling or recovery.
     *
     * <p>Note: Error is logged automatically. If {@link #shouldFailOnError()} returns false,
     * the exception is suppressed and collection continues.
     *
     * @param e The SQLException that occurred
     */
    protected void onCollectError(SQLException e) {
        log.error("Error collecting data for {}: {}", getName(), e.getMessage(), e);
    }

    /**
     * Whether to throw SQLException or continue on errors.
     *
     * <p>Return {@code true} to propagate errors (fail fast).
     * Return {@code false} to log and continue (keep old state).
     *
     * <p>Default: {@code true} (fail fast)
     *
     * @return true to throw exceptions, false to suppress them
     */
    protected boolean shouldFailOnError() {
        return true;
    }

    // ========== Helper Methods ==========

    /**
     * Safely extract double value or return default.
     *
     * @param <V>       Value groupBy
     * @param value     Value to extract from (may be null)
     * @param extractor Function to extract number (may return null)
     * @return Extracted double value, or defaultValue if value or result is null
     */
    protected <V> Double getValueOrDefault(V value,
                                           Function<V, Number> extractor) {
        if (value == null) return 0.0;
        Number result = extractor.apply(value);
        return result != null ? result.doubleValue() : 0.0;
    }
}

