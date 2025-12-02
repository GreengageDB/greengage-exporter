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
 * Utility methods for segment status/role/mode conversions
 */
@UtilityClass
public final class SegmentUtils {

    /**
     * Convert segment status to numeric value
     *
     * @param status Status string (u = UP, d = DOWN)
     * @return 1.0 for UP, 0.0 for DOWN
     */
    public static double getStatusValue(String status) {
        if (status == null) {
            return 0.0;
        }

        String lowerStatus = status.toLowerCase();
        return Constants.SEGMENT_STATUS_UP.equals(lowerStatus) ? 1.0 : 0.0;
    }

    /**
     * Convert segment role to numeric value
     *
     * @param role Role string (p = Primary, m = Mirror)
     * @return 1.0 for Primary, 2.0 for Mirror
     */
    public static double getRoleValue(String role) {
        if (role == null) {
            return 2.0;
        }

        String lowerRole = role.toLowerCase();
        return Constants.SEGMENT_ROLE_PRIMARY.equals(lowerRole) ? 1.0 : 2.0;
    }

    /**
     * Convert segment mode to numeric value
     *
     * @param mode Mode string (s = Synchronized, r = Resyncing, c = Change Tracking, n = Not Syncing)
     * @return 1.0 = Synchronized, 2.0 = Resyncing, 3.0 = Change Tracking, 4.0 = Not Syncing
     */
    public static double getModeValue(String mode) {
        if (mode == null) {
            return 4.0;
        }

        String lowerMode = mode.toLowerCase();
        return switch (lowerMode) {
            case Constants.SEGMENT_MODE_SYNCHRONIZED -> 1.0;
            case Constants.SEGMENT_MODE_RESYNCING -> 2.0;
            case Constants.SEGMENT_MODE_CHANGE_TRACKING -> 3.0;
            case Constants.SEGMENT_MODE_NOT_SYNCING -> 4.0;
            default -> 0.0;
        };
    }
}

