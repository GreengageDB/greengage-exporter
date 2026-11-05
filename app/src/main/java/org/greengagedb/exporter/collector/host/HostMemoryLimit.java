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

public interface HostMemoryLimit {

    double value();

    String metricName();

    String description();

    record v6(double value) implements HostMemoryLimit {
        @Override
        public String metricName() {
            return "gp_resource_group_memory_limit";
        }

        @Override
        public String description() {
            return "The maximum percentage of system memory on each segment host that can be allocated to resource groups (GP6)";
        }
    }

    record v7(double value) implements HostMemoryLimit {
        @Override
        public String metricName() {
            return "gp_vmem_protect_limit_per_host_mb";
        }

        @Override
        public String description() {
            return "The maximum amount of memory a segment can allocate at any given time, multiplied by the number of active primary segments on the host (GP7)";
        }
    }
}
