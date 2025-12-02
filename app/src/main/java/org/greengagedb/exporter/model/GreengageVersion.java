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
package org.greengagedb.exporter.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Greengage database version
 */
public record GreengageVersion(int major, int minor, int patch, String rawVersion) {
    private static final int MINIMUM_SUPPORTED_VERSION = 6;
    private static final Pattern GG_VERSION_REGEX = Pattern.compile(
            "\\([^)]*?\\b((\\d+)\\.(\\d+)\\.(\\d+)(?:[_-|+][A-Za-z0-9.]+)?)\\b\\s+build\\b"
    );

    private static final int MAJOR_GROUP = 2;
    private static final int MINOR_GROUP = 3;
    private static final int PATCH_GROUP = 4;

    /**
     * Parse version from PostgreSQL/Greengage version() output
     *
     * @param versionString The version string from SELECT version()
     * @return Parsed version or null if parsing fails
     */
    public static GreengageVersion parse(String versionString) {
        if (versionString == null || versionString.isBlank()) {
            return null;
        }
        final String input = versionString.trim();
        final Matcher matcher = GG_VERSION_REGEX.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        final int major = Integer.parseInt(matcher.group(MAJOR_GROUP));
        final int minor = Integer.parseInt(matcher.group(MINOR_GROUP));
        final int patch = Integer.parseInt(matcher.group(PATCH_GROUP));
        return new GreengageVersion(major, minor, patch, input);
    }

    public boolean isAtLeastVersion7() {
        return major >= 7;
    }

    public String fullVersion() {
        return major + "." + minor + "." + patch;
    }

    public boolean isSupported() {
        return major >= MINIMUM_SUPPORTED_VERSION;
    }
}

