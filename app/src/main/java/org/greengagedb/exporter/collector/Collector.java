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

import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Interface for metric collectors.
 *
 * <p>Each collector is responsible for scraping a specific set of metrics from Greengage
 * and registering them with the MeterRegistry.
 *
 * <p><b>Implementations should extend one of:</b>
 * <ul>
 *   <li>{@link AbstractEntityCollector} - For per-entity metrics with dynamic tags (databases, segments, queries)</li>
 *   <li>{@link AbstractAggregateCollector} - For aggregate-only metrics (cluster state, global stats)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Collectors must be thread-safe as they may be called concurrently.
 *
 * @see AbstractEntityCollector
 * @see AbstractAggregateCollector
 */
public interface Collector {

    /**
     * Get the name of this collector.
     *
     * <p>Used for logging, identification, and debugging purposes.
     *
     * @return Collector name (should be unique and descriptive)
     */
    String getName();

    /**
     * Collect metrics from the database.
     *
     * <p>This method is called periodically by the orchestrator. Implementations should:
     * <ul>
     *   <li>Query the database for metric data</li>
     *   <li>Update registered metrics with new values</li>
     *   <li>Handle errors gracefully</li>
     * </ul>
     *
     * @param connection Database connection (never null, already open)
     * @param version    Greengage version information (never null)
     * @throws SQLException If database operation fails (will be logged by orchestrator)
     */
    void collect(Connection connection, GreengageVersion version) throws SQLException;

    /**
     * Check if this collector is enabled.
     *
     * <p>Disabled collectors are not initialized or called by the orchestrator.
     *
     * @return {@code true} if enabled, {@code false} otherwise
     */
    boolean isEnabled();

    /**
     * Get the group this collector belongs to.
     *
     * <p>Used for organizing and categorizing collectors.
     *
     * @return Collector group (default is {@link CollectorGroup#GENERAL})
     */
    default CollectorGroup getGroup() {
        return CollectorGroup.GENERAL;
    }
}

