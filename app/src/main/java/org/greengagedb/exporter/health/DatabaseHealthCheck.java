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
package org.greengagedb.exporter.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.greengagedb.exporter.gg.DatabaseService;

/**
 * Health check for database connectivity
 * Note: This is a liveness check, not readiness, so it won't kill the pod on DB failure
 */
@Liveness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    @Inject
    DatabaseService databaseService;

    @Override
    public HealthCheckResponse call() {
        boolean connected = databaseService.testConnection();

        return HealthCheckResponse.named("database-connection")
                .status(connected)
                .withData("accessible", connected)
                .build();
    }
}

