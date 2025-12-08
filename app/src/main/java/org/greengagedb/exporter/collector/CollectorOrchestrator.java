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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.config.OrchestratorConfig;
import org.greengagedb.exporter.config.PerDBMode;
import org.greengagedb.exporter.connection.DbConnectionProvider;
import org.greengagedb.exporter.gg.DatabaseService;
import org.greengagedb.exporter.metrics.ExporterMetrics;
import org.greengagedb.exporter.model.GreengageVersion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates metric collection from all enabled collectors.
 *
 * <p>When Prometheus scrapes while another scrape is in progress,
 * cached results are returned if available and not stale, preventing
 * the target from being marked as down.
 */
@Slf4j
@ApplicationScoped
public class CollectorOrchestrator {

    private final Lock scrapeLock = new ReentrantLock();
    private final AtomicReference<ScrapeResult> lastSuccessfulScrape = new AtomicReference<>();

    private final OrchestratorConfig config;
    private final DatabaseService databaseService;
    private final ExporterMetrics exporterMetrics;
    private final Map<CollectorGroup, List<Collector>> activeCollectorGroup;
    private final DbConnectionProvider connectionProvider;

    @Inject
    public CollectorOrchestrator(OrchestratorConfig config,
                                 CollectorsConfig collectorsConfig,
                                 DatabaseService databaseService,
                                 ExporterMetrics exporterMetrics,
                                 Instance<Collector> collectors,
                                 DbConnectionProvider connectionProvider) {
        this.config = config;
        this.databaseService = databaseService;
        this.exporterMetrics = exporterMetrics;
        this.activeCollectorGroup = initializeCollectors(collectors, collectorsConfig.perDB().mode());
        this.connectionProvider = connectionProvider;
        for (Map.Entry<CollectorGroup, List<Collector>> entry : activeCollectorGroup.entrySet()) {
            log.info("Collector Group '{}' has {} enabled collectors",
                    entry.getKey(), entry.getValue().size());
        }
    }

    /**
     * Initialize and filter collectors based on their enabled status.
     *
     * @param collectors       All available collectors
     * @param collectorsConfig Per-DB mode configuration
     * @return Immutable list of enabled collectors
     */
    private Map<CollectorGroup, List<Collector>> initializeCollectors(Instance<Collector> collectors,
                                                                      PerDBMode collectorsConfig) {
        Map<CollectorGroup, List<Collector>> enabled = new HashMap<>();

        for (Collector collector : collectors) {
            if (collector.isEnabled()) {
                CollectorGroup group = collector.getGroup();
                // Special handling for PER_DB collectors based on config
                if (group == CollectorGroup.PER_DB) {
                    if (collectorsConfig == PerDBMode.NONE) {
                        group = CollectorGroup.GENERAL;
                    }
                }
                enabled.computeIfAbsent(group, k -> new ArrayList<>()).add(collector);
                log.info("Enabled collector: {}", collector.getName());
            } else {
                log.debug("Disabled collector: {}", collector.getName());
            }
        }
        return enabled;
    }

    /**
     * Perform scrape with fallback to cached results if scrape is in progress.
     *
     * <p>When called while another scrape is running:
     * <ul>
     *   <li>Returns cached results if available and not stale</li>
     *   <li>Prevents Prometheus from marking target as down</li>
     *   <li>Logs warning if no valid cache available</li>
     * </ul>
     */
    public void scrape() {
        // If scrape in progress, try to use cached results
        if (!scrapeLock.tryLock()) {
            log.debug("Scrape already in progress");
            ScrapeResult cached = lastSuccessfulScrape.get();

            if (cached != null && !cached.isStale(config.scrapeCacheMaxAge())) {
                log.debug("Returning cached scrape from {} ago", cached.getAge());
                return;
            }

            log.warn("No valid cached scrape available, waiting for current scrape to complete");
            return;
        }

        try {
            ScrapeResult result = performScrapeInternal();

            if (result.successful()) {
                lastSuccessfulScrape.set(result);
                log.debug("Scrape successful, cached for future use");
            }
        } finally {
            scrapeLock.unlock();
        }
    }

    private ScrapeResult performScrapeInternal() {
        Instant start = Instant.now();
        exporterMetrics.incrementTotalScraped();
        log.debug("Starting scrape");

        try {
            GreengageVersion version = verifyDatabaseAndVersion();
            if (version == null) {
                return ScrapeResult.failed(start);
            }

            collectFromAll(version);
            return ScrapeResult.successful(start);

        } catch (SQLException e) {
            log.error("Database error during scrape: {}", e.getMessage(), e);
            exporterMetrics.setGreengageUp(false);
            exporterMetrics.incrementTotalError();
            return ScrapeResult.failed(start, e);
        } catch (Exception e) {
            log.error("Unexpected error during scrape: {}", e.getMessage(), e);
            exporterMetrics.incrementTotalError();
            return ScrapeResult.failed(start, e);
        } finally {
            recordDuration(start);
        }
    }

    private GreengageVersion verifyDatabaseAndVersion() throws SQLException {
        int maxAttempts = config.connectionRetryAttempts();
        Duration retryDelay = config.connectionRetryDelay();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!databaseService.testConnection()) {
                    if (attempt < maxAttempts) {
                        log.warn("Database connection test failed (attempt {}/{}), retrying in {}",
                                attempt, maxAttempts, retryDelay.multipliedBy(attempt));
                        Thread.sleep(retryDelay.multipliedBy(attempt).toMillis());
                        continue;
                    }
                    log.error("Database connection test failed after {} attempts", maxAttempts);
                    exporterMetrics.setGreengageUp(false);
                    exporterMetrics.incrementTotalError();
                    return null;
                }

