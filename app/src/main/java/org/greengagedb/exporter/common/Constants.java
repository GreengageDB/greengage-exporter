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
 * Global constants for Greengage exporter metrics
 */
@UtilityClass
public final class Constants {
    public static final String NAMESPACE = "greengage";
    public static final String SUBSYSTEM_SERVER = "server";
    public static final String SUBSYSTEM_DATABASE = "database";
    public static final String SUBSYSTEM_EXPORTER = "exporter";
    public static final String SUBSYSTEM_CLUSTER = "cluster";
    public static final String SUBSYSTEM_HOST = "host";
    public static final String SUBSYSTEM_QUERY = "query";
    public static final String SUBSYSTEM_GPBACKUP = "gpbackup";
    public static final String SEGMENT_STATUS_UP = "u";
    public static final String SEGMENT_STATUS_DOWN = "d";
    public static final String SEGMENT_ROLE_PRIMARY = "p";
    public static final String SEGMENT_MODE_SYNCHRONIZED = "s";
    public static final String SEGMENT_MODE_RESYNCING = "r";
    public static final String SEGMENT_MODE_CHANGE_TRACKING = "c";
    public static final String SEGMENT_MODE_NOT_SYNCING = "n";
}

