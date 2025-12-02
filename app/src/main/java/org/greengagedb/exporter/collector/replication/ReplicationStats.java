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
package org.greengagedb.exporter.collector.replication;

public record ReplicationStats(
        String applicationName,
        String state,
        String syncState,
        long writeLagBytes,
        long flushLagBytes,
        long replayLagBytes
) {
    /**
     * Convert state string to numeric value for metrics
     *
     * @return 1=streaming, 2=catchup, 3=backup, 0=unknown
     */
    public double getStateNumeric() {
        if (state == null) {
            return 0.0;
        }
        return switch (state.toLowerCase()) {
            case "streaming" -> 1.0;
            case "catchup" -> 2.0;
            case "backup" -> 3.0;
            default -> 0.0;
        };
    }

    /**
     * Convert sync_state to numeric value
     *
     * @return 2=sync, 1=async, 0=potential/unknown
     */
    public double getSyncStateNumeric() {
        if (syncState == null) {
            return 0.0;
        }
        return switch (syncState.toLowerCase()) {
            case "sync" -> 2.0;
            case "async" -> 1.0;
            case "potential" -> 0.5;
            default -> 0.0;
        };
    }
}

