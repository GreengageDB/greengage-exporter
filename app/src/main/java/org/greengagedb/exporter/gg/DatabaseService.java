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
package org.greengagedb.exporter.gg;

import io.agroal.api.AgroalDataSource;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.greengagedb.exporter.model.GreengageVersion;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for database operations with fault tolerance
 */
@Slf4j
@ApplicationScoped
public class DatabaseService {
    private final AgroalDataSource dataSource;
    private final AtomicReference<GreengageVersion> cachedVersionRef = new AtomicReference<>();

    @Inject
    public DatabaseService(AgroalDataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Get the JDBC URL for logging/debugging purposes.
     *
     * @return JDBC URL or "unavailable" if not accessible
     */
    public String getUrl() {
        try {
            return dataSource.getConfiguration().connectionPoolConfiguration()
                    .connectionFactoryConfiguration().jdbcUrl();
        } catch (Exception e) {
            log.debug("Could not retrieve JDBC URL", e);
            return "unavailable";
        }
    }

    /**
     * Get database connection from the pool
     */
    public Connection getPoolledConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Detect and cache Greengage version
     */
    @Retry(delay = 1, delayUnit = ChronoUnit.SECONDS)
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 10, delay = 30, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreakerName("version-detection")
    public GreengageVersion detectVersion() throws SQLException {
        GreengageVersion local = cachedVersionRef.get();
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedVersionRef.get() != null) {
                return cachedVersionRef.get();
            }
            try (Connection conn = getPoolledConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version()")) {
                GreengageVersion parsed = parseVersion(rs);
                if (parsed != null) {
                    cachedVersionRef.set(parsed);
                    return parsed;
                }
            } catch (SQLException e) {
                log.warn("Failed to detect Greengage version (attempt may be retried)", e);
                throw e;
            }
        }
        throw new SQLException("Unable to detect Greengage version");
    }

    /**
     * Test database connectivity
     */
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    public boolean testConnection() {
        try (Connection conn = getPoolledConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            log.debug("Connection test failed", e);
            return false;
        } catch (Exception e) {
            log.warn("Unexpected error during connection test", e);
            return false;
        }
    }

    private GreengageVersion parseVersion(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return null;
        }
        String versionString = rs.getString(1);
        log.info("Detected Greengage version: {}", versionString);
        return GreengageVersion.parse(versionString);
    }
}
