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
import io.quarkus.scheduler.Scheduled;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.greengagedb.exporter.config.DatasourceConfig;
import org.greengagedb.exporter.model.ClusterRole;
import org.greengagedb.exporter.model.DatabaseClusterState;
import org.greengagedb.exporter.model.GreengageVersion;
import org.greengagedb.exporter.service.BashExecutorService;
import org.greengagedb.exporter.service.LiquibaseMigrationService;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.greengagedb.exporter.model.ClusterRole.DISPATCHER;
import static org.greengagedb.exporter.model.ClusterRole.STANDBY;
import static org.greengagedb.exporter.model.DatabaseClusterState.*;

/**
 * Service for database operations with fault tolerance
 */
@Slf4j
@ApplicationScoped
public class DatabaseService {
    private static final String DATABASE_CLUSTER_STATE_UTILITY_PATH_TEMPLATE = "%s/pg_controldata";
    private static final String DATABASE_CLUSTER_STATE_PROPERTY_NAME = "Database cluster state";
    private static final String DATABASE_CLUSTER_STATE_COMMAND_TEMPLATE = "%s %s | grep '%s'";
    private final AgroalDataSource dataSource;
    private final BashExecutorService bashExecutorService;
    private final LiquibaseMigrationService migrationService;
    private final DatasourceConfig datasourceConfig;
    private final AtomicReference<GreengageVersion> cachedVersionRef = new AtomicReference<>();
    private final AtomicReference<ClusterRole> cachedRoleRef = new AtomicReference<>();

    @Inject
    public DatabaseService(AgroalDataSource dataSource,
                           BashExecutorService bashExecutorService,
                           LiquibaseMigrationService migrationService,
                           DatasourceConfig datasourceConfig) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.bashExecutorService = Objects.requireNonNull(bashExecutorService, "bashExecutorService");
        this.migrationService = migrationService;
        this.datasourceConfig = datasourceConfig;
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

    @Timeout(value = 60, unit = ChronoUnit.SECONDS)
    public boolean isDispatcher() {
        try {
            ClusterRole role = cachedRoleRef.get();
            if (role == null) {
                role = updateClusterRole();
            }
            return role == DISPATCHER;
        } catch (Exception e) {
            log.error("Failed to check cluster role: {}", e.getMessage(), e);
            throw e;
        }
    }

    private synchronized ClusterRole updateClusterRole() {
        ClusterRole previousRole = cachedRoleRef.get();
        ClusterRole currentRole = getClusterRole();
        if (previousRole == null) {
            log.info("Detected initial cluster role: {}", currentRole);
        } else if (previousRole != currentRole) {
            log.info("Cluster role changed: {} -> {}", previousRole, currentRole);
        }
        if (currentRole == DISPATCHER) {
            // Force to migrate at first time and if the role has been changed
            boolean forceMigration = previousRole != currentRole;
            migrationService.migrate(forceMigration);
        }
        cachedRoleRef.set(currentRole);
        return currentRole;
    }

    private ClusterRole getClusterRole() {
        String command = DATABASE_CLUSTER_STATE_UTILITY_PATH_TEMPLATE.formatted(datasourceConfig.binPath());
        try {
            bashExecutorService.checkCommandExists(command);
        } catch (Exception e) {
            throw new RuntimeException("Check that the correct value is set for the app.datasource.bin-path property" +
                    " or the DATASOURCE_BIN_PATH variable. " + e.getMessage(), e);
        }
        String clusterStateValue = bashExecutorService.run(DATABASE_CLUSTER_STATE_COMMAND_TEMPLATE
                        .formatted(command, datasourceConfig.masterDataDirectory(), DATABASE_CLUSTER_STATE_PROPERTY_NAME))
                .replace(DATABASE_CLUSTER_STATE_PROPERTY_NAME + ":", "")
                .trim();
        Optional<DatabaseClusterState> optionalClusterState = DatabaseClusterState.getByValue(clusterStateValue);
        if (optionalClusterState.isEmpty()) {
            return STANDBY;
        }
        DatabaseClusterState dbClusterState = optionalClusterState.get();
        if (MASTER_STATES.contains(dbClusterState)) {
            return DISPATCHER;
        }
        return STANDBY;
    }

    @Scheduled(every = "${app.datasource.update-role-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduleUpdateCoordinatorRole() {
        log.debug("Update cluster role");
        try {
            updateClusterRole();
        } catch (Exception e) {
            log.error("Failed to update cluster role: {}", e.getMessage(), e);
            // Don't rethrow - we want the scheduler to keep running
        }
    }
}
