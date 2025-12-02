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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ValueUtilsTest {

    @Test
    void testGetOrUnknown_WithNull() {
        String result = ValueUtils.getOrUnknown(null);
        assertEquals("unknown", result, "Null value should return 'unknown'");
    }

    @Test
    void testGetOrUnknown_WithNonNullValue() {
        String input = "test_value";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("test_value", result, "Non-null value should be returned as-is");
    }

    @Test
    void testGetOrUnknown_WithEmptyString() {
        String input = "";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("", result, "Empty string should be returned as-is, not converted to 'unknown'");
    }

    @Test
    void testGetOrUnknown_WithWhitespaceString() {
        String input = "   ";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("   ", result, "Whitespace string should be returned as-is");
    }

    @Test
    void testGetOrUnknown_WithSpecialCharacters() {
        String input = "!@#$%^&*()";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("!@#$%^&*()", result, "Special characters should be preserved");
    }

    @Test
    void testGetOrUnknown_WithNumericString() {
        String input = "12345";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("12345", result, "Numeric strings should be preserved");
    }

    @Test
    void testGetOrUnknown_WithUnicodeCharacters() {
        String input = "测试数据";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals("测试数据", result, "Unicode characters should be preserved");
    }

    @Test
    void testGetOrUnknown_WithVeryLongString() {
        String input = "a".repeat(10000);
        String result = ValueUtils.getOrUnknown(input);
        assertEquals(input, result, "Very long strings should be returned as-is");
        assertEquals(10000, result.length());
    }

    @Test
    void testGetOrUnknown_ReturnsExactValue() {
        String input = "hostname.example.com";
        String result = ValueUtils.getOrUnknown(input);
        assertSame(input, result, "Should return the exact same String object");
    }

    @Test
    void testGetOrUnknown_MultipleNullCalls() {
        // Verify consistent behavior across multiple calls
        String result1 = ValueUtils.getOrUnknown(null);
        String result2 = ValueUtils.getOrUnknown(null);
        String result3 = ValueUtils.getOrUnknown(null);

        assertEquals("unknown", result1);
        assertEquals("unknown", result2);
        assertEquals("unknown", result3);
    }

    @Test
    void testGetOrUnknown_MixedCalls() {
        // Test alternating null and non-null calls
        assertEquals("unknown", ValueUtils.getOrUnknown(null));
        assertEquals("value1", ValueUtils.getOrUnknown("value1"));
        assertEquals("unknown", ValueUtils.getOrUnknown(null));
        assertEquals("value2", ValueUtils.getOrUnknown("value2"));
    }

    @Test
    void testGetOrUnknown_WithNewlineCharacters() {
        String input = "line1\nline2\nline3";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals(input, result, "Newline characters should be preserved");
    }

    @Test
    void testGetOrUnknown_WithTabCharacters() {
        String input = "column1\tcolumn2\tcolumn3";
        String result = ValueUtils.getOrUnknown(input);
        assertEquals(input, result, "Tab characters should be preserved");
    }

    @Test
    void testGetOrUnknown_UsageScenario_DatabaseName() {
        // Simulate real usage with database names
        String dbName = "production_db";
        assertEquals("production_db", ValueUtils.getOrUnknown(dbName));

        String nullDbName = null;
        assertEquals("unknown", ValueUtils.getOrUnknown(nullDbName));
    }

    @Test
    void testGetOrUnknown_UsageScenario_Hostname() {
        // Simulate real usage with hostnames
        String hostname = "server01.datacenter.internal";
        assertEquals("server01.datacenter.internal", ValueUtils.getOrUnknown(hostname));

        String nullHostname = null;
        assertEquals("unknown", ValueUtils.getOrUnknown(nullHostname));
    }
}

