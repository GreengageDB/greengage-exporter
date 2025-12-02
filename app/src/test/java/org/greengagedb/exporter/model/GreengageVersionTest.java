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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GreengageVersionTest {

    @Test
    void testParseVersion() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 6.26.35_arenadata53 build 2625.gitac00af7.el7) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(6, version.major());
        assertEquals(26, version.minor());
        assertEquals(35, version.patch());
        assertEquals("6.26.35", version.fullVersion());
    }

    @Test
    void testParseVersion7() {
        String versionString = "PostgreSQL 12.22 (Greengage Database 7.3.0+dev.840.g53480a5ef6 build 240+git53480a5) " +
                "on x86_64-pc-linux-gnu, compiled by gcc (Ubuntu 11.4.0-1ubuntu1~22.04.2) 11.4.0, 64-bit compiled " +
                "on Oct  7 2025 20:21:20 Bhuvnesh C.";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(7, version.major());
        assertTrue(version.isAtLeastVersion7());
    }

    @Test
    void testParseGreenplumVersion7() {
        String versionString = "PostgreSQL 9.4.26 (Greenplum Database 7.0.0_arenadata53 build 2625.gitac00af7.el7) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(7, version.major());
        assertTrue(version.isAtLeastVersion7());
    }

    @Test
    void testParseInvalidVersion() {
        String versionString = "PostgreSQL 14.0";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNull(version);
    }

    @Test
    void testParseNullVersion() {
        assertNull(GreengageVersion.parse(null));
    }

    @Test
    void testParseEmptyVersion() {
        GreengageVersion version = GreengageVersion.parse("");
        assertNull(version);
    }

    @Test
    void testIsSupported_Version6() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 6.0.0 build 1) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertTrue(version.isSupported(), "Version 6.0.0 should be supported (minimum)");
    }

    @Test
    void testIsSupported_Version5_NotSupported() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 5.99.99 build 1) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertFalse(version.isSupported(), "Version 5.x should not be supported");
    }

    @Test
    void testIsSupported_Version7() {
        String versionString = "PostgreSQL 12.22 (Greengage Database 7.0.0 build 1) on x86_64-pc-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertTrue(version.isSupported(), "Version 7.0.0 should be supported");
    }

    @Test
    void testIsAtLeastVersion7_Version6_ReturnsFalse() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 6.99.99 build 1) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertFalse(version.isAtLeastVersion7(), "Version 6.x should not be at least version 7");
    }

    @Test
    void testIsAtLeastVersion7_Version8_ReturnsTrue() {
        String versionString = "PostgreSQL 12.22 (Greengage Database 8.0.0 build 1) on x86_64-pc-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertTrue(version.isAtLeastVersion7(), "Version 8.x should be at least version 7");
    }

    @Test
    void testRawVersion_PreservedInRecord() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 6.26.35_arenadata53 build 2625.gitac00af7.el7) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(versionString, version.rawVersion(), "Raw version string should be preserved");
    }

    @Test
    void testFullVersion_Format() {
        String versionString = "PostgreSQL 12.22 (Greengage Database 7.3.15 build 240) on x86_64-pc-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals("7.3.15", version.fullVersion());
    }

    @Test
    void testParseVersion_WithPlus() {
        // Test with + in version string
        String versionString = "PostgreSQL 12.22 (Greengage Database 7.3.0+dev.840 build 240) on x86_64-pc-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(7, version.major());
        assertEquals(3, version.minor());
        assertEquals(0, version.patch());
    }

    @Test
    void testParseVersion_WhitespaceString() {
        GreengageVersion version = GreengageVersion.parse("   ");
        assertNull(version, "Whitespace-only string should return null");
    }

    @Test
    void testParseVersion_EdgeCase_SingleDigitComponents() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 6.0.1 build 1) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(6, version.major());
        assertEquals(0, version.minor());
        assertEquals(1, version.patch());
    }

    @Test
    void testParseVersion_EdgeCase_MultiDigitComponents() {
        String versionString = "PostgreSQL 9.4.26 (Greengage Database 10.25.99 build 1) on x86_64-unknown-linux-gnu";
        GreengageVersion version = GreengageVersion.parse(versionString);

        assertNotNull(version);
        assertEquals(10, version.major());
        assertEquals(25, version.minor());
        assertEquals(99, version.patch());
    }
}

