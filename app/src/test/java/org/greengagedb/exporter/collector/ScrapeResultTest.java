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
package org.greengagedb.exporter.collector;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ScrapeResultTest {

    @Test
    void testSuccessfulScrapeResult() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        assertNotNull(result);
        assertEquals(now, result.timestamp());
        assertTrue(result.successful());
        assertNull(result.error());
    }

    @Test
    void testFailedScrapeResultWithoutError() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.failed(now);

        assertNotNull(result);
        assertEquals(now, result.timestamp());
        assertFalse(result.successful());
        assertNull(result.error());
    }

    @Test
    void testFailedScrapeResultWithError() {
        Instant now = Instant.now();
        SQLException exception = new SQLException("Connection failed");
        ScrapeResult result = ScrapeResult.failed(now, exception);

        assertNotNull(result);
        assertEquals(now, result.timestamp());
        assertFalse(result.successful());
        assertNotNull(result.error());
        assertEquals(exception, result.error());
        assertEquals("Connection failed", result.error().getMessage());
    }

    @Test
    void testIsStale_WithFreshResult() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        Duration maxAge = Duration.ofSeconds(30);
        assertFalse(result.isStale(maxAge), "Fresh result should not be stale");
    }

    @Test
    void testIsStale_WithOldResult() {
        // Create a result from 2 seconds ago
        Instant twoSecondsAgo = Instant.now().minusSeconds(2);
        ScrapeResult result = ScrapeResult.successful(twoSecondsAgo);

        // Max age is 1 second, so result should be stale
        Duration maxAge = Duration.ofSeconds(1);
        assertTrue(result.isStale(maxAge), "Old result should be stale");
    }

    @Test
    void testIsStale_ExactlyAtMaxAge() throws InterruptedException {
        // Create a result from exactly 1 second ago
        Instant oneSecondAgo = Instant.now().minusSeconds(1);
        ScrapeResult result = ScrapeResult.successful(oneSecondAgo);

        Duration maxAge = Duration.ofSeconds(1);
        // Due to execution time, it should be slightly stale
        // But we test the boundary behavior
        Thread.sleep(10); // Small delay to ensure it crosses the boundary
        assertTrue(result.isStale(maxAge));
    }

    @Test
    void testIsStale_WithZeroMaxAge() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        Duration maxAge = Duration.ZERO;
        // Even a fresh result is stale with zero max age
        assertTrue(result.isStale(maxAge), "Any result should be stale with zero max age");
    }

    @Test
    void testIsStale_WithVeryLargeMaxAge() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        Duration maxAge = Duration.ofDays(365);
        assertFalse(result.isStale(maxAge), "Result should not be stale with very large max age");
    }

    @Test
    void testGetAge_ReturnsPositiveDuration() throws InterruptedException {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        // Small delay to ensure age is measurable
        Thread.sleep(10);

        Duration age = result.getAge();
        assertNotNull(age);
        assertTrue(age.toMillis() >= 0, "Age should be positive");
        assertTrue(age.toMillis() >= 10, "Age should be at least 10ms");
    }

    @Test
    void testGetAge_IncreasesOverTime() throws InterruptedException {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        Duration firstAge = result.getAge();
        Thread.sleep(50);
        Duration secondAge = result.getAge();

        assertTrue(secondAge.compareTo(firstAge) > 0,
                "Age should increase over time");
        assertTrue(secondAge.toMillis() - firstAge.toMillis() >= 40,
                "Age difference should be approximately the sleep duration");
    }

    @Test
    void testGetAge_ForOldResult() {
        Instant tenSecondsAgo = Instant.now().minusSeconds(10);
        ScrapeResult result = ScrapeResult.successful(tenSecondsAgo);

        Duration age = result.getAge();
        assertNotNull(age);
        assertTrue(age.getSeconds() >= 10, "Age should be at least 10 seconds");
        assertTrue(age.getSeconds() < 11, "Age should be less than 11 seconds");
    }

    @Test
    void testRecordSemantics_Equality() {
        Instant now = Instant.now();
        ScrapeResult result1 = ScrapeResult.successful(now);
        ScrapeResult result2 = ScrapeResult.successful(now);

        assertEquals(result1, result2, "Records with same values should be equal");
        assertEquals(result1.hashCode(), result2.hashCode(), "Equal records should have same hash code");
    }

    @Test
    void testRecordSemantics_InequalityByTimestamp() {
        Instant now = Instant.now();
        Instant later = now.plusSeconds(1);

        ScrapeResult result1 = ScrapeResult.successful(now);
        ScrapeResult result2 = ScrapeResult.successful(later);

        assertNotEquals(result1, result2, "Records with different timestamps should not be equal");
    }

    @Test
    void testRecordSemantics_InequalityBySuccess() {
        Instant now = Instant.now();

        ScrapeResult result1 = ScrapeResult.successful(now);
        ScrapeResult result2 = ScrapeResult.failed(now);

        assertNotEquals(result1, result2, "Successful and failed results should not be equal");
    }

    @Test
    void testRecordSemantics_ToString() {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        String toString = result.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("timestamp"));
        assertTrue(toString.contains("successful"));
    }

    @Test
    void testStalenessCalculation_EdgeCase_VeryOldResult() {
        // Simulate a result from 1 hour ago
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        ScrapeResult result = ScrapeResult.successful(oneHourAgo);

        Duration maxAge = Duration.ofSeconds(30);
        assertTrue(result.isStale(maxAge),
                "Very old result should definitely be stale");

        Duration age = result.getAge();
        assertTrue(age.getSeconds() >= 3600,
                "Age should be approximately 1 hour");
    }

    @Test
    void testErrorPreservation_DifferentExceptionTypes() {
        Instant now = Instant.now();

        // Test with different exception types
        RuntimeException runtimeException = new RuntimeException("Runtime error");
        ScrapeResult result1 = ScrapeResult.failed(now, runtimeException);
        assertEquals(runtimeException, result1.error());

        SQLException sqlException = new SQLException("SQL error");
        ScrapeResult result2 = ScrapeResult.failed(now, sqlException);
        assertEquals(sqlException, result2.error());

        IllegalArgumentException illegalArgException = new IllegalArgumentException("Invalid arg");
        ScrapeResult result3 = ScrapeResult.failed(now, illegalArgException);
        assertEquals(illegalArgException, result3.error());
    }

    @Test
    void testIsStale_WithMillisecondPrecision() throws InterruptedException {
        Instant now = Instant.now();
        ScrapeResult result = ScrapeResult.successful(now);

        // Sleep for 500ms
        Thread.sleep(500);

        // Max age is 250ms - should be stale
        assertTrue(result.isStale(Duration.ofMillis(250)));

        // Max age is 1000ms - should not be stale
        assertFalse(result.isStale(Duration.ofMillis(1000)));
    }
}

