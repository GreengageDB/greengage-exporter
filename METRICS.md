# Greengage DB Exporter - Metrics Reference

This document provides a reference for all metrics exported by the Greengage DB Exporter for Prometheus.

## Table of Contents

- [Metric Naming Convention](#metric-naming-convention)
- [Cluster State & Health Metrics](#cluster-state--health-metrics)
- [Segment Metrics](#segment-metrics)
- [Connection Metrics](#connection-metrics)
- [Lock Metrics](#lock-metrics)
- [Replication Metrics](#replication-metrics)
- [Query Performance Metrics](#query-performance-metrics)
- [Host Resource Metrics](#host-resource-metrics)
- [Vacuum Statistics Metrics](#vacuum-statistics-metrics)
- [Table Health Metrics](#table-health-metrics)
- [Database Size Metrics](#database-size-metrics)
- [Backup Metrics (GPBackup)](#backup-metrics-gpbackup)
- [Exporter Internal Metrics](#exporter-internal-metrics)

---

## Metric Naming Convention

All Greengage DB metrics follow the pattern: `greengage_<subsystem>_<metric_name>`

**Subsystems:**
- `cluster` - Cluster-wide metrics (state, connections, replication, segments)
- `server` - Server/database-level metrics (locks, vacuum, table health)
- `database` - Per-database and per-table metrics (vacuum, bloat)
- `host` - Host-level resource metrics (CPU, disk, memory)
- `query` - Query performance metrics
- `gpbackup` - GPBackup history metrics
- `exporter` - Internal exporter metrics

---

## Cluster State & Health Metrics

### greengage_up

**Type:** Gauge  
**Description:** Whether the Greengage cluster is reachable (1=up, 0=down). This is the primary indicator of exporter health.  
**Possible Values:** `0` (down), `1` (up)  
**Labels:** None

**Example:**
```
greengage_up 1.0
```

**Alerting:** Alert when `greengage_up == 0` for more than 1 minute.

---

### greengage_cluster_state

**Type:** Gauge  
**Description:** Whether the Greengage database cluster is accessible (can query segments). Indicates if the cluster can execute distributed queries.  
**Possible Values:** `0` (not accessible), `1` (accessible)  
**Labels:**
- `version` - Greengage version string (e.g., "7.0.0", "unknown")
- `master` - Master node hostname
- `standby` - Standby node hostname (empty string if no standby)

**Example:**
```
greengage_cluster_state{master="greengage-master-1.internal",standby="",version="7.0.0"} 1.0
```

---

### greengage_cluster_uptime_seconds

**Type:** Gauge  
**Description:** Duration in seconds that the Greengage database has been running since the last restart.  
**Unit:** seconds  
**Labels:** None

**Example:**
```
greengage_cluster_uptime_seconds 33848.306563
```

**Usage:** Calculate uptime: `greengage_cluster_uptime_seconds / 3600 / 24` for days.

---

### greengage_cluster_max_connections

**Type:** Gauge  
**Description:** Maximum number of allowed connections to the Greengage database (from `max_connections` setting).  
**Labels:** None

**Example:**
```
greengage_cluster_max_connections 250.0
```

**Alerting:** Alert when `greengage_cluster_connections_all_states_total / greengage_cluster_max_connections > 0.8` (80% capacity).

---

### greengage_cluster_config_last_load_time_seconds

**Type:** Gauge  
**Description:** Unix timestamp of the last configuration reload via `pg_reload_conf()` or server restart.  
**Unit:** Unix timestamp (seconds since epoch)  
**Labels:** None

**Example:**
```
greengage_cluster_config_last_load_time_seconds 1.764592546603E9
```

**Usage:** Convert to datetime: `FROM_UNIXTIME(greengage_cluster_config_last_load_time_seconds)`.

---

### greengage_cluster_sync

**Type:** Gauge  
**Description:** Number of sync replicas streaming from master. Indicates synchronous replication status.  
**Possible Values:** `0` (no sync replication), `1` or more (sync active)  
**Labels:** None

**Example:**
```
greengage_cluster_sync 0.0
```

---

## Segment Metrics

### greengage_cluster_segment_status

**Type:** Gauge  
**Description:** UP(1) if the segment is running, DOWN(0) if the segment has failed or is unreachable.  
**Possible Values:** `0` (down), `1` (up)  
**Labels:**
- `dbid` - Database ID of the segment
- `content` - Content ID (segment number, `-1` for master/coordinator)
- `hostname` - Hostname where segment is running
- `port` - Port number
- `preferred_role` - Preferred role (`p` for primary, `m` for mirror)
- `role` - Current role (`p` for primary, `m` for mirror)

**Example:**
```
greengage_cluster_segment_status{content="0",dbid="2",hostname="host-1.internal",port="10000",preferred_role="p",role="p"} 1.0
greengage_cluster_segment_status{content="0",dbid="10",hostname="host-2.internal",port="10500",preferred_role="m",role="m"} 1.0
```

**Alerting:** Alert when `greengage_cluster_segment_status == 0`.

---

### greengage_cluster_segment_role

**Type:** Gauge  
**Description:** The segment's current role.  
**Possible Values:** `1` (primary), `2` (mirror)  
**Labels:** Same as `greengage_cluster_segment_status`

**Example:**
```
greengage_cluster_segment_role{content="0",dbid="2",hostname="host-1.internal",port="10000",preferred_role="p",role="p"} 1.0
```

---

### greengage_cluster_segment_mode

**Type:** Gauge  
**Description:** The replication/synchronization status for the segment.  
**Possible Values:**
- `1.0` - Synchronized (healthy)
- `2.0` - Resyncing (recovering)
- `3.0` - Change Tracking (degraded, tracking changes for recovery)
- `4.0` - Not Syncing (no mirror or not replicating)

**Labels:** Same as `greengage_cluster_segment_status`

**Example:**
```
greengage_cluster_segment_mode{content="0",dbid="2",hostname="host-1.internal",port="10000",preferred_role="p",role="p"} 1.0
greengage_cluster_segment_mode{content="-1",dbid="1",hostname="master.internal",port="5432",preferred_role="p",role="p"} 4.0
```

**Alerting:** Alert when `greengage_cluster_segment_mode > 1` (not synchronized).

---

### greengage_cluster_segments_total

**Type:** Gauge  
**Description:** Total number of segments in the cluster (including master and all primaries/mirrors).  
**Labels:** None

**Example:**
```
greengage_cluster_segments_total 17.0
```

---

### greengage_cluster_segments_up

**Type:** Gauge  
**Description:** Number of segments currently in UP status.  
**Labels:** None

**Example:**
```
greengage_cluster_segments_up 17.0
```

---

### greengage_cluster_segments_down

**Type:** Gauge  
**Description:** Number of segments currently in DOWN status.  
**Labels:** None

**Example:**
```
greengage_cluster_segments_down 0.0
```

**Alerting:** Alert when `greengage_cluster_segments_down > 0`.

---

## Connection Metrics

### greengage_cluster_connections_total

**Type:** Gauge  
**Description:** Total number of database connections by state.  
**Labels:**
- `state` - Connection state: `active`, `idle`, `idle in transaction`

**Example:**
```
greengage_cluster_connections_total{state="idle"} 5.0
greengage_cluster_connections_total{state="active"} 2.0
greengage_cluster_connections_total{state="idle in transaction"} 0.0
```

**Usage:**
- Monitor idle connections: `greengage_cluster_connections_total{state="idle"}`
- Monitor stuck transactions: `greengage_cluster_connections_total{state="idle in transaction"}`

---

### greengage_cluster_connections_all_states_total

**Type:** Gauge  
**Description:** Total number of connections across all states (sum of all connection states).  
**Labels:** None

**Example:**
```
greengage_cluster_connections_all_states_total 7.0
```

**Alerting:** Alert when approaching max connections:
```promql
greengage_cluster_connections_all_states_total / greengage_cluster_max_connections > 0.8
```

---

## Lock Metrics

### greengage_cluster_query_waiting_count

**Type:** Gauge  
**Description:** Total number of queries waiting for locks (all lock types).  
**Labels:** None

**Example:**
```
greengage_cluster_query_waiting_count 0.0
```

**Alerting:** Alert when `greengage_cluster_query_waiting_count > 10` for extended periods.

---

### greengage_server_locked_sessions_count

**Type:** Gauge  
**Description:** Number of locked sessions by lock type.  
**Labels:**
- `lock_type` - Type of lock: `relation`, `tuple`, `transactionid`, `virtualxid`, `object`, etc.

**Example:**
```
greengage_server_locked_sessions_count{lock_type="relation"} 3.0
greengage_server_locked_sessions_count{lock_type="tuple"} 1.0
```

---

### greengage_server_locked_sessions_total

**Type:** Gauge  
**Description:** Total number of locked sessions across all lock types.  
**Labels:** None

**Example:**
```
greengage_server_locked_sessions_total 4.0
```

---

## Replication Metrics

### greengage_cluster_replication_lag_bytes

**Type:** Gauge  
**Description:** Replication lag in bytes (replay lag). This is the most important lag metric, representing how far behind the standby/mirror is from the primary in terms of WAL bytes.  
**Unit:** bytes  
**Labels:**
- `application_name` - Replication application name (e.g., `gp_walreceiver`)
- `content` - Segment content ID
- `host` - Primary host serving this replication
- `scope` - Scope: `segment` or `master`

**Example:**
```
greengage_cluster_replication_lag_bytes{application_name="gp_walreceiver",content="0",host="host-1.internal",scope="segment"} 0.0
greengage_cluster_replication_lag_bytes{application_name="gp_walreceiver",content="1",host="host-1.internal",scope="segment"} 2048.0
```

**Alerting:** Alert when lag exceeds threshold:
```promql
greengage_cluster_replication_lag_bytes > 10485760  # 10MB
```

---

### greengage_cluster_replication_write_lag_bytes

**Type:** Gauge  
**Description:** Replication write lag in bytes (data sent but not yet written to disk on standby).  
**Unit:** bytes  
**Labels:** Same as `greengage_cluster_replication_lag_bytes`

**Example:**
```
greengage_cluster_replication_write_lag_bytes{application_name="gp_walreceiver",content="0",host="host-1.internal",scope="segment"} 0.0
```

---

### greengage_cluster_replication_flush_lag_bytes

**Type:** Gauge  
**Description:** Replication flush lag in bytes (data written but not yet flushed to disk on standby).  
**Unit:** bytes  
**Labels:** Same as `greengage_cluster_replication_lag_bytes`

**Example:**
```
greengage_cluster_replication_flush_lag_bytes{application_name="gp_walreceiver",content="0",host="host-1.internal",scope="segment"} 0.0
```

---

### greengage_cluster_replication_max_lag_bytes

**Type:** Gauge  
**Description:** Maximum replication lag in bytes across all segments. Useful for high-level cluster health monitoring.  
**Unit:** bytes  
**Labels:** None

**Example:**
```
greengage_cluster_replication_max_lag_bytes 2048.0
```

**Alerting:** Alert when `greengage_cluster_replication_max_lag_bytes > 10485760` (10MB).

---

### greengage_cluster_replication_state

**Type:** Gauge  
**Description:** Replication state as a numeric value.  
**Possible Values:**
- `0` - unknown
- `1` - streaming (healthy)
- `2` - catchup (recovering)
- `3` - backup (performing backup)

**Labels:** Same as `greengage_cluster_replication_lag_bytes`

**Example:**
```
greengage_cluster_replication_state{application_name="gp_walreceiver",content="0",host="host-1.internal",scope="segment"} 1.0
```

---

### greengage_cluster_replication_sync_state

**Type:** Gauge  
**Description:** Replication synchronization state as a numeric value.  
**Possible Values:**
- `0` - unknown
- `0.5` - potential (could become sync)
- `1` - async (asynchronous replication)
- `2` - sync (synchronous replication)

**Labels:** Same as `greengage_cluster_replication_lag_bytes`

**Example:**
```
greengage_cluster_replication_sync_state{application_name="gp_walreceiver",content="0",host="host-1.internal",scope="segment"} 2.0
```

---

## Query Performance Metrics

### greengage_query_active_queries_total

**Type:** Gauge  
**Description:** Total number of active queries across all duration buckets (excluding autovacuum).  
**Labels:** None

**Example:**
```
greengage_query_active_queries_total 5.0
```

---

### greengage_query_active_queries_slow

**Type:** Gauge  
**Description:** Number of slow active queries with duration greater than 180 seconds (3 minutes).  
**Labels:** None

**Example:**
```
greengage_query_active_queries_slow 2.0
```

**Alerting:** Alert when `greengage_query_active_queries_slow > 5`.

---

### greengage_query_active_queries_duration_bucket

**Type:** Gauge  
**Description:** Number of active queries in specific duration buckets. Helps identify query performance patterns.  
**Labels:**
- `bucket` - Duration bucket:
  - `0_10` - 0-10 seconds
  - `10_60` - 10-60 seconds (10s-1min)
  - `60_180` - 60-180 seconds (1-3 minutes)
  - `180_600` - 180-600 seconds (3-10 minutes)
  - `600_plus` - 600+ seconds (10+ minutes)

**Example:**
```
greengage_query_active_queries_duration_bucket{bucket="0_10"} 10.0
greengage_query_active_queries_duration_bucket{bucket="10_60"} 3.0
greengage_query_active_queries_duration_bucket{bucket="60_180"} 1.0
greengage_query_active_queries_duration_bucket{bucket="180_600"} 0.0
greengage_query_active_queries_duration_bucket{bucket="600_plus"} 0.0
```

**Usage:** Visualize query distribution in Grafana using stacked graphs.

---

## Host Resource Metrics

### greengage_host_disk_total_kb

**Type:** Gauge  
**Description:** Total disk space in kilobytes per host.  
**Unit:** kilobytes  
**Labels:**
- `hostname` - Hostname

**Example:**
```
greengage_host_disk_total_kb{hostname="host-1.internal"} 1.00941264E8
```

---

### greengage_host_disk_used_kb

**Type:** Gauge  
**Description:** Used disk space in kilobytes per host.  
**Unit:** kilobytes  
**Labels:**
- `hostname` - Hostname

**Example:**
```
greengage_host_disk_used_kb{hostname="host-1.internal"} 1.8798436E7
```

---

### greengage_host_disk_available_kb

**Type:** Gauge  
**Description:** Available disk space in kilobytes per host.  
**Unit:** kilobytes  
**Labels:**
- `hostname` - Hostname

**Example:**
```
greengage_host_disk_available_kb{hostname="host-1.internal"} 8.2126444E7
```

**Alerting:** Alert when available space is low:
```promql
greengage_host_disk_available_kb < 10485760  # Less than 10GB
```

---

### greengage_host_disk_usage_percent

**Type:** Gauge  
**Description:** Disk usage percentage per host.  
**Unit:** percent (0-100)  
**Labels:**
- `hostname` - Hostname

**Example:**
```
greengage_host_disk_usage_percent{hostname="host-1.internal"} 19.0
```

**Alerting:** Alert when `greengage_host_disk_usage_percent > 85`.

---

### greengage_host_max_disk_total_kb
### greengage_host_avg_disk_total_kb

**Type:** Gauge  
**Description:** Maximum/average disk total KB across all hosts.  
**Unit:** kilobytes  
**Labels:** None

**Example:**
```
greengage_host_max_disk_total_kb 1.00941264E8
greengage_host_avg_disk_total_kb 1.00941264E8
```

---

### greengage_host_max_disk_used_kb
### greengage_host_avg_disk_used_kb

**Type:** Gauge  
**Description:** Maximum/average disk used KB across all hosts.  
**Unit:** kilobytes  
**Labels:** None

**Example:**
```
greengage_host_max_disk_used_kb 1.8798436E7
greengage_host_avg_disk_used_kb 1.8761324E7
```

---

### greengage_host_max_disk_available_kb
### greengage_host_avg_disk_available_kb

**Type:** Gauge  
**Description:** Maximum/average disk available KB across all hosts.  
**Unit:** kilobytes  
**Labels:** None

**Example:**
```
greengage_host_max_disk_available_kb 8.2200668E7
greengage_host_avg_disk_available_kb 8.2163556E7
```

---

### greengage_host_max_disk_usage_percent
### greengage_host_avg_disk_usage_percent

**Type:** Gauge  
**Description:** Maximum/average disk usage percent across all hosts.  
**Unit:** percent (0-100)  
**Labels:** None

**Example:**
```
greengage_host_max_disk_usage_percent 19.0
greengage_host_avg_disk_usage_percent 19.0
```

---

### greengage_host_disk_total_kb_skew_ratio
### greengage_host_disk_used_kb_skew_ratio
### greengage_host_disk_available_kb_skew_ratio
### greengage_host_disk_usage_percent_skew_ratio

**Type:** Gauge  
**Description:** Skew ratio for disk metrics across hosts. Ratio of maximum value to average value. A value close to 1.0 indicates balanced distribution, higher values indicate skew.  
**Possible Values:** `≥ 1.0` (1.0 = perfect balance, higher = more skew)  
**Labels:** None

**Example:**
```
greengage_host_disk_total_kb_skew_ratio 1.0
greengage_host_disk_used_kb_skew_ratio 1.002
greengage_host_disk_available_kb_skew_ratio 1.001
greengage_host_disk_usage_percent_skew_ratio 1.0
```

**Alerting:** Alert when skew > 1.5 (significant imbalance).

---

### greengage_host_cpu_usage_percentage

**Type:** Gauge  
**Description:** CPU usage percentage per host and resource group.  
**Unit:** percent (0-100)  
**Labels:**
- `hostname` - Hostname
- `resourceGroupName` - Resource group name (e.g., `default_group`, `admin_group`, `system_group`)
- `limit` - CPU rate limit for the resource group

**Example:**
```
greengage_host_cpu_usage_percentage{hostname="host-1.internal",limit="20",resourceGroupName="default_group"} 15.0
greengage_host_cpu_usage_percentage{hostname="host-1.internal",limit="10",resourceGroupName="admin_group"} 2.0
```

---

### greengage_host_cpu_rate_limit_percentage

**Type:** Gauge  
**Description:** CPU rate limit percentage per resource group.  
**Unit:** percent (0-100)  
**Labels:**
- `resourceGroupName` - Resource group name

**Example:**
```
greengage_host_cpu_rate_limit_percentage{resourceGroupName="default_group"} 20.0
greengage_host_cpu_rate_limit_percentage{resourceGroupName="admin_group"} 10.0
```

---

### greengage_host_max_cpu_usage
### greengage_host_avg_cpu_usage

**Type:** Gauge  
**Description:** Maximum/average CPU usage percentage across all hosts.  
**Unit:** percent (0-100)  
**Labels:** None

**Example:**
```
greengage_host_max_cpu_usage 15.0
greengage_host_avg_cpu_usage 12.5
```

---

### greengage_host_cpu_usage_skew_ratio

**Type:** Gauge  
**Description:** CPU usage skew ratio across all hosts. Indicates how evenly CPU usage is distributed.  
**Possible Values:** `≥ 0.0` (0.0 = all idle, 1.0 = perfect balance, higher = more skew)  
**Labels:** None

**Example:**
```
greengage_host_cpu_usage_skew_ratio 1.2
```

---

### greengage_host_mem_usage_mb

**Type:** Gauge  
**Description:** Memory usage in megabytes per host and resource group.  
**Unit:** megabytes  
**Labels:**
- `hostname` - Hostname
- `resourceGroupName` - Resource group name
- `limit` - Memory limit for the resource group (`∞` for unlimited, or numeric value)

**Example:**
```
greengage_host_mem_usage_mb{hostname="host-1.internal",limit="∞",resourceGroupName="default_group"} 4217.0
greengage_host_mem_usage_mb{hostname="host-1.internal",limit="∞",resourceGroupName="admin_group"} 161.0
```

---

### greengage_host_mem_limit_mb

**Type:** Gauge  
**Description:** Memory limit in megabytes per resource group.  
**Unit:** megabytes  
**Possible Values:** `-1` for unlimited, or positive value for limit  
**Labels:**
- `resourceGroupName` - Resource group name

**Example:**
```
greengage_host_mem_limit_mb{resourceGroupName="default_group"} -1.0
greengage_host_mem_limit_mb{resourceGroupName="custom_group"} 8192.0
```

---

### greengage_host_max_mem_usage
### greengage_host_avg_mem_usage

**Type:** Gauge  
**Description:** Maximum/average memory usage in megabytes across all hosts.  
**Unit:** megabytes  
**Labels:** None

**Example:**
```
greengage_host_max_mem_usage 13904.0
greengage_host_avg_mem_usage 13857.5
```

---

### greengage_host_mem_usage_skew_ratio

**Type:** Gauge  
**Description:** Memory usage skew ratio across all hosts.  
**Possible Values:** `≥ 1.0` (1.0 = perfect balance, higher = more skew)  
**Labels:** None

**Example:**
```
greengage_host_mem_usage_skew_ratio 1.003
```

---

### greengage_host_num_running_sessions

**Type:** Gauge  
**Description:** Number of running sessions per resource group.  
**Labels:**
- `resourceGroupName` - Resource group name

**Example:**
```
greengage_host_num_running_sessions{resourceGroupName="default_group"} 5.0
greengage_host_num_running_sessions{resourceGroupName="admin_group"} 1.0
```

---

### greengage_host_num_queueing_sessions

**Type:** Gauge  
**Description:** Number of sessions waiting in queue per resource group (due to resource limits).  
**Labels:**
- `resourceGroupName` - Resource group name

**Example:**
```
greengage_host_num_queueing_sessions{resourceGroupName="default_group"} 0.0
```

**Alerting:** Alert when `greengage_host_num_queueing_sessions > 0` for extended periods.

---

### greengage_host_spill_usage_bytes

**Type:** Gauge  
**Description:** Disk spill file usage in bytes per host. Indicates queries that exceeded work_mem and spilled to disk.  
**Unit:** bytes  
**Labels:**
- `hostname` - Hostname

**Example:**
```
greengage_host_spill_usage_bytes{hostname="host-1.internal"} 0.0
greengage_host_spill_usage_bytes{hostname="host-2.internal"} 104857600.0
```

**Usage:** High spill indicates queries need more memory or better optimization.

---

### greengage_host_max_spill_usage
### greengage_host_avg_spill_usage

**Type:** Gauge  
**Description:** Maximum/average spill file usage in bytes across all hosts.  
**Unit:** bytes  
**Labels:** None

**Example:**
```
greengage_host_max_spill_usage 104857600.0
greengage_host_avg_spill_usage 52428800.0
```

---

### greengage_host_spill_usage_skew_ratio

**Type:** Gauge  
**Description:** Spill file usage skew ratio across all hosts.  
**Possible Values:** `≥ 0.0` (0.0 = no spill, 1.0 = perfect balance, higher = more skew)  
**Labels:** None

**Example:**
```
greengage_host_spill_usage_skew_ratio 0.0
```

---

## Vacuum Statistics Metrics

### greengage_database_table_dead_tuple_ratio

**Type:** Gauge  
**Description:** Ratio of dead tuples to total tuples (live + dead) for a table. High values indicate need for vacuum.  
**Unit:** ratio (0.0-1.0)  
**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_database_table_dead_tuple_ratio{database="mydb",schema="public",table="orders"} 0.15
```

**Alerting:** Alert when `greengage_database_table_dead_tuple_ratio > 0.2` (20% dead tuples).

---

### greengage_database_table_seconds_since_last_vacuum

**Type:** Gauge  
**Description:** Seconds since the last vacuum (manual or autovacuum) for this table.  
**Unit:** seconds  
**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_database_table_seconds_since_last_vacuum{database="mydb",schema="public",table="orders"} 25195.0
```

**Usage:** Convert to hours: `greengage_database_table_seconds_since_last_vacuum / 3600`.  
**Alerting:** Alert when exceeds 24 hours for high-churn tables.

---

### greengage_database_table_seconds_since_last_autovacuum

**Type:** Gauge  
**Description:** Seconds since the last autovacuum for this table.  
**Unit:** seconds  
**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_database_table_seconds_since_last_autovacuum{database="mydb",schema="public",table="orders"} 25195.0
```

---

### greengage_database_table_vacuum_count

**Type:** Gauge  
**Description:** Total number of manual vacuums performed on this table.  
**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_database_table_vacuum_count{database="mydb",schema="public",table="orders"} 1.0
```

---

### greengage_database_table_autovacuum_count

**Type:** Gauge  
**Description:** Total number of autovacuums performed on this table.  
**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_database_table_autovacuum_count{database="mydb",schema="public",table="orders"} 15.0
```

---

### greengage_database_db_max_seconds_since_last_vacuum

**Type:** Gauge  
**Description:** Maximum seconds since last vacuum across all tables in the database.  
**Unit:** seconds  
**Labels:**
- `datname` - Database name

**Example:**
```
greengage_database_db_max_seconds_since_last_vacuum{datname="mydb"} 25195.0
```

---

### greengage_database_db_avg_dead_tuple_ratio

**Type:** Gauge  
**Description:** Average dead tuple ratio across all tables in the database.  
**Unit:** ratio (0.0-1.0)  
**Labels:**
- `datname` - Database name

**Example:**
```
greengage_database_db_avg_dead_tuple_ratio{datname="mydb"} 0.05
```

---

### greengage_database_db_max_dead_tuple_ratio

**Type:** Gauge  
**Description:** Maximum dead tuple ratio across all tables in the database.  
**Unit:** ratio (0.0-1.0)  
**Labels:**
- `datname` - Database name

**Example:**
```
greengage_database_db_max_dead_tuple_ratio{datname="mydb"} 0.15
```

**Alerting:** Alert when `greengage_database_db_max_dead_tuple_ratio > 0.3`.

---

### greengage_server_vacuum_running

**Type:** Gauge  
**Description:** Indicates if any vacuum or autovacuum process is currently running.  
**Possible Values:** `0` (not running), `1` (running)  
**Labels:** None

**Example:**
```
greengage_server_vacuum_running 0.0
```

---

### greengage_server_vacuum_running_seconds

**Type:** Gauge  
**Description:** Seconds the vacuum/autovacuum process has been running. Only present when vacuum is active.  
**Unit:** seconds  
**Labels:**
- `datname` - Database name
- `usename` - Username running the vacuum
- `pid` - Process ID

**Example:**
```
greengage_server_vacuum_running_seconds{datname="mydb",usename="gpadmin",pid="12345"} 120.0
```

**Usage:** Identifies long-running vacuum operations.

---

## Table Health Metrics

### greengage_server_table_bloat_state

**Type:** Gauge  
**Description:** Table bloat state categorization based on the ratio of actual pages to expected pages.  
**Possible Values:**
- `0` - No bloat (healthy)
- `1` - Moderate bloat (ratio 4-10x)
- `2` - Severe bloat (ratio > 10x)

**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_server_table_bloat_state{database="mydb",schema="public",table="orders"} 0.0
greengage_server_table_bloat_state{database="mydb",schema="public",table="old_archive"} 2.0
```

**Alerting:** Alert when `greengage_server_table_bloat_state == 2` (severe bloat).  
**Remediation:** Run `VACUUM FULL` or recreate table.

---

### greengage_server_table_skew_factor

**Type:** Gauge  
**Description:** Table data skew coefficient. Measures how unevenly data is distributed across segments.  
**Unit:** coefficient  
**Possible Values:**
- `1.0` - Perfect distribution (no skew)
- `1.0-1.5` - Acceptable skew
- `> 1.5` - Significant skew (poor distribution)
- `> 100` - Extreme skew (data on very few segments)

**Labels:**
- `database` - Database name
- `schema` - Schema name
- `table` - Table name

**Example:**
```
greengage_server_table_skew_factor{database="mydb",schema="public",table="orders"} 1.2
greengage_server_table_skew_factor{database="mydb",schema="public",table="config"} 282.8
```

**Alerting:** Alert when `greengage_server_table_skew_factor > 5` for large tables.  
**Remediation:** Review distribution key and consider redistributing table.

**Note:** Only top 10 most skewed tables are reported (with skew > 0.1).

---

## Database Size Metrics

### greengage_host_database_name_mb_size

**Type:** Gauge  
**Description:** Total size in megabytes of each database in the file system.  
**Unit:** megabytes  
**Labels:**
- `dbname` - Database name

**Example:**
```
greengage_host_database_name_mb_size{dbname="mydb"} 3325.0
greengage_host_database_name_mb_size{dbname="postgres"} 150.0
```

**Usage:** Monitor database growth over time.

---

### greengage_host_total_database_size_mb

**Type:** Gauge  
**Description:** Total size of all databases in megabytes.  
**Unit:** megabytes  
**Labels:** None

**Example:**
```
greengage_host_total_database_size_mb 3475.0
```

---

### greengage_server_database_count

**Type:** Gauge  
**Description:** Number of databases in the cluster.  
**Labels:** None

**Example:**
```
greengage_server_database_count 2.0
```

---

## Backup Metrics (GPBackup)

**Note:** These metrics require GPBackup history database to be configured and the collector to be enabled.

### greengage_gpbackup_backup_count

**Type:** Gauge  
**Description:** Total number of backups for a database by type and status.  
**Labels:**
- `database` - Database name
- `type` - Backup type: `full` or `incremental`
- `status` - Backup status: `success`, `failure`, or `in_progress`

**Example:**
```
greengage_gpbackup_backup_count{database="mydb",status="success",type="full"} 10.0
greengage_gpbackup_backup_count{database="mydb",status="success",type="incremental"} 25.0
greengage_gpbackup_backup_count{database="mydb",status="failure",type="incremental"} 1.0
```

**Usage:** Track backup success rates:
```promql
rate(greengage_gpbackup_backup_count{status="success"}[1d])
```

---

### greengage_gpbackup_last_backup_duration_seconds

**Type:** Gauge  
**Description:** Duration of the last completed backup (success or failure) in seconds.  
**Unit:** seconds  
**Labels:**
- `database` - Database name
- `incremental` - `0` for full backup, `1` for incremental backup
- `status` - Backup status: `success` or `failure`

**Example:**
```
greengage_gpbackup_last_backup_duration_seconds{database="mydb",incremental="0",status="success"} 111.0
greengage_gpbackup_last_backup_duration_seconds{database="mydb",incremental="1",status="success"} 45.0
greengage_gpbackup_last_backup_duration_seconds{database="mydb",incremental="1",status="failure"} 7.0
```

**Usage:** Monitor backup performance and identify slow backups.  
**Alerting:** Alert if backup duration increases significantly.

---

### greengage_gpbackup_seconds_since_last_backup_completion

**Type:** Gauge  
**Description:** Seconds since the last successful backup completion.  
**Unit:** seconds  
**Labels:**
- `database` - Database name
- `incremental` - Backup type: `full` or `incremental`

**Example:**
```
greengage_gpbackup_seconds_since_last_backup_completion{database="mydb",incremental="full"} 50703.0
greengage_gpbackup_seconds_since_last_backup_completion{database="mydb",incremental="incremental"} 3600.0
```

**Usage:** Convert to hours: `greengage_gpbackup_seconds_since_last_backup_completion / 3600`.  
**Alerting:**
- Alert if no full backup in 7 days: `greengage_gpbackup_seconds_since_last_backup_completion{incremental="full"} > 604800`
- Alert if no incremental backup in 24 hours: `greengage_gpbackup_seconds_since_last_backup_completion{incremental="incremental"} > 86400`

---

## Exporter Internal Metrics

### greengage_exporter_total_scraped_total

**Type:** Counter  
**Description:** Total number of successful scrapes performed by the exporter.  
**Labels:** None

**Example:**
```
greengage_exporter_total_scraped_total 938.0
```

**Usage:** Track exporter uptime and scrape rate:
```promql
rate(greengage_exporter_total_scraped_total[5m])
```

---

### greengage_exporter_total_error_total

**Type:** Counter  
**Description:** Total number of scrape errors encountered by the exporter.  
**Labels:** None

**Example:**
```
greengage_exporter_total_error_total 0.0
```

**Alerting:** Alert when error rate is high:
```promql
rate(greengage_exporter_total_error_total[5m]) > 0.1
```

---

### greengage_exporter_scrape_duration_seconds

**Type:** Summary  
**Description:** Duration of scrape operations in seconds. Includes count, sum, and max.  
**Unit:** seconds  
**Labels:**
- `method` - (internal label for summary statistics)

**Example:**
```
greengage_exporter_scrape_duration_seconds_count 937.0
greengage_exporter_scrape_duration_seconds_sum 7897.27781061
greengage_exporter_scrape_duration_seconds_max 10.081908068
```

**Usage:**
- Average scrape duration: `greengage_exporter_scrape_duration_seconds_sum / greengage_exporter_scrape_duration_seconds_count`
- Scrape rate: `rate(greengage_exporter_scrape_duration_seconds_count[5m])`

**Alerting:** Alert if scrape duration exceeds scrape interval (indicates slow scrapes):
```promql
greengage_exporter_scrape_duration_seconds_max > 15
```
---
## Version Compatibility

- **Greengage DB 6.x:** All metrics supported, uses older WAL location functions
- **Greengage DB 7.x:** All metrics supported, uses newer WAL LSN functions
- The exporter automatically detects version and uses appropriate queries

---

## Additional Resources

- [Exporter Configuration Reference](README.md#configuration-reference)
- [Grafana Dashboards](deploy/dashboards/)
- [Prometheus Configuration Example](deploy/prometheus.yml)

---

**Generated for:** Greengage DB Exporter v1.0.0  
**Last Updated:** 2024-12-01

