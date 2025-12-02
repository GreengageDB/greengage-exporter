# Greengage DB Exporter

A Prometheus metrics exporter for Greengage DB. This exporter collects various
database metrics including cluster state, segment health, connections, locks, replication status, and more.

## Features

- **Cluster Monitoring**: Track cluster state and segment health
- **Connection Monitoring**: Monitor active connections and connection pools
- **Lock Monitoring**: Track database locks and blocking queries
- **Replication Monitoring**: Monitor replication lag and segment synchronization
- **Vacuum Statistics**: Track vacuum operations and table health
- **Backup History**: Monitor GPBackup history (when available)
- **Resource Monitoring**: CPU, disk, and memory usage per host
- **Query Performance**: Track active query durations
- **Configurable Collectors**: Enable/disable specific metric collectors
- **Circuit Breaker Pattern**: Fail-fast behavior for database issues
- **Per-Database Metrics**: Collect metrics for specific databases or all databases

## Quick Start

### Prerequisites

- Java 17 or higher
- Greengage DB
- (Optional) GPBackup history database for backup monitoring

### Configuration

All configuration is done via environment variables or the `application.properties` file. See
the [Configuration Reference](#configuration-reference) section below for details.

### Running the Exporter

```bash
# Using environment variables
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
export DB_USER=gpadmin
export DB_PASSWORD=yourpassword

java -jar greengage-exporter-1.0.0.jar
```

The exporter will start on port 8080 by default. Metrics are available at:

```
http://localhost:8080/metrics
```

### Docker Deployment

See the `deploy/` directory for Docker Compose examples with Prometheus and Grafana.

## Configuration Reference

### Application Settings

| Property                      | Environment Variable | Default              | Description         |
|-------------------------------|----------------------|----------------------|---------------------|
| `quarkus.application.name`    | -                    | `greengage-exporter` | Application name    |
| `quarkus.application.version` | -                    | `1.0.0`              | Application version |

### HTTP Server Settings

| Property                | Environment Variable | Default   | Description                        |
|-------------------------|----------------------|-----------|------------------------------------|
| `quarkus.http.port`     | `HTTP_PORT`          | `8080`    | HTTP server port                   |
| `quarkus.http.ssl-port` | `HTTPS_PORT`         | `8443`    | HTTPS server port (if SSL enabled) |
| `quarkus.http.host`     | -                    | `0.0.0.0` | HTTP server bind address           |

### Logging Settings

| Property            | Environment Variable | Default | Description                                        |
|---------------------|----------------------|---------|----------------------------------------------------|
| `quarkus.log.level` | `LOG_LEVEL`          | `INFO`  | Global log level (TRACE, DEBUG, INFO, WARN, ERROR) |

### Database Connection Settings

#### Primary Greengage Database

| Property                                                 | Environment Variable     | Default                                                     | Description                           |
|----------------------------------------------------------|--------------------------|-------------------------------------------------------------|---------------------------------------|
| `quarkus.datasource.jdbc.url`                            | `DB_JDBC_URL`            | `jdbc:postgresql://localhost:5432/postgres?sslmode=disable` | JDBC connection URL for Greengage DB  |
| `quarkus.datasource.username`                            | `DB_USER`                | `gpadmin`                                                   | Database username                     |
| `quarkus.datasource.password`                            | `DB_PASSWORD`            | ``                                                          | Database password                     |
| `quarkus.datasource.jdbc.max-size`                       | `DB_POOL_MAX`            | `5`                                                         | Maximum database connection pool size |
| `quarkus.datasource.jdbc.min-size`                       | `DB_POOL_MIN`            | `1`                                                         | Minimum database connection pool size |
| `quarkus.datasource.jdbc.initial-size`                   | `DB_POOL_INIT`           | `1`                                                         | Initial database connection pool size |
| `quarkus.datasource.jdbc.acquisition-timeout`            | `DB_CONN_TIMEOUT`        | `5S`                                                        | Connection acquisition timeout        |
| `quarkus.datasource.jdbc.idle-removal-interval`          | `DB_IDLE_TIMEOUT`        | `10M`                                                       | Idle connection removal interval      |
| `quarkus.datasource.jdbc.max-lifetime`                   | `DB_MAX_LIFETIME`        | `30M`                                                       | Maximum connection lifetime           |
| `quarkus.datasource.jdbc.background-validation-interval` | `DB_VALIDATION_INTERVAL` | `2M`                                                        | Background validation interval        |

### Scrape Configuration

| Property              | Environment Variable | Default | Description                               |
|-----------------------|----------------------|---------|-------------------------------------------|
| `app.scrape.interval` | `SCRAPE_INTERVAL`    | `15s`   | Interval between metric collection cycles |

**Note**: Duration values support units: `s` (seconds), `m` (minutes), `h` (hours)

### Orchestrator Configuration

The orchestrator manages collector execution, retries, and circuit breaker patterns.

| Property                                       | Environment Variable             | Default | Description                                                             |
|------------------------------------------------|----------------------------------|---------|-------------------------------------------------------------------------|
| `app.orchestrator.scrape-cache-max-age`        | `ORCHESTRATOR_CACHE_MAX_AGE`     | `30s`   | Maximum age of cached scrape results before considered stale            |
| `app.orchestrator.connection-retry-attempts`   | `ORCHESTRATOR_RETRY_ATTEMPTS`    | `3`     | Number of retry attempts for database connection failures               |
| `app.orchestrator.connection-retry-delay`      | `ORCHESTRATOR_RETRY_DELAY`       | `1s`    | Base delay between connection retry attempts (uses exponential backoff) |
| `app.orchestrator.collector-failure-threshold` | `ORCHESTRATOR_FAILURE_THRESHOLD` | `3`     | Number of collector failures before triggering circuit breaker          |
| `app.orchestrator.circuit-breaker-enabled`     | `ORCHESTRATOR_CIRCUIT_BREAKER`   | `true`  | Enable circuit breaker for collector failures                           |

**Circuit Breaker Behavior**: When enabled, the orchestrator will stop executing remaining collectors after the failure
threshold is reached during a scrape cycle. This prevents cascading failures and reduces load on an unhealthy database.

### Metrics Exposition Settings

| Property                                    | Environment Variable | Default    | Description                               |
|---------------------------------------------|----------------------|------------|-------------------------------------------|
| `quarkus.micrometer.export.prometheus.path` | `METRICS_PATH`       | `/metrics` | HTTP path for Prometheus metrics endpoint |

### Collector Settings

Individual metric collectors can be enabled or disabled. All collectors are enabled by default except GPBackup History.

#### Core Collectors

| Property                                     | Environment Variable                    | Default | Description                                                  |
|----------------------------------------------|-----------------------------------------|---------|--------------------------------------------------------------|
| `app.collectors.cluster-state-enabled`       | `COLLECTOR_CLUSTER_STATE_ENABLED`       | `true`  | Collect cluster state metrics (master, segments, status)     |
| `app.collectors.segment-enabled`             | `COLLECTOR_SEGMENT_ENABLED`             | `true`  | Collect segment health and configuration metrics             |
| `app.collectors.connections-enabled`         | `COLLECTOR_CONNECTIONS_ENABLED`         | `true`  | Collect active connection statistics per database            |
| `app.collectors.locks-enabled`               | `COLLECTOR_LOCKS_ENABLED`               | `true`  | Collect database lock information                            |
| `app.collectors.extended-locks-enabled`      | `COLLECTOR_EXTENDED_LOCKS_ENABLED`      | `true`  | Collect extended lock information including blocking queries |
| `app.collectors.database-size-enabled`       | `COLLECTOR_DATABASE_SIZE_ENABLED`       | `true`  | Collect database size metrics                                |
| `app.collectors.replication-monitor-enabled` | `COLLECTOR_REPLICATION_MONITOR_ENABLED` | `true`  | Collect replication lag and segment sync status              |
| `app.collectors.table-health-enabled`        | `COLLECTOR_TABLE_HEALTH_ENABLED`        | `true`  | Collect table bloat and health metrics                       |

#### Host Resource Collectors

| Property                                | Environment Variable               | Default | Description                                     |
|-----------------------------------------|------------------------------------|---------|-------------------------------------------------|
| `app.collectors.spill-per-host-enabled` | `COLLECTOR_SPILL_PER_HOST_ENABLED` | `true`  | Collect query spill statistics per host/segment |
| `app.collectors.disk-per-host-enabled`  | `COLLECTOR_DISK_PER_HOST_ENABLED`  | `true`  | Collect disk usage statistics per host/segment  |
| `app.collectors.rsg-per-host-enabled`   | `COLLECTOR_RSG_PER_HOST_ENABLED`   | `true`  | Collect resource group statistics per host      |

#### Vacuum Collectors

| Property                                                 | Environment Variable                     | Default | Description                                                     |
|----------------------------------------------------------|------------------------------------------|---------|-----------------------------------------------------------------|
| `app.collectors.table-vacuum-statistics-enabled`         | `COLLECTOR_TABLE_VACUUM_STATS_ENABLED`   | `true`  | Collect per-table vacuum statistics                             |
| `app.collectors.table-vacuum-statistics-tuple-threshold` | `COLLECTOR_TABLE_VACUUM_TUPLE_THRESHOLD` | `1000`  | Minimum tuple count threshold for collecting table vacuum stats |
| `app.collectors.db-vacuum-statistics-enabled`            | `COLLECTOR_DB_VACUUM_STATS_ENABLED`      | `true`  | Collect database-level vacuum statistics                        |
| `app.collectors.vacuum-running-enabled`                  | `COLLECTOR_VACUUM_RUNNING_ENABLED`       | `true`  | Collect currently running vacuum operations                     |

#### Query Collectors

| Property                               | Environment Variable                      | Default | Description                           |
|----------------------------------------|-------------------------------------------|---------|---------------------------------------|
| `app.collectors.active-query-duration` | `COLLECTOR_ACTIVE_QUERY_DURATION_ENABLED` | `true`  | Collect active query duration metrics |

#### Backup Collectors

| Property                                   | Environment Variable                  | Default | Description                                                           |
|--------------------------------------------|---------------------------------------|---------|-----------------------------------------------------------------------|
| `app.collectors.gp-backup-history-enabled` | `COLLECTOR_GP_BACKUP_HISTORY_ENABLED` | `false` | Collect GPBackup history metrics (requires GPBackup history database) |

### Per-Database Collection Settings

Control which databases to collect per-database metrics from.

| Property                                         | Environment Variable             | Default | Description                                                               |
|--------------------------------------------------|----------------------------------|---------|---------------------------------------------------------------------------|
| `app.collectors.per-db.mode`                     | `COLLECTOR_PER_DB_MODE`          | `all`   | Mode for per-database collection: `all`, `include`, `exclude`, `none`     |
| `app.collectors.per-db.db-list`                  | `COLLECTOR_PER_DB_LIST`          | `"postgres"`    | Comma-separated list of databases (used with `include` or `exclude` mode) |
| `app.collectors.per-db.connection-cache-enabled` | `COLLECTOR_PER_DB_CACHE_ENABLED` | `true`  | Enable connection caching for per-database collectors                     |

**Per-DB Mode Values**:

- `all`: Collect metrics from all non-system databases
- `include`: Only collect from databases listed in `db-list`
- `exclude`: Collect from all databases except those in `db-list`
- `none`: Disable per-database metric collection

**Example**:

```bash
# Collect only from specific databases
export COLLECTOR_PER_DB_MODE=include
export COLLECTOR_PER_DB_LIST="mydb1,mydb2,mydb3"
```

### GPBackup History Database (Optional)

If GPBackup history monitoring is enabled, configure the SQLite database path:

| Property                                       | Environment Variable | Default                                    | Description                                   |
|------------------------------------------------|----------------------|--------------------------------------------|-----------------------------------------------|
| `quarkus.datasource.gpbackup_history.jdbc.url` | -                    | `jdbc:sqlite:/path/to/gpbackup_history.db` | JDBC URL for GPBackup history SQLite database |

## SSL/TLS Configuration

The Greengage DB Exporter can be configured to use SSL/TLS for both the exporter's HTTP server and the database connections. This section covers secure configuration for production environments.

### Exporter HTTPS Configuration

#### Using the Modern TLS Registry (Quarkus 3.x+)

Quarkus 3.x introduces a modern TLS registry that simplifies SSL configuration. This is the recommended approach:

**Configuration Properties:**

```properties
# TLS Configuration using PKCS12 keystore
quarkus.tls.https.key-store.p12.path=/path/to/server-keystore.p12
quarkus.tls.https.key-store.p12.password=${KEYSTORE_PASSWORD}

# HTTP Configuration
quarkus.http.ssl-port=8443
quarkus.http.insecure-requests=redirect
quarkus.http.tls-configuration-name=https
```

**Environment Variables:**

```bash
export QUARKUS_TLS_HTTPS_KEY_STORE_P12_PATH=/path/to/server-keystore.p12
export KEYSTORE_PASSWORD=your_secure_password
export QUARKUS_HTTP_SSL_PORT=8443
export QUARKUS_HTTP_INSECURE_REQUESTS=redirect
export QUARKUS_HTTP_TLS_CONFIGURATION_NAME=https
```

#### Advanced TLS Options
**Certificate Reload (Hot Reload):**

```properties
# Automatically reload certificates every 30 minutes
quarkus.http.ssl.certificate.reload-period=30M
```

### Database SSL/TLS Configuration

#### JDBC Connection String with SSL

To establish a secure connection to Greengage DB, configure the JDBC URL with SSL parameters:

**Basic SSL Configuration:**

```properties
quarkus.datasource.jdbc.url=jdbc:postgresql://db.example.com:5432/postgres?ssl=true&sslmode=require
```

**Full SSL Configuration with Certificate Verification:**

```properties
quarkus.datasource.jdbc.url=jdbc:postgresql://db.example.com:5432/postgres?ssl=true&sslmode=verify-full&sslrootcert=/path/to/root.crt&sslcert=/path/to/client.crt&sslkey=/path/to/client.key
```

**Environment Variable:**

```bash
export DB_JDBC_URL='jdbc:postgresql://db.example.com:5432/postgres?ssl=true&sslmode=verify-full&sslrootcert=/path/to/root.crt'
```

#### PostgreSQL SSL Modes

| SSL Mode         | Description                                                                                          | Use Case                        |
|------------------|------------------------------------------------------------------------------------------------------|---------------------------------|
| `disable`        | No SSL connection                                                                                    | Development only                |
| `allow`          | Try non-SSL first, then SSL if server requires                                                       | Legacy compatibility            |
| `prefer`         | Try SSL first, then non-SSL (default)                                                                | Basic security                  |
| `require`        | SSL required, but no certificate verification                                                        | Encrypted communication         |
| `verify-ca`      | SSL required with CA verification                                                                    | Verify server authenticity      |
| `verify-full`    | SSL required with full certificate and hostname verification                                         | Maximum security (recommended)  |

**Recommended for Production:** `verify-full`

#### JDBC SSL Parameters

| Parameter       | Description                                              | Example                        |
|-----------------|----------------------------------------------------------|--------------------------------|
| `ssl`           | Enable SSL connection                                    | `ssl=true`                     |
| `sslmode`       | SSL connection mode                                      | `sslmode=verify-full`          |
| `sslrootcert`   | Path to CA certificate file                              | `sslrootcert=/path/to/ca.crt`  |
| `sslcert`       | Path to client certificate file                          | `sslcert=/path/to/client.crt`  |
| `sslkey`        | Path to client private key file                          | `sslkey=/path/to/client.key`   |
| `sslpassword`   | Password for encrypted private key                       | `sslpassword=keypass`          |
| `sslfactory`    | Custom SSL socket factory class                          | `sslfactory=CustomFactory`     |

#### Using Java Truststore (Alternative)

Instead of specifying certificate paths in the JDBC URL, you can use Java's truststore:

```bash
# Add CA certificate to Java truststore
keytool -importcert -alias greengage-ca -file ca.crt -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit

# Run exporter with truststore
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=trustpass \
     -jar greengage-exporter-1.0.0.jar
```

### Complete SSL Configuration Example

#### Example 1: Full HTTPS and Database SSL

**application.properties:**

```properties
# Exporter HTTPS Configuration
quarkus.tls.https.key-store.p12.path=/etc/exporter/certs/server-keystore.p12
quarkus.tls.https.key-store.p12.password=${KEYSTORE_PASSWORD}
quarkus.tls.https.protocols=TLSv1.3,TLSv1.2
quarkus.http.ssl-port=8443
quarkus.http.insecure-requests=redirect
quarkus.http.tls-configuration-name=https

# Database SSL Configuration
quarkus.datasource.jdbc.url=jdbc:postgresql://greengage.internal:5432/postgres?ssl=true&sslmode=verify-full&sslrootcert=/etc/exporter/certs/db-ca.crt&sslcert=/etc/exporter/certs/db-client.crt&sslkey=/etc/exporter/certs/db-client.key
quarkus.datasource.username=exporter
quarkus.datasource.password=${DB_PASSWORD}

# Connection Pool
quarkus.datasource.jdbc.max-size=5
quarkus.datasource.jdbc.min-size=1
```

## Prometheus Configuration

Add the following to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'greengage-exporter'
    static_configs:
      - targets: [ 'localhost:8080' ]
    scrape_interval: 15s
    scrape_timeout: 10s
```

**Important**: The Prometheus scrape interval should match or be slightly longer than `app.scrape.interval` to ensure
fresh metrics are available.

## Grafana Dashboards

Pre-built Grafana dashboards are available in the `deploy/dashboards/` directory:

- **Cluster Overview**: Overall cluster health and status
- **Database Health**: Database sizes, bloat, and vacuum statistics
- **Host Resources**: CPU, disk, memory, and resource groups
- **Query Performance**: Active queries and query duration metrics
- **Replication & Segments**: Replication lag and segment synchronization
- **Backup Monitoring**: GPBackup history and status
- **Exporter Monitoring**: Exporter health and performance metrics

## Building from Source

```bash
cd app
./mvnw clean package
```

## Building native binary with GraalVM

```bash
cd app
./mvnw clean package -Dnative -Dquarkus.native.container-build=true -DskipTests -Dquarkus.native.container-runtime-options=--platform=linux/amd64
```

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

