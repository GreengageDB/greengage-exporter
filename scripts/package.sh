#!/usr/bin/env bash
#
# Greengage DB Exporter - Distribution Packaging Script
#
# This script creates distribution packages for the Greengage DB Exporter
# suitable for deployment to target systems.
#
# Usage:
#   ./package.sh [OPTIONS]
#
# Options:
#   --jvm              Package JVM version
#   --native           Package native executable version
#   --all              Package all versions
#   --output DIR       Output directory (default: dist/)
#   --help             Show this help message
#

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="${PROJECT_ROOT}/app"

OUTPUT_DIR="${PROJECT_ROOT}/dist"
VERSION=""

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
    echo -e "${BLUE}  Greengage DB Exporter - Packaging Script${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
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
Greengage DB Exporter - Distribution Packaging Script

Creates distribution packages for deployment to target systems.

Usage:
    ./package.sh [OPTIONS]

Options:
    --jvm              Package JVM version (default)
    --native           Package native executable version
    --all              Package all available versions
    --output DIR       Output directory (default: dist/)
    --version VER      Override version string
    --help, -h         Show this help message

Output:
    Creates tar.gz archives containing:
    - Application binaries
    - Configuration templates
    - Installation scripts
    - Documentation

Examples:
    # Package JVM version
    ./package.sh --jvm

    # Package native version
    ./package.sh --native

    # Package all versions to custom directory
    ./package.sh --all --output /tmp/releases

EOF
}

get_version() {
    cd "$APP_DIR"
    ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | sed 's/-SNAPSHOT//'
}

# ==============================================================================
# Packaging Functions
# ==============================================================================

prepare_output_dir() {
    print_step "Preparing output directory..."
    
    mkdir -p "$OUTPUT_DIR"
    print_success "Output directory: $OUTPUT_DIR"
}

