#!/usr/bin/env bash
#
# Greengage DB Exporter - Systemd Service Installation Script
#
# This script installs the Greengage DB Exporter as a systemd service
# on Linux systems. It handles:
# - Creating the service user
# - Installing binaries
# - Setting up configuration
# - Creating and enabling the systemd service
#
# Usage:
#   sudo ./install-service.sh [OPTIONS]
#
# Options:
#   --jvm              Install JVM version (default)
#   --native           Install native executable version
#   --uninstall        Remove the service and all related files
#   --prefix PATH      Installation prefix (default: /opt/greengage-exporter)
#   --user USER        Service user (default: ggexporter)
#   --group GROUP      Service group (default: ggexporter)
#   --help             Show this help message
#
# Requirements:
#   - Linux with systemd
#   - Root privileges (sudo)
#   - Built artifacts (run build.sh first)
#

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="${PROJECT_ROOT}/app"

# Detect if running from distribution package or source project
# Distribution package structure: ../bin/greengage-exporter, ../lib/quarkus-run.jar
# Source project structure: ../app/target/...
DIST_BIN_DIR="${PROJECT_ROOT}/bin"
DIST_LIB_DIR="${PROJECT_ROOT}/lib"
IS_DIST_PACKAGE=false

if [[ -d "$DIST_BIN_DIR" ]] || [[ -d "$DIST_LIB_DIR" ]]; then
    IS_DIST_PACKAGE=true
fi

# Installation defaults
INSTALL_PREFIX="/opt/greengage-exporter"
SERVICE_NAME="greengage-exporter"
SERVICE_USER="ggexporter"
SERVICE_GROUP="ggexporter"
INSTALL_MODE="jvm"

# Paths
SYSTEMD_DIR="/etc/systemd/system"
CONFIG_DIR="/etc/greengage-exporter"
LOG_DIR="/var/log/greengage-exporter"

# Colors for output
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    NC=''
fi

# ==============================================================================
# Helper Functions
# ==============================================================================

