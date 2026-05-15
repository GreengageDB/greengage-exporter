/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greengagedb.exporter.service;

import io.quarkus.liquibase.LiquibaseFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import liquibase.Liquibase;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@Slf4j
public class LiquibaseMigrationService {

    @Inject
    LiquibaseFactory liquibaseFactory;

    private final AtomicBoolean migrationCompleted = new AtomicBoolean(false);

    public void migrate(boolean force) {
        if (migrationCompleted.get() && !force) {
            return;
        }
        try (Liquibase liquibase = liquibaseFactory.createLiquibase()) {
            log.info("Trying to run migration");
            liquibase.validate();
            liquibase.update(
                    liquibaseFactory.createContexts(),
                    liquibaseFactory.createLabels()
            );
            migrationCompleted.set(true);
            log.info("Migration completed successfully");
        } catch (Exception e) {
            log.warn("Migration failed. Will retry later: {}", e.getMessage(), e);
        }
    }
}
