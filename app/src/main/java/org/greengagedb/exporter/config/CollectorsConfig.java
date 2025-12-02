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

import java.util.Set;

/**
 * Simplified application configuration
 */
@ConfigMapping(prefix = "app.collectors")
public interface CollectorsConfig {

    @WithDefault("true")
    boolean clusterStateEnabled();

    @WithDefault("true")
    boolean segmentEnabled();

    @WithDefault("true")
    boolean connectionsEnabled();

    @WithDefault("true")
    boolean locksEnabled();

    @WithDefault("true")
    boolean databaseSizeEnabled();

    @WithDefault("true")
    boolean gpBackupHistoryEnabled();

    @WithDefault("true")
    boolean replicationMonitorEnabled();

    @WithDefault("true")
    boolean tableHealthEnabled();

    @WithDefault("true")
    boolean spillPerHostEnabled();

    @WithDefault("true")
    boolean diskPerHostEnabled();

    @WithDefault("true")
    boolean activeQueryDuration();

    PerDB perDB();

    @WithDefault("true")
    boolean tableVacuumStatisticsEnabled();

    @WithDefault("1000")
    int tableVacuumStatisticsTupleThreshold();

    @WithDefault("true")
    boolean dbVacuumStatisticsEnabled();

    @WithDefault("true")
    boolean vacuumRunningEnabled();

    @WithDefault("true")
    boolean rsgPerHostEnabled();

    @WithDefault("true")
    boolean extendedLocksEnabled();

    @ConfigMapping(prefix = "app.collectors.per-db")
    interface PerDB {
        @WithDefault("from_db")
        PerDBMode mode();

        @WithDefault("")
        Set<String> dbList();

        @WithDefault("true")
        boolean connectionCacheEnabled();
    }
}
