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
package org.greengagedb.exporter.collector.locks;

import lombok.Getter;

public record LocksKey(
        MetricType metric,
        String database,
        String lockType,
        String mode,
        String segment
) {
    @Getter
    public enum MetricType {
        WAITING_QUERIES("lock_waiting_queries", "Number of sessions waiting for locks"),
        MAX_WAIT_TIME_S("lock_wait_max_wait_seconds", "Maximum wait time for locks in seconds"),
        ;
        private final String name;
        private final String description;

        MetricType(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public static MetricType fromString(String name) {
            for (MetricType type : MetricType.values()) {
                if (type.name.equals(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown MetricType name: " + name);
        }
    }
}
