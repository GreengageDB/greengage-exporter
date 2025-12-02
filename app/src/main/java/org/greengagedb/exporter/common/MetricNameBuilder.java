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
package org.greengagedb.exporter.common;

import lombok.experimental.UtilityClass;

/**
 * Utility class for building Prometheus metric names following the convention:
 * namespace_subsystem_metric_name
 */
@UtilityClass
public final class MetricNameBuilder {
    /**
     * Build a fully qualified metric name
     *
     * @param subsystem The subsystem (e.g., "cluster", "node", "server")
     * @param name      The metric name
     * @return The fully qualified metric name
     */
    public static String build(String subsystem, String name) {
        if (subsystem == null || subsystem.isEmpty()) {
            return Constants.NAMESPACE + "_" + name;
        }
        return Constants.NAMESPACE + "_" + subsystem + "_" + name;
    }

    /**
     * Build a metric name without a subsystem
     *
     * @param name The metric name
     * @return The metric name with namespace
     */
    public static String build(String name) {
        return Constants.NAMESPACE + "_" + name;
    }
}

