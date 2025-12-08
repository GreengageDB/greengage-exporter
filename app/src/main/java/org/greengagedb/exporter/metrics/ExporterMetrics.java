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
package org.greengagedb.exporter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.greengagedb.exporter.common.Constants;
import org.greengagedb.exporter.common.MetricNameBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal exporter metrics
 */
@Slf4j
@ApplicationScoped
public class ExporterMetrics {

    private static final String NAME_TOTAL_SCRAPED = MetricNameBuilder.build(Constants.SUBSYSTEM_EXPORTER, "total_scraped");
    private static final String NAME_TOTAL_ERROR = MetricNameBuilder.build(Constants.SUBSYSTEM_EXPORTER, "total_error");
    private static final String NAME_COLLECTOR_ERROR = MetricNameBuilder.build(Constants.SUBSYSTEM_EXPORTER, "collector_error");
    private static final String NAME_SCRAPE_DURATION = MetricNameBuilder.build(Constants.SUBSYSTEM_EXPORTER, "scrape_duration_seconds");
    private static final String NAME_UPTIME = MetricNameBuilder.build(Constants.SUBSYSTEM_EXPORTER, "uptime_seconds");
    private static final String NAME_UP = MetricNameBuilder.build("up");

    private final AtomicReference<Double> databaseUpGaugeValue = new AtomicReference<>(0.0);
    private final Instant startTime = Instant.now();

    private final MeterRegistry registry;

    private Counter scrapeSuccessCounter;
    private Counter scrapeErrorCounter;
    private Timer scrapeDurationTimer;

    @Inject
    public ExporterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registerScrapeSuccessCounter();
        registerScrapeErrorCounter();
        registerDatabaseUpGauge();
        registerScrapeDurationTimer();
        registerUptimeGauge();
        log.info("Exporter metrics initialized");
    }

    private void registerScrapeSuccessCounter() {
        scrapeSuccessCounter = Counter.builder(NAME_TOTAL_SCRAPED)
                .description("Total number of scrapes")
                .register(registry);
    }

    private void registerScrapeErrorCounter() {
        scrapeErrorCounter = Counter.builder(NAME_TOTAL_ERROR)
                .description("Total number of scrape errors")
                .register(registry);
    }

    private void registerDatabaseUpGauge() {
        Gauge.builder(NAME_UP, databaseUpGaugeValue::get)
                .description("Whether greengage cluster is reachable (1=up, 0=down)")
                .register(registry);
    }

    private void registerScrapeDurationTimer() {
        scrapeDurationTimer = Timer.builder(NAME_SCRAPE_DURATION)
                .description("Duration of the last scrape in seconds")
                .register(registry);
    }

    private void registerUptimeGauge() {
        Gauge.builder(NAME_UPTIME, () -> Duration.between(startTime, Instant.now()).toSeconds())
                .description("Duration in seconds since the exporter started")
                .register(registry);
    }

    public void incrementTotalScraped() {
        scrapeSuccessCounter.increment();
    }

    public void incrementTotalError() {
        scrapeErrorCounter.increment();
    }

    /**
     * Increment error counter for a specific collector.
     * This helps identify which collector is causing issues.
     *
     * @param collectorName Name of the collector that failed
     */
    public void incrementCollectorError(String collectorName) {
        Counter.builder(NAME_COLLECTOR_ERROR)
                .tag("collector", collectorName)
                .description("Number of errors per collector")
                .register(registry)
                .increment();
    }

    public void recordScrapeDuration(Duration duration) {
        scrapeDurationTimer.record(duration);
    }

    public void setGreengageUp(boolean up) {
        databaseUpGaugeValue.set(up ? 1.0 : 0.0);
    }
}
