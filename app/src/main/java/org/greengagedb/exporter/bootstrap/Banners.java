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
package org.greengagedb.exporter.bootstrap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Handles printing startup and shutdown banners to the log.
 *
 * <p>Separates presentation logic from application lifecycle logic,
 * making both easier to test and maintain.
 */
@Slf4j
@ApplicationScoped
public class Banners {

    private final int width;

    @Inject
    public Banners(@ConfigProperty(name = "app.banner.width", defaultValue = "60") int width) {
        this.width = width;
    }

    /**
     * Print the startup banner header.
     */
    public void printHeader() {
        String line = "=".repeat(width);
        log.info(line);
        log.info(centerText("Greengage Exporter Starting"));
        log.info(line);
    }

    /**
     * Print the startup banner footer with endpoint information.
     */
    public void printFooter() {
        String line = "=".repeat(width);
        log.info(line);
        log.info(centerText("Exporter Started Successfully"));
        log.info("");
        log.info("  Metrics endpoint:     /metrics or /q/metrics");
        log.info("  Health check:         /q/health");
        log.info("  Ready check:          /q/health/ready");
        log.info("  Live check:           /q/health/live");
        log.info("");
        log.info(line);
    }

    /**
     * Center text within the banner width.
     *
     * @param text Text to center
     * @return Centered text with padding
     */
    private String centerText(String text) {
        int padding = (width - text.length()) / 2;
        if (padding <= 0) {
            return text;
        }
        return " ".repeat(padding) + text;
    }
}

