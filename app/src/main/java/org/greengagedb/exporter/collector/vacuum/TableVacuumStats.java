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
package org.greengagedb.exporter.collector.vacuum;

/**
 * Represents vacuum statistics for a specific table in the database.
 *
 * @param databaseName              The name of the database.
 * @param schemaName                The name of the schema.
 * @param tableName                 The name of the table.
 * @param deadTupleRatio            The ratio of dead tuples to total tuples in the table.
 * @param secondsSinceLastVacuum    The number of seconds since the last manual vacuum operation.
 * @param secondsSinceLastAutovacuum The number of seconds since the last autovacuum operation.
 * @param vacuumCount               The total number of manual vacuum operations performed on the table.
 * @param autovacuumCount           The total number of autovacuum operations performed on the table.
 */
public record TableVacuumStats(
        String databaseName,
        String schemaName,
        String tableName,
        double deadTupleRatio,
        long secondsSinceLastVacuum,
        long secondsSinceLastAutovacuum,
        long vacuumCount,
        long autovacuumCount
) {
}

