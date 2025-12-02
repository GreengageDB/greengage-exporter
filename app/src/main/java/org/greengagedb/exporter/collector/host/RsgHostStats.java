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
package org.greengagedb.exporter.collector.host;

public record RsgHostStats(
        Object data
) {

    public HostValues hostValues() {
        return (HostValues) data;
    }

    public RsgValues rsgValues() {
        return (RsgValues) data;
    }

    public record HostValues(
            String resourceGroupName,
            String hostname,
            int cpuUsage,
            int memoryUsage,
            int cpuRateLimit,
            int memoryLimit
    ) {
        public static HostValues of(RsgHostValues values) {
            return new HostValues(
                    values.resourceGroupName(),
                    values.hostname(),
                    values.cpuUsage(),
                    values.memoryUsage(),
                    values.cpuRateLimit(),
                    values.memoryLimit()
            );
        }
    }

    public record RsgValues(
            String resourceGroupName,
            int numRunning,
            int numQueueing,
            int cpuRateLimit,
            int memoryLimit
    ) {
        public static RsgValues of(RsgHostValues values) {
            return new RsgValues(
                    values.resourceGroupName(),
                    values.numRunning(),
                    values.numQueueing(),
                    values.cpuRateLimit(),
                    values.memoryLimit()
            );
        }
    }
}
