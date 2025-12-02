# Greengage DB Exporter - Deployment Scripts

This directory contains scripts for setting up, building, and deploying the Greengage DB Exporter.

## Scripts Overview

| Script | Purpose |
|--------|---------|
| `setup-env.sh` | Check and prepare the development/build environment |
| `build.sh` | Build the application (JVM, native, Docker) |
| `install-service.sh` | Install as a systemd service on Linux |
| `package.sh` | Create distribution archives for release |

## Quick Start

### 1. Environment Setup

First, verify your environment has all required dependencies:

```bash
# Check basic requirements
./scripts/setup-env.sh

# Check with native build requirements
./scripts/setup-env.sh --native

# Check with Docker requirements
./scripts/setup-env.sh --docker

# Check all optional dependencies
./scripts/setup-env.sh --all
```

### 2. Build the Application

Choose a build type based on your deployment needs:

```bash
# Build JVM version (requires Java 17+ at runtime)
./scripts/build.sh --jvm

# Build native executable with local GraalVM
./scripts/build.sh --native

# Build native executable using Docker (no local GraalVM needed)
./scripts/build.sh --native-container

# Build Docker image (JVM)
./scripts/build.sh --docker-jvm

# Build Docker image (Native)
./scripts/build.sh --docker-native
```

**Common options:**
- `--clean` - Clean before building
- `--skip-tests` - Skip test execution

### 3. Deploy as Systemd Service

For production Linux deployments:

```bash
# Install JVM version
sudo ./scripts/install-service.sh --jvm

# Install native version
sudo ./scripts/install-service.sh --native

# Uninstall
sudo ./scripts/install-service.sh --uninstall
```

### 4. Create Distribution Packages

Create release archives for distribution to other systems:

```bash
# Package JVM version (creates tar.gz with all needed files)
./scripts/package.sh --jvm

# Package native version
./scripts/package.sh --native

# Package all versions
./scripts/package.sh --all

# Custom output directory and version
./scripts/package.sh --jvm --output ./releases --version 1.0.0
```

**Output:** Creates `dist/greengage-exporter-{version}-{type}.tar.gz` containing:
- Application binaries (`bin/`)
- Configuration templates (`config/`)
- Installation scripts (`scripts/`)
- Documentation (README, LICENSE, METRICS)
- SHA256 checksums

**Deploy from package:**
```bash
# On target server
tar -xzf greengage-exporter-1.0.0-jvm.tar.gz
cd greengage-exporter-1.0.0-jvm

# Option 1: Run directly
./bin/greengage-exporter

# Option 2: Install as service
sudo ./scripts/install-service.sh --jvm
```

## Build Types Comparison

| Build Type | Runtime Requirements | Startup Time | Memory Usage | Best For |
|------------|---------------------|--------------|--------------|----------|
| JVM | Java 17+ | ~2-3 seconds | Higher | Development, flexibility |
| Native | None | ~50ms | Lower | Production, containers |
| Docker JVM | Docker | ~2-3 seconds | Higher | Container orchestration |
| Docker Native | Docker | ~50ms | Lower | Kubernetes, serverless |

## File Locations After Installation

When installed as a systemd service:

| Location | Purpose |
|----------|---------|
| `/opt/greengage-exporter/` | Application files |
| `/etc/greengage-exporter/` | Configuration |
| `/var/log/greengage-exporter/` | Log files (if file logging enabled) |
| `/etc/systemd/system/greengage-exporter.service` | Service unit file |

## Configuration

After installation, edit the configuration file:

```bash
sudo nano /etc/greengage-exporter/greengage-exporter.conf
```

Key settings to configure:
- `DB_JDBC_URL` - Database connection URL
- `DB_USER` - Database username
- `DB_PASSWORD` - Database password
- `HTTP_PORT` - HTTP server port (default: 8080)

Then restart the service:

```bash
sudo systemctl restart greengage-exporter
```

## Service Management

```bash
# Check status
sudo systemctl status greengage-exporter

# Start/Stop/Restart
sudo systemctl start greengage-exporter
sudo systemctl stop greengage-exporter
sudo systemctl restart greengage-exporter

# View logs
journalctl -u greengage-exporter -f

# View last 100 lines
journalctl -u greengage-exporter -n 100
```
## Environment Variables

The following environment variables can be used to customize builds:

| Variable | Description | Default |
|----------|-------------|---------|
| `DOCKER_REGISTRY` | Docker registry prefix | (none) |
| `DOCKER_TAG` | Docker image tag | `latest` |
| `MAVEN_OPTS` | Maven JVM options | (none) |

Example:
```bash
DOCKER_REGISTRY=myregistry.io DOCKER_TAG=1.0.0 ./scripts/build.sh --docker-jvm
```

## Reference Files

- `systemd/greengage-exporter.service` - Template systemd unit file for manual installation
- `../env.example` - Example environment configuration file

