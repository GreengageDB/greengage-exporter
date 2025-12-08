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

import jakarta.enterprise.inject.Instance;
import org.greengagedb.exporter.config.CollectorsConfig;
import org.greengagedb.exporter.config.OrchestratorConfig;
import org.greengagedb.exporter.config.PerDBMode;
import org.greengagedb.exporter.connection.DbConnectionProvider;
import org.greengagedb.exporter.gg.DatabaseService;
import org.greengagedb.exporter.metrics.ExporterMetrics;
import org.greengagedb.exporter.model.GreengageVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectorOrchestratorTest {

    private CollectorOrchestrator orchestrator;

    @Mock
    private OrchestratorConfig orchestratorConfig;

    @Mock
    private CollectorsConfig collectorsConfig;

    @Mock
    private CollectorsConfig.PerDB perDBConfig;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private ExporterMetrics exporterMetrics;

    @Mock
    private Instance<Collector> collectorsInstance;

    @Mock
    private DbConnectionProvider connectionProvider;

    @Mock
    private Connection mockConnection;

    @Mock
    private Collector generalCollector1;

    @Mock
    private Collector generalCollector2;

    @Mock
    private Collector perDbCollector;

    @Mock
    private DataSource dataSource1;

    private GreengageVersion testVersion;

    @BeforeEach
    void setUp() {
        testVersion = new GreengageVersion(6, 4, 0, "Greengage 6.4.0");

        // Setup default orchestrator config (lenient to avoid UnnecessaryStubbingException)
        lenient().when(orchestratorConfig.scrapeCacheMaxAge()).thenReturn(Duration.ofSeconds(30));
        lenient().when(orchestratorConfig.connectionRetryAttempts()).thenReturn(3);
        lenient().when(orchestratorConfig.connectionRetryDelay()).thenReturn(Duration.ofSeconds(1));
        lenient().when(orchestratorConfig.scrapeFailureThreshold()).thenReturn(3);
        lenient().when(orchestratorConfig.scrapeFailFastEnabled()).thenReturn(true);

        // Setup collectors config
        lenient().when(collectorsConfig.perDB()).thenReturn(perDBConfig);
        lenient().when(perDBConfig.mode()).thenReturn(PerDBMode.NONE);
    }

    private void initializeOrchestrator(List<Collector> collectors) {
        // Mock the Instance<Collector> to return our test collectors (lenient to avoid UnnecessaryStubbingException)
        lenient().when(collectorsInstance.iterator()).thenReturn(collectors.iterator());
        lenient().when(collectorsInstance.spliterator()).thenReturn(collectors.spliterator());

        orchestrator = new CollectorOrchestrator(
                orchestratorConfig,
                collectorsConfig,
                databaseService,
                exporterMetrics,
                collectorsInstance,
                connectionProvider
        );
    }

    @Test
    void testConstructor_InitializesEnabledCollectors() {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(generalCollector2.isEnabled()).thenReturn(false);
        when(generalCollector2.getName()).thenReturn("collector2");

        List<Collector> collectors = List.of(generalCollector1, generalCollector2);

        // Execute
        initializeOrchestrator(collectors);

        // Verify - only enabled collector is logged
        verify(generalCollector1).isEnabled();
        verify(generalCollector2).isEnabled();
    }

    @Test
    void testConstructor_HandlesPerDBModeNone() {
        // Setup - PER_DB collector when mode is NONE should be treated as GENERAL
        when(perDbCollector.isEnabled()).thenReturn(true);
        when(perDbCollector.getName()).thenReturn("perDbCollector");
        when(perDbCollector.getGroup()).thenReturn(CollectorGroup.PER_DB);
        when(perDBConfig.mode()).thenReturn(PerDBMode.NONE);

        List<Collector> collectors = List.of(perDbCollector);

        // Execute
        initializeOrchestrator(collectors);

        // Verify - collector is enabled despite PER_DB mode being NONE
        verify(perDbCollector).isEnabled();
    }

    @Test
    void testScrape_SuccessfulScrape() throws Exception {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // Execute
        orchestrator.scrape();

        // Verify
        verify(databaseService).testConnection();
        verify(databaseService).detectVersion();
        verify(exporterMetrics).setGreengageUp(true);
        verify(exporterMetrics).incrementTotalScraped();
        verify(generalCollector1).collect(mockConnection, testVersion);
        verify(exporterMetrics).recordScrapeDuration(any(Duration.class));
    }

    @Test
    void testScrape_DatabaseConnectionFailure_RetriesAndFails() throws Exception {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(false);

        // Execute
        orchestrator.scrape();

        // Verify - should retry 3 times
        verify(databaseService, times(3)).testConnection();
        verify(exporterMetrics).setGreengageUp(false);
        verify(exporterMetrics, times(1)).incrementTotalError();
        verify(generalCollector1, never()).collect(any(), any());
    }

    @Test
    void testScrape_DatabaseConnectionSucceedsAfterRetry() throws Exception {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1);
        initializeOrchestrator(collectors);

        // Fail first two attempts, succeed on third
        when(databaseService.testConnection()).thenReturn(false, false, true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // Execute
        orchestrator.scrape();

        // Verify
        verify(databaseService, times(3)).testConnection();
        verify(exporterMetrics).setGreengageUp(true);
        verify(generalCollector1).collect(mockConnection, testVersion);
    }

    @Test
    void testScrape_VersionDetectionFails() throws Exception {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(null); // Version detection fails

        // Execute
        orchestrator.scrape();

        // Verify
        verify(exporterMetrics).setGreengageUp(false);
        verify(exporterMetrics, times(1)).incrementTotalError();
        verify(generalCollector1, never()).collect(any(), any());
    }

    @Test
    void testScrape_CollectorFailure_ContinuesWithOthers() throws Exception {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(generalCollector2.isEnabled()).thenReturn(true);
        when(generalCollector2.getName()).thenReturn("collector2");
        when(generalCollector2.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1, generalCollector2);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // First collector fails
        doThrow(new SQLException("Collector failed")).when(generalCollector1).collect(any(), any());

        // Execute
        orchestrator.scrape();

        // Verify - both collectors attempted
        verify(generalCollector1).collect(mockConnection, testVersion);
        verify(generalCollector2).collect(mockConnection, testVersion);
        verify(exporterMetrics).incrementTotalError();
    }

    @Test
    void testScrape_FailFastTriggered() throws Exception {
        // Setup - create 4 collectors (more than threshold of 3)
        Collector collector3 = mock(Collector.class);
        Collector collector4 = mock(Collector.class);

        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(generalCollector2.isEnabled()).thenReturn(true);
        when(generalCollector2.getName()).thenReturn("collector2");
        when(generalCollector2.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(collector3.isEnabled()).thenReturn(true);
        when(collector3.getName()).thenReturn("collector3");
        when(collector3.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(collector4.isEnabled()).thenReturn(true);
        when(collector4.getName()).thenReturn("collector4");
        when(collector4.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1, generalCollector2, collector3, collector4);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // First 3 collectors fail (reaching threshold)
        doThrow(new SQLException("Collector failed")).when(generalCollector1).collect(any(), any());
        doThrow(new SQLException("Collector failed")).when(generalCollector2).collect(any(), any());
        doThrow(new SQLException("Collector failed")).when(collector3).collect(any(), any());

        // Execute
        orchestrator.scrape();

        // Verify - fail-fast triggered, 4th collector not executed
        verify(generalCollector1).collect(any(), any());
        verify(generalCollector2).collect(any(), any());
        verify(collector3).collect(any(), any());
        verify(collector4, never()).collect(any(), any()); // Fail-fast stopped execution
        verify(exporterMetrics, atLeast(3)).incrementTotalError();
    }

    @Test
    void testScrape_FailFastDisabled_AllCollectorsExecute() throws Exception {
        // Setup
        when(orchestratorConfig.scrapeFailFastEnabled()).thenReturn(false); // Disable fail-fast

        Collector collector3 = mock(Collector.class);
        Collector collector4 = mock(Collector.class);

        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(generalCollector2.isEnabled()).thenReturn(true);
        when(generalCollector2.getName()).thenReturn("collector2");
        when(generalCollector2.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(collector3.isEnabled()).thenReturn(true);
        when(collector3.getName()).thenReturn("collector3");
        when(collector3.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(collector4.isEnabled()).thenReturn(true);
        when(collector4.getName()).thenReturn("collector4");
        when(collector4.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1, generalCollector2, collector3, collector4);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // All collectors fail
        doThrow(new SQLException("Collector failed")).when(generalCollector1).collect(any(), any());
        doThrow(new SQLException("Collector failed")).when(generalCollector2).collect(any(), any());
        doThrow(new SQLException("Collector failed")).when(collector3).collect(any(), any());
        doThrow(new SQLException("Collector failed")).when(collector4).collect(any(), any());

        // Execute
        orchestrator.scrape();

        // Verify - all collectors executed despite failures
        verify(generalCollector1).collect(any(), any());
        verify(generalCollector2).collect(any(), any());
        verify(collector3).collect(any(), any());
        verify(collector4).collect(any(), any());
    }

    @Test
    void testScrape_PerDatabaseCollectors() throws Exception {
        // Setup
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);

        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("generalCollector");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(perDbCollector.isEnabled()).thenReturn(true);
        when(perDbCollector.getName()).thenReturn("perDbCollector");
        when(perDbCollector.getGroup()).thenReturn(CollectorGroup.PER_DB);

        List<Collector> collectors = List.of(generalCollector1, perDbCollector);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        Connection dbConnection = mock(Connection.class);
        when(dbConnection.getCatalog()).thenReturn("testdb");
        when(dataSource1.getConnection()).thenReturn(dbConnection);
        when(connectionProvider.getDataSources(mockConnection)).thenReturn(List.of(dataSource1));

        // Execute
        orchestrator.scrape();

        // Verify
        verify(generalCollector1).collect(mockConnection, testVersion); // General on main connection
        verify(perDbCollector).collect(dbConnection, testVersion); // PER_DB on database connection
        verify(connectionProvider).cleanup(); // Cleanup called
    }

    @Test
    void testScrape_PerDatabaseCollectors_CleanupOnFailFast() throws Exception {
        // Setup
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);

        when(perDbCollector.isEnabled()).thenReturn(true);
        when(perDbCollector.getName()).thenReturn("perDbCollector");
        when(perDbCollector.getGroup()).thenReturn(CollectorGroup.PER_DB);

        List<Collector> collectors = List.of(perDbCollector);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        Connection dbConnection = mock(Connection.class);
        when(dbConnection.getCatalog()).thenReturn("testdb");
        when(dataSource1.getConnection()).thenReturn(dbConnection);
        when(connectionProvider.getDataSources(mockConnection)).thenReturn(List.of(dataSource1));

        // Collector fails
        doThrow(new SQLException("Collector failed")).when(perDbCollector).collect(any(), any());

        // Execute
        orchestrator.scrape();

        // Verify - cleanup called even though collector failed
        verify(connectionProvider).cleanup();
    }

    @Test
    void testGetActiveCollectorCount() {
        // Setup
        when(generalCollector1.isEnabled()).thenReturn(true);
        when(generalCollector1.getName()).thenReturn("collector1");
        when(generalCollector1.getGroup()).thenReturn(CollectorGroup.GENERAL);

        when(generalCollector2.isEnabled()).thenReturn(true);
        when(generalCollector2.getName()).thenReturn("collector2");
        when(generalCollector2.getGroup()).thenReturn(CollectorGroup.GENERAL);

        List<Collector> collectors = List.of(generalCollector1, generalCollector2);
        initializeOrchestrator(collectors);

        // Execute
        int count = orchestrator.getActiveCollectorCount();

        // Verify
        assertEquals(1, count); // Only one collector group (GENERAL)
    }

    @Test
    void testScrape_ConnectionProviderThrowsException() throws Exception {
        // Setup
        when(perDBConfig.mode()).thenReturn(PerDBMode.ALL);

        when(perDbCollector.isEnabled()).thenReturn(true);
        when(perDbCollector.getName()).thenReturn("perDbCollector");
        when(perDbCollector.getGroup()).thenReturn(CollectorGroup.PER_DB);

        List<Collector> collectors = List.of(perDbCollector);
        initializeOrchestrator(collectors);

        when(databaseService.testConnection()).thenReturn(true);
        when(databaseService.detectVersion()).thenReturn(testVersion);
        when(databaseService.getPoolledConnection()).thenReturn(mockConnection);

        // ConnectionProvider throws exception
        when(connectionProvider.getDataSources(mockConnection))
                .thenThrow(new RuntimeException("Connection provider error"));

        // Execute
        orchestrator.scrape();

        // Verify - error handled gracefully
        verify(exporterMetrics).incrementTotalError();
    }
}

