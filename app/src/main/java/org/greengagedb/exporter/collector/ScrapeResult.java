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

import java.time.Duration;
import java.time.Instant;

/**
 * Result of a scrape operation, including timing and status information.
 *
 * <p>Used for caching successful scrapes to provide stale metrics
 * when a new scrape is in progress, preventing Prometheus from marking
 * the target as down during concurrent scrape requests.
 *
 * @param timestamp When the scrape started
 * @param successful Whether the scrape completed successfully
 * @param error Optional error if the scrape failed
 */
public record ScrapeResult(Instant timestamp, boolean successful, Throwable error) {

    /**
     * Create a successful scrape result.
     *
     * @param start When the scrape started
     * @return Successful scrape result
     */
    public static ScrapeResult successful(Instant start) {
        return new ScrapeResult(start, true, null);
    }

    /**
     * Create a failed scrape result without error details.
     *
     * @param start When the scrape started
     * @return Failed scrape result
     */
    public static ScrapeResult failed(Instant start) {
        return new ScrapeResult(start, false, null);
    }

    /**
     * Create a failed scrape result with error details.
     *
     * @param start When the scrape started
     * @param error The error that caused the failure
     * @return Failed scrape result with error
     */
    public static ScrapeResult failed(Instant start, Throwable error) {
        return new ScrapeResult(start, false, error);
    }

    /**
     * Check if this scrape result is too old to be useful.
     *
     * @param maxAge Maximum age before result is considered stale
     * @return true if the result is older than maxAge
     */
    public boolean isStale(Duration maxAge) {
        return Duration.between(timestamp, Instant.now()).compareTo(maxAge) > 0;
    }

    /**
     * Get the age of this scrape result.
     *
     * @return Duration since the scrape started
     */
    public Duration getAge() {
        return Duration.between(timestamp, Instant.now());
    }
}