                GreengageVersion version = databaseService.detectVersion();
                if (version == null) {
                    log.error("Failed to detect Greengage version");
                    exporterMetrics.setGreengageUp(false);
                    exporterMetrics.incrementTotalError();
                    return null;
                }

                exporterMetrics.setGreengageUp(true);
                if (attempt > 1) {
                    log.info("Database connection restored after {} attempts", attempt);
                }
                return version;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Connection retry interrupted", e);
            }
        }

        return null;
    }

    private void collectFromAll(GreengageVersion version) throws SQLException {
        try (Connection connection = databaseService.getPoolledConnection()) {
            CollectorExecutionContext context = new CollectorExecutionContext(
                    config.collectorFailureThreshold(),
                    config.circuitBreakerEnabled()
            );

            // Execute GENERAL collectors on main connection
            List<Collector> generalCollectors = activeCollectorGroup.getOrDefault(CollectorGroup.GENERAL, List.of());
            executeGeneralCollectors(generalCollectors, connection, version, context);

            // Execute PER_DB collectors on per-database connections
            List<Collector> perDbCollectors = activeCollectorGroup.getOrDefault(CollectorGroup.PER_DB, List.of());
            executePerDbCollectors(perDbCollectors, connection, version, context);

            if (context.failures > 0) {
                log.warn("Scrape completed with {} collector failures", context.failures);
            }
        }
    }

    /**
     * Execute collectors that work on the general connection.
     */
    private void executeGeneralCollectors(List<Collector> collectors, Connection connection,
                                          GreengageVersion version, CollectorExecutionContext context) throws SQLException {
        for (Collector collector : collectors) {
            executeCollector(collector, connection, version, context, "");
        }
    }

    /**
     * Execute collectors that work on per-database connections.
     * Ensures proper cleanup of temporary DataSources even if circuit breaker is triggered.
     */
    private void executePerDbCollectors(List<Collector> collectors, Connection baseConnection,
                                        GreengageVersion version, CollectorExecutionContext context) throws SQLException {
        if (collectors.isEmpty()) {
            return;
        }

        List<DataSource> dataSources = connectionProvider.getDataSources(baseConnection);
        try {
            for (DataSource dataSource : dataSources) {
                for (Collector collector : collectors) {
                    try (Connection dbConnection = dataSource.getConnection()) {
                        String databaseName = dbConnection.getCatalog();
                        executeCollector(collector, dbConnection, version, context, " (per-database: %s)".formatted(databaseName));
                    } catch (SQLException e) {
                        handleCollectorFailure(collector, context, e, " (per-database)");
                    }
                }
            }
        } finally {
            // Ensure cleanup happens even if circuit breaker is triggered
            connectionProvider.cleanup();
        }
    }

    /**
     * Execute a single collector with error handling and circuit breaker logic.
     */
    private void executeCollector(Collector collector, Connection connection, GreengageVersion version,
                                  CollectorExecutionContext context, String logSuffix) throws SQLException {
        long collectionStart = System.currentTimeMillis();
        try {
            log.debug("Collecting metrics from: {}{}", collector.getName(), logSuffix);
            collector.collect(connection, version);
        } catch (Exception e) {
            handleCollectorFailure(collector, context, e, logSuffix);
        } finally {
            long duration = System.currentTimeMillis() - collectionStart;
            log.debug("Collector {}{} completed in {} ms", collector.getName(), logSuffix, duration);
        }
    }

    /**
     * Handle collector failure with circuit breaker logic.
     */
    private void handleCollectorFailure(Collector collector, CollectorExecutionContext context,
                                        Exception e, String logSuffix) throws SQLException {
        context.failures++;
        log.error("Error collecting metrics from {}{} ({}/{} failures): {}",
                collector.getName(), logSuffix, context.failures, context.failureThreshold, e.getMessage(), e);
        exporterMetrics.incrementTotalError();
        exporterMetrics.incrementCollectorError(collector.getName());

        // Circuit breaker: if too many collectors fail, assume DB issue
        if (context.circuitBreakerEnabled && context.failures >= context.failureThreshold) {
            log.error("Too many collector failures ({}), circuit breaker triggered - " +
                    "assuming database issue, stopping remaining collectors", context.failures);
            throw new SQLException("Multiple collectors failed, possible database issue", e);
        }
    }

    private void recordDuration(Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        exporterMetrics.recordScrapeDuration(duration);
        log.debug("Scrape completed in {} ms", duration.toMillis());
    }

    /**
     * Get the number of active collectors.
     *
     * @return Number of enabled collectors
     */
    public int getActiveCollectorCount() {
        return activeCollectorGroup.size();
    }

    /**
     * Context for tracking collector execution state.
     */
    private static class CollectorExecutionContext {
        private final int failureThreshold;
        private final boolean circuitBreakerEnabled;
        private int failures = 0;

        CollectorExecutionContext(int failureThreshold, boolean circuitBreakerEnabled) {
            this.failureThreshold = failureThreshold;
            this.circuitBreakerEnabled = circuitBreakerEnabled;
        }
    }
}
