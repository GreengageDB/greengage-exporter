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
package org.greengagedb.exporter.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Configuration for orchestrator behavior including retry logic and circuit breakers.
 */
@ConfigMapping(prefix = "app.orchestrator")
public interface OrchestratorConfig {

    /**
     * Maximum age of cached scrape results before they're considered stale.
     * When a scrape is in progress and Prometheus requests metrics,
     * cached results younger than this age will be returned.
     *
     * @return Maximum cache age (default: 30 seconds)
     */
    @WithDefault("30s")
    Duration scrapeCacheMaxAge();

    /**
     * Number of retry attempts for database connection failures.
     *
     * @return Number of retry attempts (default: 3)
     */
    @WithDefault("3")
    int connectionRetryAttempts();

    /**
     * Base delay between connection retry attempts.
     * Actual delay uses exponential backoff: delay * attempt_number.
     *
     * @return Base retry delay (default: 1 second)
     */
    @WithDefault("1s")
    Duration connectionRetryDelay();

    /**
     * Number of collector failures before assuming database issue
     * and stopping the scrape early (circuit breaker pattern).
     *
     * @return Failure threshold (default: 3)
     */
    @WithDefault("3")
    int collectorFailureThreshold();

    /**
     * Whether to enable the circuit breaker for collector failures.
     * If disabled, all collectors will always run even if some fail.
     *
     * @return true to enable circuit breaker (default: true)
     */
    @WithDefault("true")
    boolean circuitBreakerEnabled();
}