package_jvm() {
    print_step "Packaging JVM version..."
    
    local jar_path="${APP_DIR}/target/quarkus-app/quarkus-run.jar"
    
    if [[ ! -f "$jar_path" ]]; then
        print_error "JVM build artifacts not found. Run './scripts/build.sh --jvm' first."
        return 1
    fi
    
    local package_name="greengage-exporter-${VERSION}-jvm"
    local package_dir="${OUTPUT_DIR}/${package_name}"
    local archive_name="${package_name}.tar.gz"
    
    # Create package directory structure
    rm -rf "$package_dir"
    mkdir -p "$package_dir"/{bin,lib,config,scripts}
    
    # Copy application
    cp -r "${APP_DIR}/target/quarkus-app"/* "$package_dir/lib/"
    
    # Create launcher script
    cat > "$package_dir/bin/greengage-exporter" << 'LAUNCHER_EOF'
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_FILE="${APP_DIR}/config/greengage-exporter.conf"

# Load configuration if exists
if [[ -f "$CONFIG_FILE" ]]; then
    set -a
    source "$CONFIG_FILE"
    set +a
fi

# JVM options
JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS="$JAVA_OPTS -Dquarkus.http.host=0.0.0.0"
JAVA_OPTS="$JAVA_OPTS -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
JAVA_OPTS="$JAVA_OPTS -Xms${JAVA_XMS:-64m} -Xmx${JAVA_XMX:-256m}"

exec java $JAVA_OPTS -jar "$APP_DIR/lib/quarkus-run.jar" "$@"
LAUNCHER_EOF
    chmod +x "$package_dir/bin/greengage-exporter"
    
    # Copy configuration template
    cp "${PROJECT_ROOT}/env.example" "$package_dir/config/greengage-exporter.conf.example"
    
    # Copy scripts
    cp "${SCRIPT_DIR}/install-service.sh" "$package_dir/scripts/"
    cp -r "${SCRIPT_DIR}/systemd" "$package_dir/scripts/"
    chmod +x "$package_dir/scripts/"*.sh
    
    # Copy documentation
    cp "${PROJECT_ROOT}/README.md" "$package_dir/"
    cp "${PROJECT_ROOT}/LICENSE" "$package_dir/"
    cp "${PROJECT_ROOT}/METRICS.md" "$package_dir/" 2>/dev/null || true
    
    # Create archive
    cd "$OUTPUT_DIR"
    tar -czf "$archive_name" "$package_name"
    rm -rf "$package_dir"
    
    print_success "Created: ${OUTPUT_DIR}/${archive_name}"
    
    # Show archive size
    local size
    size=$(du -h "${OUTPUT_DIR}/${archive_name}" | cut -f1)
    print_info "Size: $size"
}

package_native() {
    print_step "Packaging native version..."
    
    local runner_path
    runner_path=$(find "${APP_DIR}/target" -maxdepth 1 -name "*-runner" -type f 2>/dev/null | head -1)
    
    if [[ -z "$runner_path" ]] || [[ ! -f "$runner_path" ]]; then
        print_error "Native build artifacts not found. Run './scripts/build.sh --native' first."
        return 1
    fi
    
    local package_name="greengage-exporter-${VERSION}-native-linux-amd64"
    local package_dir="${OUTPUT_DIR}/${package_name}"
    local archive_name="${package_name}.tar.gz"
    
    # Create package directory structure
    rm -rf "$package_dir"
    mkdir -p "$package_dir"/{bin,config,scripts}
    
    # Copy native executable
    cp "$runner_path" "$package_dir/bin/greengage-exporter"
    chmod +x "$package_dir/bin/greengage-exporter"
    
    # Copy configuration template
    cp "${PROJECT_ROOT}/env.example" "$package_dir/config/greengage-exporter.conf.example"
    
    # Copy scripts
    cp "${SCRIPT_DIR}/install-service.sh" "$package_dir/scripts/"
    cp -r "${SCRIPT_DIR}/systemd" "$package_dir/scripts/"
    chmod +x "$package_dir/scripts/"*.sh
    
    # Copy documentation
    cp "${PROJECT_ROOT}/README.md" "$package_dir/"
    cp "${PROJECT_ROOT}/LICENSE" "$package_dir/"
    cp "${PROJECT_ROOT}/METRICS.md" "$package_dir/" 2>/dev/null || true
    
    # Create archive
    cd "$OUTPUT_DIR"
    tar -czf "$archive_name" "$package_name"
    rm -rf "$package_dir"
    
    print_success "Created: ${OUTPUT_DIR}/${archive_name}"
    
    # Show archive size
    local size
    size=$(du -h "${OUTPUT_DIR}/${archive_name}" | cut -f1)
    print_info "Size: $size"
}

create_checksums() {
    print_step "Creating checksums..."
    
    cd "$OUTPUT_DIR"
    
    # Create SHA256 checksums
    local checksum_file="checksums-sha256.txt"
    rm -f "$checksum_file"
    
    for archive in *.tar.gz; do
        if [[ -f "$archive" ]]; then
            if command -v sha256sum &> /dev/null; then
                sha256sum "$archive" >> "$checksum_file"
            elif command -v shasum &> /dev/null; then
                shasum -a 256 "$archive" >> "$checksum_file"
            fi
        fi
    done
    
    if [[ -f "$checksum_file" ]]; then
        print_success "Created: ${OUTPUT_DIR}/${checksum_file}"
    fi
}

# ==============================================================================
# Main
# ==============================================================================

main() {
    local PACKAGE_JVM=false
    local PACKAGE_NATIVE=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jvm)
                PACKAGE_JVM=true
                shift
                ;;
            --native)
                PACKAGE_NATIVE=true
                shift
                ;;
            --all)
                PACKAGE_JVM=true
                PACKAGE_NATIVE=true
                shift
                ;;
            --output)
                OUTPUT_DIR="$2"
                shift 2
                ;;
            --version)
                VERSION="$2"
                shift 2
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                print_error "Unknown option: $1"
                exit 2
                ;;
        esac
    done
    
    # Default to JVM if nothing specified
    if [[ "$PACKAGE_JVM" == false ]] && [[ "$PACKAGE_NATIVE" == false ]]; then
        PACKAGE_JVM=true
    fi
    
    print_header
    
    # Get version if not specified
    if [[ -z "$VERSION" ]]; then
        VERSION=$(get_version)
    fi
    print_info "Version: $VERSION"
    
    prepare_output_dir
    
    if [[ "$PACKAGE_JVM" == true ]]; then
        package_jvm || true
    fi
    
    if [[ "$PACKAGE_NATIVE" == true ]]; then
        package_native || true
    fi
    
    create_checksums
    
    echo
    print_success "Packaging complete!"
    echo
    echo "Distribution packages are in: $OUTPUT_DIR"
    ls -la "$OUTPUT_DIR"/*.tar.gz 2>/dev/null || true
}

main "$@"

