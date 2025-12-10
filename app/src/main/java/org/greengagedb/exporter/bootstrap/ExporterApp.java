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
package org.greengagedb.exporter.bootstrap;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.collector.CollectorOrchestrator;
import org.greengagedb.exporter.config.ScrapeConfig;
import org.greengagedb.exporter.gg.DatabaseService;
import org.greengagedb.exporter.model.GreengageVersion;

/**
 * Application lifecycle bean that initializes the exporter on startup
 * and schedules periodic metric collection.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Startup: Print banner, validate configuration, detect database version</li>
 *   <li>Runtime: Periodic scrapes via scheduler</li>
 * </ol>
 */
@Slf4j
@ApplicationScoped
public class ExporterApp {
    private final DatabaseService databaseService;
    private final CollectorOrchestrator orchestrator;
    private final ScrapeConfig scrapeConfig;
    private final Banners banner;

    @Inject
    public ExporterApp(DatabaseService databaseService,
                       CollectorOrchestrator orchestrator,
                       ScrapeConfig scrapeConfig,
                       Banners banner) {
        this.databaseService = databaseService;
        this.orchestrator = orchestrator;
        this.scrapeConfig = scrapeConfig;
        this.banner = banner;
    }

    void onStartup(@Observes StartupEvent event) {
        banner.printHeader();

        // Log configuration (orchestrator already initialized via constructor injection)
        logConfiguration();

        // Try to detect version on startup (non-blocking, will retry on first scrape if fails)
        detectAndLogVersion();

        banner.printFooter();
    }

    private void logConfiguration() {
        log.info("Configuration:");
        log.info("  Scrape interval:        {}", scrapeConfig.interval());
        log.info("  Active collectors:      {}", orchestrator.getActiveCollectorCount());
        log.info("  Database URL:           {}", maskSensitiveInfo(databaseService.getUrl()));
    }

    /**
     * Mask sensitive information in connection strings for logging.
     *
     * @param url Database connection URL
     * @return Masked URL with password hidden
     */
    private String maskSensitiveInfo(String url) {
        if (url == null) {
            return "not configured";
        }
        // Mask password in connection string
        return url.replaceAll("password=[^&\\s]+", "password=***")
                .replaceAll(":[^:/@]+@", ":***@"); // Also mask user:pass@host format
    }

    private void detectAndLogVersion() {
        try {
            GreengageVersion version = databaseService.detectVersion();
            if (version != null) {
                log.info("Database connection successful:");
                log.info("  Greengage version:      {}", version.fullVersion());
                log.info("  Major.Minor.Patch:      {}.{}.{}",
                        version.major(),
                        version.minor(),
                        version.patch());
                if (!version.isSupported()) {
                    log.warn("Detected Greengage version {} is not supported. Minimum supported version is {}",
                            version.fullVersion(), GreengageVersion.minimumVersion());
                    log.error("Shutting down Greengage Exporter due to unsupported database version");
                    System.exit(1);
                }
            } else {
                log.warn("Could not detect Greengage version on startup");
                log.warn("Will retry on first scrape - check database connectivity");
            }
        } catch (Exception e) {
            log.warn("Error detecting Greengage version on startup: {}", e.getMessage());
            log.warn("Will retry on first scrape");
            log.debug("Version detection error details:", e);
        }
    }

    /**
     * Periodic scrape job that runs in the background.
     *
     * <p>Uses the interval configured in app.scrape.interval.
     * Concurrent execution is skipped to prevent overlapping scrapes.
     *
     * <p>The orchestrator handles:
     * <ul>
     *   <li>Concurrent scrape protection</li>
     *   <li>Cached results for overlapping requests</li>
     *   <li>Retry logic for transient failures</li>
     * </ul>
     */
    @Scheduled(every = "${app.scrape.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void schedulePeriodicScrape() {
        log.debug("Periodic scrape triggered");
        try {
            orchestrator.scrape();
        } catch (Exception e) {
            // Additional safety net for unexpected errors that escape orchestrator
            log.error("Unexpected error in scheduled scrape: {}", e.getMessage(), e);
            // Don't rethrow - we want the scheduler to keep running
        }
    }
}
