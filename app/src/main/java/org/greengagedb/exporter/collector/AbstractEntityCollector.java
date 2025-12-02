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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Abstract base class for collectors with dynamic per-entity metrics.
 *
 * <p><b>Use this class when:</b>
 * <ul>
 *   <li>Tracking metrics for multiple entities (databases, tables, segments, queries)</li>
 *   <li>Metrics have entity-specific tags (dbname, tablename, etc.)</li>
 *   <li>Entities can be added or removed dynamically</li>
 * </ul>
 *
 * <p><b>Examples:</b>
 * <ul>
 *   <li>Database size per database (dbname=postgres, dbname=template1)</li>
 *   <li>Table bloat per table (schemaname=public, tablename=users)</li>
 *   <li>Segment health per segment (segmentid=0, segmentid=1)</li>
 * </ul>
 *
 * @param <K> Type of entity key (String, Integer, composite key, etc.)
 * @param <V> Type of entity value (DatabaseInfo, SegmentInfo, etc.)
 */
@Slf4j
public abstract class AbstractEntityCollector<K, V> implements Collector {

    /**
     * Cache of registered entity keys to avoid duplicate metric registration.
     */
    private final Set<K> registeredKeys = ConcurrentHashMap.newKeySet();
    /**
     * Tracking meter IDs for cleanup (only populated if shouldRemoveDeletedMetrics() == true).
     */
    private final Map<K, List<Meter.Id>> entityMeterIds = new ConcurrentHashMap<>();
    @Inject
    protected CollectorsConfig config;
    @Inject
    protected MeterRegistry registry;
    /**
     * Current entity snapshot (read-only reference, replaced atomically).
     */
    private volatile Map<K, V> entities = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (isEnabled()) {
            log.debug("Initializing collector: {}", getName());
            try {
                registerAggregateMetrics(registry);
                log.debug("Registered aggregate metrics for collector: {}", getName());
            } catch (Exception e) {
                log.error("Failed to register aggregate metrics for collector: {}", getName(), e);
                throw e;
            }
        } else {
            log.debug("Collector {} is disabled", getName());
        }
    }

    @Override
    public final void collect(Connection connection, GreengageVersion version) throws SQLException {
        Objects.requireNonNull(connection, "Connection must not be null");
        Objects.requireNonNull(version, "Version must not be null");

        try {
            // 1. Collect new entities from database
            Map<K, V> newEntities = collectEntities(connection, version);

            // 2. Validate collected data
            validateEntities(newEntities);

            // 3. Handle deleted entities (if cleanup is enabled)
            if (shouldRemoveDeletedMetrics()) {
                handleDeletedEntities(newEntities.keySet());
            }

            // 4. Atomically replace entity map (thread-safe)
            Map<K, V> oldEntities = entities;
            entities = new ConcurrentHashMap<>(newEntities);

            // 5. Register metrics for new keys
            for (Map.Entry<K, V> entry : newEntities.entrySet()) {
                K key = entry.getKey();
                if (registeredKeys.add(key)) {
                    // First time seeing this key - register metrics
                    List<Meter.Id> meterIds = registerEntityMetrics(registry, key, () -> entities.get(key));
                    if (shouldRemoveDeletedMetrics() && meterIds != null && !meterIds.isEmpty()) {
                        entityMeterIds.put(key, meterIds);
                    }
                    log.debug("Registered metrics for new entity: {}", key);
                }
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
     * Handle cleanup of metrics for deleted entities.
     * Removes metrics from registry and cleans up tracking data.
     */
    private void handleDeletedEntities(Set<K> currentKeys) {
        Set<K> deletedKeys = new HashSet<>(entities.keySet());
        deletedKeys.removeAll(currentKeys);

        for (K key : deletedKeys) {
            List<Meter.Id> meterIds = entityMeterIds.remove(key);
            if (meterIds != null) {
                for (Meter.Id id : meterIds) {
                    try {
                        registry.remove(id);
                    } catch (Exception e) {
                        log.warn("Failed to remove meter {} for deleted entity {}: {}",
                                id, key, e.getMessage());
                    }
                }
                log.debug("Removed {} metrics for deleted entity: {}", meterIds.size(), key);
            }
            registeredKeys.remove(key);
        }
    }

    /**
     * Validate collected entities before processing.
     *
     * @throws IllegalStateException if validation fails
     */
    private void validateEntities(Map<K, V> newEntities) {
        if (newEntities == null) {
            throw new IllegalStateException(
                    "collectEntities() must not return null for collector: " + getName());
        }

        // Validate keys and values are not null
        newEntities.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalStateException(
                        "Entity keys must not be null in collector: " + getName());
            }
            if (value == null) {
                throw new IllegalStateException(
                        "Entity values must not be null for key: " + key +
                                " in collector: " + getName());
            }
        });
    }

    // ========== Abstract Methods (Must Override) ==========

    /**
     * Collect entities from database.
     *
     * <p><b>Responsibilities:</b>
     * <ul>
     *   <li>Execute SQL queries to gather entity data</li>
     *   <li>Transform ResultSet rows into entity objects</li>
     *   <li>Return complete snapshot of current entities as a Map</li>
     * </ul>
     *
     * <p><b>DO NOT:</b>
     * <ul>
     *   <li>Register metrics (handled by framework)</li>
     *   <li>Store state in instance variables (return it instead)</li>
     *   <li>Return null (return empty map instead)</li>
     *   <li>Include null keys or values (will fail validation)</li>
     *   <li>Catch and swallow SQLException (let it propagate)</li>
     * </ul>
     *
     * <p><b>Thread Safety:</b> This method may be called concurrently.
     * Use local variables only, don't mutate shared state.
     *
     * @param connection Database connection (never null, already open)
     * @param version    Greengage version information (never null)
     * @return Map of entities (never null, may be empty)
     * @throws SQLException if database error occurs
     */
    protected abstract Map<K, V> collectEntities(
            Connection connection,
            GreengageVersion version
    ) throws SQLException;

    // ========== Optional Override Methods ==========

    /**
     * Register metrics for a specific entity.
     *
     * <p>This method is called ONCE for each unique key when it's first seen.
     * Use the valueSupplier to get current entity value dynamically.
     *
     * <p><b>Important:</b> The supplier may return null if the entity was deleted
     * between collections. Handle null gracefully (return Double.NaN for gauges).
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * List<Meter.Id> ids = new ArrayList<>();
     * ids.add(Gauge.builder(metricName, () -> {
     *     DatabaseInfo db = valueSupplier.get();
     *     return db != null ? db.sizeMb : Double.NaN;
     * })
     * .tag("dbname", dbname)
     * .description("Database size in megabytes")
     * .register(registry)
     * .getId());
     * return ids;
     * }</pre>
     *
     * <p><b>Note:</b> Return empty list if no per-entity metrics are needed
     * (only aggregate metrics). This is valid but consider using
     * {@link AbstractAggregateCollector} instead.
     *
     * @param registry      MeterRegistry to register metrics (never null)
     * @param key           Entity key (never null, e.g., database name, segment id)
     * @param valueSupplier Supplier to get current entity value (may return null)
     * @return List of registered meter IDs for cleanup (empty list if no cleanup needed)
     */
    protected List<Meter.Id> registerEntityMetrics(
            MeterRegistry registry,
            K key,
            Supplier<V> valueSupplier
    ) {
        // Default: no entity-specific metrics
        return Collections.emptyList();
    }

    /**
     * Register aggregate metrics (without dynamic entity tags).
     *
     * <p>These are fixed metrics registered once at initialization, such as:
     * <ul>
     *   <li>Total count across all entities</li>
     *   <li>Maximum/minimum values across entities</li>
     *   <li>Aggregated sums or averages</li>
     * </ul>
     *
     * <p><b>Best Practices:</b>
     * <ul>
     *   <li>Use {@link #getEntities()} to access current entities</li>
     *   <li>Suppliers should iterate entities and aggregate values</li>
     *   <li>Handle empty entity map gracefully</li>
     * </ul>
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Gauge.builder("db_total_count", () -> (double) getEntities().size())
     *     .description("Total number of databases")
     *     .register(registry);
     *
     * Gauge.builder("db_total_size", () ->
     *     getEntities().values().stream()
     *         .mapToDouble(DatabaseInfo::getSize)
     *         .sum()
     * ).register(registry);
     * }</pre>
     *
     * @param registry MeterRegistry to register metrics (never null)
     */
    protected void registerAggregateMetrics(MeterRegistry registry) {
        // Default: no aggregate metrics
    }

    /**
     * Whether to remove metrics when entities are deleted.
     *
     * <p><b>Guidelines:</b>
     * <ul>
     *   <li>Return {@code true} for high-churn entities (locks, active queries, sessions)</li>
     *   <li>Return {@code false} for stable entities (databases, tables, segments)</li>
     * </ul>
     *
     * <p><b>Trade-offs:</b>
     * <ul>
     *   <li>Cleanup=true: Prevents metric cardinality explosion but adds overhead</li>
     *   <li>Cleanup=false: Simpler but metrics accumulate over time</li>
     * </ul>
     *
     * <p>Default: {@code false} (metrics remain after entity deletion, showing last value)
     *
     * @return true to enable metric cleanup, false to keep metrics forever
     */
    protected boolean shouldRemoveDeletedMetrics() {
        return false;
    }

    /**
     * Hook called when an error occurs during collection.
     *
     * <p>Override to implement custom error handling or recovery.
     *
     * @param e The SQLException that occurred
     */
    protected void onCollectError(SQLException e) {
        log.error("Error collecting entities for {}: {}", getName(), e.getMessage(), e);
    }

    /**
     * Whether to throw SQLException or continue on errors.
     *
     * <p>Return {@code true} to propagate errors (fail fast).
     * Return {@code false} to log and continue (keep old entity state).
     *
     * <p>Default: {@code true} (fail fast)
     *
     * @return true to throw exceptions, false to suppress them
     */
    protected boolean shouldFailOnError() {
        return true;
    }

    /**
     * Get current entities (read-only view).
     *
     * <p>Useful for implementing aggregate metrics that need to iterate over all entities.
     *
     * <p><b>Thread Safety:</b> Returns a snapshot of entities at the time of call.
     * The map itself won't change, but concurrent collections may update the reference.
     *
     * @return Unmodifiable view of current entities
     */
    protected Map<K, V> getEntities() {
        return Collections.unmodifiableMap(entities);
    }

    /**
     * Get count of registered entities.
     *
     * @return Number of entities currently tracked
     */
    protected int getEntityCount() {
        return entities.size();
    }

    /**
     * Safely extract double value or return NaN.
     *
     * @param value     Value to extract from (maybe null)
     * @param extractor Function to extract number (may return null)
     * @param <T>       Value groupBy
     * @return Extracted double value, or Double.NaN if value or result is null
     */
    protected <T> double getValueOrNaN(T value, java.util.function.Function<T, Number> extractor) {
        if (value == null) return Double.NaN;
        Number result = extractor.apply(value);
        return result != null ? result.doubleValue() : Double.NaN;
    }

    /**
     * Safely extract double value or return default.
     *
     * @param <T>       Value groupBy
     * @param extractor Function to extract number (may return null)
     * @return Extracted double value, or defaultValue if value or result is null
     */
    protected <T> double getValueOrDefault(T value,
                                           Function<T, Number> extractor) {
        if (value == null) return 0.0;
        Number result = extractor.apply(value);
        return result != null ? result.doubleValue() : 0.0;
    }
}