print_header() {
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  Greengage DB Exporter - Service Installation${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

print_step() {
    echo
    echo -e "${BLUE}▶${NC} $1"
}

show_help() {
    cat << EOF
Greengage DB Exporter - Systemd Service Installation Script

This script installs the Greengage DB Exporter as a systemd service on Linux.

Usage:
    sudo ./install-service.sh [OPTIONS]

Installation Modes:
    --jvm              Install JVM version (default)
                       Requires: Java 17+ runtime on target system
    
    --native           Install native executable version
                       No Java required at runtime

Actions:
    --install          Install the service (default)
    --uninstall        Remove the service and all files
    --upgrade          Upgrade existing installation

Options:
    --prefix PATH      Installation prefix (default: /opt/greengage-exporter)
    --user USER        Service user (default: ggexporter)
    --group GROUP      Service group (default: ggexporter)
    --no-start         Don't start service after installation
    --help, -h         Show this help message

Examples:
    # Install JVM version with defaults
    sudo ./install-service.sh

    # Install native version
    sudo ./install-service.sh --native

    # Install with custom prefix
    sudo ./install-service.sh --prefix /usr/local/greengage-exporter

    # Uninstall
    sudo ./install-service.sh --uninstall

Configuration:
    After installation, edit the configuration file at:
    ${CONFIG_DIR}/greengage-exporter.conf

    Then restart the service:
    sudo systemctl restart greengage-exporter

EOF
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        print_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

check_systemd() {
    if ! command -v systemctl &> /dev/null; then
        print_error "systemd not found. This script only supports systemd-based Linux distributions."
        exit 1
    fi
}

check_prerequisites() {
    check_root
    check_systemd
    
    if [[ "$INSTALL_MODE" == "jvm" ]]; then
        if ! command -v java &> /dev/null; then
            print_error "Java not found. JVM mode requires Java 17+ runtime."
            print_info "Install Java or use --native mode instead."
            exit 1
        fi
        
        local java_version
        java_version=$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')
        if [[ "$java_version" -lt 17 ]]; then
            print_error "Java ${java_version} found, but version 17+ is required."
            exit 1
        fi
    fi
}

# ==============================================================================
# User/Group Management
# ==============================================================================

create_service_user() {
    print_step "Creating service user and group..."
    
    # Create group if it doesn't exist
    if ! getent group "$SERVICE_GROUP" &> /dev/null; then
        groupadd --system "$SERVICE_GROUP"
        print_success "Created group: $SERVICE_GROUP"
    else
        print_info "Group already exists: $SERVICE_GROUP"
    fi
    
    # Create user if it doesn't exist
    if ! getent passwd "$SERVICE_USER" &> /dev/null; then
        useradd --system \
            --gid "$SERVICE_GROUP" \
            --home-dir "$INSTALL_PREFIX" \
            --shell /sbin/nologin \
            --comment "Greengage DB Exporter Service" \
            "$SERVICE_USER"
        print_success "Created user: $SERVICE_USER"
    else
        print_info "User already exists: $SERVICE_USER"
    fi
}

remove_service_user() {
    if getent passwd "$SERVICE_USER" &> /dev/null; then
        userdel "$SERVICE_USER" || true
        print_success "Removed user: $SERVICE_USER"
    fi
    
    if getent group "$SERVICE_GROUP" &> /dev/null; then
        groupdel "$SERVICE_GROUP" || true
        print_success "Removed group: $SERVICE_GROUP"
    fi
}

# ==============================================================================
# Installation Functions
# ==============================================================================

install_directories() {
    print_step "Creating directories..."
    
    mkdir -p "$INSTALL_PREFIX"
    mkdir -p "$INSTALL_PREFIX/bin"
    mkdir -p "$INSTALL_PREFIX/lib"
    mkdir -p "$CONFIG_DIR"
    mkdir -p "$LOG_DIR"
    
    print_success "Created: $INSTALL_PREFIX"
    print_success "Created: $CONFIG_DIR"
    print_success "Created: $LOG_DIR"
}

install_jvm_artifacts() {
    print_step "Installing JVM artifacts..."
    
    local source_dir=""
    
    # Check for distribution package first, then source project
    if [[ "$IS_DIST_PACKAGE" == true ]] && [[ -f "${DIST_LIB_DIR}/quarkus-run.jar" ]]; then
        source_dir="$DIST_LIB_DIR"
        print_info "Installing from distribution package"
    elif [[ -d "${APP_DIR}/target/quarkus-app" ]]; then
        source_dir="${APP_DIR}/target/quarkus-app"
        print_info "Installing from source build"
    else
        print_error "JVM build artifacts not found"
        print_info "Run './scripts/build.sh --jvm' first, or use a distribution package"
        exit 1
    fi
    
    # Copy Quarkus application
    cp -r "$source_dir"/* "$INSTALL_PREFIX/lib/"
    
    # Create wrapper script
    cat > "$INSTALL_PREFIX/bin/greengage-exporter" << 'WRAPPER_EOF'
#!/usr/bin/env bash
#
# Greengage DB Exporter launcher script
#
set -euo pipefail

INSTALL_DIR="/opt/greengage-exporter"
CONFIG_FILE="/etc/greengage-exporter/greengage-exporter.conf"

# Load configuration if exists
if [[ -f "$CONFIG_FILE" ]]; then
    set -a
    source "$CONFIG_FILE"
    set +a
fi

# Set default JVM options
JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS="$JAVA_OPTS -Dquarkus.http.host=0.0.0.0"
JAVA_OPTS="$JAVA_OPTS -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

# Memory settings (can be overridden in config)
if [[ -z "${JAVA_XMX:-}" ]]; then
    JAVA_XMX="256m"
fi
if [[ -z "${JAVA_XMS:-}" ]]; then
    JAVA_XMS="64m"
fi

JAVA_OPTS="$JAVA_OPTS -Xms${JAVA_XMS} -Xmx${JAVA_XMX}"

exec java $JAVA_OPTS -jar "$INSTALL_DIR/lib/quarkus-run.jar" "$@"
WRAPPER_EOF
    
    chmod +x "$INSTALL_PREFIX/bin/greengage-exporter"
    
    print_success "Installed JVM artifacts"
}

install_native_artifacts() {
    print_step "Installing native artifacts..."
    
    local runner_path=""
    
    # Check for distribution package first, then source project
    if [[ "$IS_DIST_PACKAGE" == true ]] && [[ -f "${DIST_BIN_DIR}/greengage-exporter" ]]; then
        runner_path="${DIST_BIN_DIR}/greengage-exporter"
        print_info "Installing from distribution package"
    else
        # Look for native runner in source build
        runner_path=$(find "${APP_DIR}/target" -maxdepth 1 -name "*-runner" -type f 2>/dev/null | head -1)
        if [[ -n "$runner_path" ]]; then
            print_info "Installing from source build"
        fi
    fi
    
    if [[ -z "$runner_path" ]] || [[ ! -f "$runner_path" ]]; then
        print_error "Native build artifacts not found"
        print_info "Run './scripts/build.sh --native' first, or use a distribution package"
        exit 1
    fi
    
    # Copy native executable
    cp "$runner_path" "$INSTALL_PREFIX/bin/greengage-exporter"
    chmod +x "$INSTALL_PREFIX/bin/greengage-exporter"
    
    print_success "Installed native executable"
}

install_configuration() {
    print_step "Installing configuration..."
    
    local config_file="${CONFIG_DIR}/greengage-exporter.conf"
    
    if [[ -f "$config_file" ]]; then
        print_warning "Configuration file already exists, creating backup"
        cp "$config_file" "${config_file}.bak.$(date +%Y%m%d%H%M%S)"
    fi
    
    # Create configuration file from template
    cat > "$config_file" << 'CONFIG_EOF'
# Greengage DB Exporter Configuration
# This file is sourced by the service. Use shell variable syntax.
#
# For full configuration reference, see:
# https://github.com/your-org/greengage-exporter#configuration-reference

# ==============================================================================
# HTTP Server
# ==============================================================================
HTTP_PORT=8080
# HTTPS_PORT=8443

# ==============================================================================
# Logging
# ==============================================================================
# Options: TRACE, DEBUG, INFO, WARN, ERROR
LOG_LEVEL=INFO

# ==============================================================================
# Database Connection (REQUIRED - update these values)
# ==============================================================================
DB_JDBC_URL="jdbc:postgresql://localhost:5432/postgres?sslmode=disable"
DB_USER="gpadmin"
DB_PASSWORD=""

# Connection Pool
DB_POOL_MAX=5
DB_POOL_MIN=1
DB_POOL_INIT=1

# Timeouts
DB_CONN_TIMEOUT=5s
DB_IDLE_TIMEOUT=10m
DB_MAX_LIFETIME=30m

# ==============================================================================
# Scrape Configuration
# ==============================================================================
SCRAPE_INTERVAL=15s

# ==============================================================================
# Orchestrator Configuration
# ==============================================================================
ORCHESTRATOR_CACHE_MAX_AGE=30s
ORCHESTRATOR_RETRY_ATTEMPTS=3
ORCHESTRATOR_RETRY_DELAY=1s
ORCHESTRATOR_FAILURE_THRESHOLD=3
ORCHESTRATOR_CIRCUIT_BREAKER=true

# ==============================================================================
# Metrics
# ==============================================================================
METRICS_PATH=/metrics

# ==============================================================================
# Collectors (enable/disable)
# ==============================================================================
COLLECTOR_CLUSTER_STATE_ENABLED=true
COLLECTOR_SEGMENT_ENABLED=true
COLLECTOR_CONNECTIONS_ENABLED=true
COLLECTOR_LOCKS_ENABLED=true
COLLECTOR_EXTENDED_LOCKS_ENABLED=true
COLLECTOR_DATABASE_SIZE_ENABLED=true
COLLECTOR_REPLICATION_MONITOR_ENABLED=true
COLLECTOR_TABLE_HEALTH_ENABLED=true
COLLECTOR_SPILL_PER_HOST_ENABLED=true
COLLECTOR_DISK_PER_HOST_ENABLED=true
COLLECTOR_RSG_PER_HOST_ENABLED=true
COLLECTOR_ACTIVE_QUERY_DURATION_ENABLED=true
COLLECTOR_TABLE_VACUUM_STATS_ENABLED=true
COLLECTOR_DB_VACUUM_STATS_ENABLED=true
COLLECTOR_VACUUM_RUNNING_ENABLED=true
COLLECTOR_GP_BACKUP_HISTORY_ENABLED=true
GPBACKUP_HISTORY_JDBC_URL=jdbc:sqlite:/data1/master/gpseg-1/gpbackup_history.db
# ==============================================================================
# Per-Database Collection
# ==============================================================================
# Mode: all, include, exclude, none
COLLECTOR_PER_DB_MODE=all
COLLECTOR_PER_DB_LIST="postgres"
COLLECTOR_PER_DB_CACHE_ENABLED=true

# ==============================================================================
# JVM Settings (only for JVM mode)
# ==============================================================================
# JAVA_XMS=64m
# JAVA_XMX=256m
# JAVA_OPTS=""

CONFIG_EOF
    
    # Set secure permissions on config (contains password)
    chmod 640 "$config_file"
    
    print_success "Created configuration: $config_file"
    print_warning "IMPORTANT: Edit $config_file and set your database credentials"
}

install_systemd_service() {
    print_step "Installing systemd service..."
    
    local service_file="${SYSTEMD_DIR}/${SERVICE_NAME}.service"
    
    # Determine ExecStart based on mode
    local exec_start
    if [[ "$INSTALL_MODE" == "jvm" ]]; then
        exec_start="${INSTALL_PREFIX}/bin/greengage-exporter"
    else
        exec_start="${INSTALL_PREFIX}/bin/greengage-exporter -Dquarkus.http.host=0.0.0.0"
    fi
    
    cat > "$service_file" << SERVICE_EOF
[Unit]
Description=Greengage DB Prometheus Exporter
Documentation=https://github.com/your-org/greengage-exporter
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_GROUP}

# Environment configuration
EnvironmentFile=-${CONFIG_DIR}/greengage-exporter.conf

# Execution
WorkingDirectory=${INSTALL_PREFIX}
ExecStart=${exec_start}

# Restart behavior
Restart=on-failure
RestartSec=10
StartLimitInterval=60
StartLimitBurst=3

# Security hardening
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes

# Allow read access to config
ReadOnlyPaths=/
ReadWritePaths=${LOG_DIR}

# Capabilities
CapabilityBoundingSet=
AmbientCapabilities=

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=greengage-exporter

# Resource limits (adjust as needed)
MemoryMax=512M
TasksMax=100

[Install]
WantedBy=multi-user.target
SERVICE_EOF
    
    print_success "Created systemd service: $service_file"
}

set_permissions() {
    print_step "Setting permissions..."
    
    chown -R "$SERVICE_USER:$SERVICE_GROUP" "$INSTALL_PREFIX"
    chown -R "$SERVICE_USER:$SERVICE_GROUP" "$LOG_DIR"
    chown root:$SERVICE_GROUP "$CONFIG_DIR"
    chown root:$SERVICE_GROUP "${CONFIG_DIR}/greengage-exporter.conf"
    
    chmod 755 "$INSTALL_PREFIX"
    chmod 755 "$INSTALL_PREFIX/bin"
    chmod 750 "$CONFIG_DIR"
    
    print_success "Permissions set"
}

enable_and_start_service() {
    print_step "Enabling and starting service..."
    
    systemctl daemon-reload
    systemctl enable "$SERVICE_NAME"
    
    if [[ "$NO_START" == false ]]; then
        systemctl start "$SERVICE_NAME"
        sleep 2
        
        if systemctl is-active --quiet "$SERVICE_NAME"; then
            print_success "Service started successfully"
        else
            print_warning "Service may have issues starting"
            print_info "Check logs: journalctl -u $SERVICE_NAME -f"
        fi
    else
        print_info "Service enabled but not started (--no-start specified)"
    fi
}

# ==============================================================================
# Uninstall Functions
# ==============================================================================

uninstall_service() {
    print_step "Uninstalling Greengage DB Exporter..."
    
    # Stop and disable service
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        systemctl stop "$SERVICE_NAME"
        print_success "Stopped service"
    fi
    
    if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
        systemctl disable "$SERVICE_NAME"
        print_success "Disabled service"
    fi
    
    # Remove service file
    local service_file="${SYSTEMD_DIR}/${SERVICE_NAME}.service"
    if [[ -f "$service_file" ]]; then
        rm -f "$service_file"
        systemctl daemon-reload
        print_success "Removed service file"
    fi
    
    # Remove installation directory
    if [[ -d "$INSTALL_PREFIX" ]]; then
        rm -rf "$INSTALL_PREFIX"
        print_success "Removed: $INSTALL_PREFIX"
    fi
    
    # Remove log directory
    if [[ -d "$LOG_DIR" ]]; then
        rm -rf "$LOG_DIR"
        print_success "Removed: $LOG_DIR"
    fi
    
    # Ask about config removal
    if [[ -d "$CONFIG_DIR" ]]; then
        echo
        read -p "Remove configuration directory ${CONFIG_DIR}? [y/N] " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm -rf "$CONFIG_DIR"
            print_success "Removed: $CONFIG_DIR"
        else
            print_info "Configuration preserved at: $CONFIG_DIR"
        fi
    fi
    
    # Remove user
    echo
    read -p "Remove service user and group? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        remove_service_user
    else
        print_info "User/group preserved: $SERVICE_USER/$SERVICE_GROUP"
    fi
    
    print_success "Uninstallation complete"
}

# ==============================================================================
# Installation Summary
# ==============================================================================

print_installation_summary() {
    echo
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  Installation Complete!${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo
    echo "Installation Details:"
    echo "  Mode:           $INSTALL_MODE"
    echo "  Install Dir:    $INSTALL_PREFIX"
    echo "  Config File:    ${CONFIG_DIR}/greengage-exporter.conf"
    echo "  Log Dir:        $LOG_DIR"
    echo "  Service User:   $SERVICE_USER"
    echo
    echo "Service Management:"
    echo "  Status:         sudo systemctl status $SERVICE_NAME"
    echo "  Start:          sudo systemctl start $SERVICE_NAME"
    echo "  Stop:           sudo systemctl stop $SERVICE_NAME"
    echo "  Restart:        sudo systemctl restart $SERVICE_NAME"
    echo "  Logs:           journalctl -u $SERVICE_NAME -f"
    echo
    echo "Next Steps:"
    echo "  1. Edit configuration: sudo nano ${CONFIG_DIR}/greengage-exporter.conf"
    echo "  2. Set database credentials (DB_JDBC_URL, DB_USER, DB_PASSWORD)"
    echo "  3. Restart service: sudo systemctl restart $SERVICE_NAME"
    echo "  4. Verify: curl http://localhost:8080/metrics"
    echo
}

# ==============================================================================
# Main
# ==============================================================================

main() {
    local ACTION="install"
    NO_START=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jvm)
                INSTALL_MODE="jvm"
                shift
                ;;
            --native)
                INSTALL_MODE="native"
                shift
                ;;
            --install)
                ACTION="install"
                shift
                ;;
            --uninstall)
                ACTION="uninstall"
                shift
                ;;
            --upgrade)
                ACTION="upgrade"
                shift
                ;;
            --prefix)
                INSTALL_PREFIX="$2"
                shift 2
                ;;
            --user)
                SERVICE_USER="$2"
                shift 2
                ;;
            --group)
                SERVICE_GROUP="$2"
                shift 2
                ;;
            --no-start)
                NO_START=true
                shift
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                print_error "Unknown option: $1"
                echo "Use --help for usage information"
                exit 2
                ;;
        esac
    done
    
    print_header
    
    case "$ACTION" in
        install)
            check_prerequisites
            create_service_user
            install_directories
            
            if [[ "$INSTALL_MODE" == "jvm" ]]; then
                install_jvm_artifacts
            else
                install_native_artifacts
            fi
            
            install_configuration
            install_systemd_service
            set_permissions
            enable_and_start_service
            print_installation_summary
            ;;
        uninstall)
            check_root
            uninstall_service
            ;;
        upgrade)
            check_prerequisites
            
            # Stop service for upgrade
            if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
                print_step "Stopping service for upgrade..."
                systemctl stop "$SERVICE_NAME"
            fi
            
            # Reinstall artifacts
            if [[ "$INSTALL_MODE" == "jvm" ]]; then
                install_jvm_artifacts
            else
                install_native_artifacts
            fi
            
            set_permissions
            
            # Restart service
            systemctl start "$SERVICE_NAME"
            print_success "Upgrade complete"
            ;;
    esac
}

main "$@"

