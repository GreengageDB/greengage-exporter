#!/usr/bin/env bash
#
# Greengage DB Exporter - Environment Setup Script
#
# This script checks and prepares the environment for building and running
# the Greengage DB Exporter. It verifies required dependencies and optionally
# installs missing components.
#
# Usage:
#   ./setup-env.sh [OPTIONS]
#
# Options:
#   --check-only     Only check dependencies, don't install anything
#   --native         Include GraalVM/native-image requirements
#   --docker         Include Docker requirements
#   --help           Show this help message
#
# Exit codes:
#   0 - All requirements satisfied
#   1 - Missing required dependencies
#   2 - Invalid arguments
#

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Required versions
REQUIRED_JAVA_VERSION=17
REQUIRED_MAVEN_VERSION="3.8.0"

# Colors for output (if terminal supports it)
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m' # No Color
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
    echo -e "${BLUE}  Greengage DB Exporter - Environment Setup${NC}"
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

print_section() {
    echo
    echo -e "${BLUE}──────────────────────────────────────────────────────────────${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}──────────────────────────────────────────────────────────────${NC}"
}

show_help() {
    cat << EOF
Greengage DB Exporter - Environment Setup Script

This script checks and prepares the environment for building and running
the Greengage DB Exporter.

Usage:
    ./setup-env.sh [OPTIONS]

Options:
    --check-only     Only check dependencies, don't suggest installations
    --native         Include GraalVM/native-image requirements check
    --docker         Include Docker requirements check
    --all            Check all optional dependencies (native + docker)
    --help, -h       Show this help message

Examples:
    # Basic check for JVM build
    ./setup-env.sh

    # Check with native build requirements
    ./setup-env.sh --native

    # Check all requirements
    ./setup-env.sh --all

    # Only check, don't show installation instructions
    ./setup-env.sh --check-only

Exit codes:
    0 - All required dependencies satisfied
    1 - Missing required dependencies
    2 - Invalid arguments

EOF
}

# Compare version strings
# Returns: 0 if $1 >= $2, 1 otherwise
version_gte() {
    local v1="$1"
    local v2="$2"
    
    if [[ "$v1" == "$v2" ]]; then
        return 0
    fi
    
    local IFS=.
    local i
    local v1_parts=($v1)
    local v2_parts=($v2)
    
    for ((i=0; i<${#v2_parts[@]}; i++)); do
        local v1_part="${v1_parts[i]:-0}"
        local v2_part="${v2_parts[i]:-0}"
        
        if ((v1_part > v2_part)); then
            return 0
        elif ((v1_part < v2_part)); then
            return 1
        fi
    done
    
    return 0
}

# Detect operating system
detect_os() {
    case "$(uname -s)" in
        Linux*)     echo "linux";;
        Darwin*)    echo "macos";;
        CYGWIN*|MINGW*|MSYS*) echo "windows";;
        *)          echo "unknown";;
    esac
}

# Detect architecture
detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64)  echo "amd64";;
        arm64|aarch64) echo "arm64";;
        *)             echo "$(uname -m)";;
    esac
}

# ==============================================================================
# Dependency Checks
# ==============================================================================

check_java() {
    print_section "Java Development Kit (JDK)"
    
    if ! command -v java &> /dev/null; then
        print_error "Java not found"
        MISSING_DEPS+=("java")
        return 1
    fi
    
    # Get Java version
    local java_version_output
    java_version_output=$(java -version 2>&1 | head -n 1)
    
    # Extract version number (handles both "1.8.0_xxx" and "17.0.x" formats)
    # First extract the quoted version string, then get the major version
    local java_version
    java_version=$(echo "$java_version_output" | sed -E 's/.*version "([0-9]+)\.[0-9]+\.[0-9]+.*/\1/' | head -1)
    
    # Fallback for older format like "1.8.0_xxx"
    if [[ "$java_version" == "1" ]]; then
        java_version=$(echo "$java_version_output" | sed -E 's/.*version "1\.([0-9]+)\..*/\1/')
    fi
    
    if [[ -z "$java_version" ]]; then
        print_error "Unable to determine Java version"
        MISSING_DEPS+=("java")
        return 1
    fi
    
    if [[ "$java_version" -ge "$REQUIRED_JAVA_VERSION" ]]; then
        print_success "Java ${java_version} found (required: ${REQUIRED_JAVA_VERSION}+)"
        
        # Check JAVA_HOME
        if [[ -n "${JAVA_HOME:-}" ]]; then
            print_success "JAVA_HOME is set: ${JAVA_HOME}"
        else
            print_warning "JAVA_HOME is not set (optional, but recommended)"
        fi
        return 0
    else
        print_error "Java ${java_version} found, but version ${REQUIRED_JAVA_VERSION}+ is required"
        MISSING_DEPS+=("java")
        return 1
    fi
}

check_maven() {
    print_section "Apache Maven"
    
    # Check for Maven wrapper first (preferred)
    local mvnw_path="${PROJECT_ROOT}/app/mvnw"
    if [[ -x "$mvnw_path" ]]; then
        print_success "Maven Wrapper (mvnw) found in project"
        
        # Try to get version from wrapper
        if cd "${PROJECT_ROOT}/app" && ./mvnw --version &> /dev/null; then
            local mvn_version
            mvn_version=$(./mvnw --version 2>&1 | grep -i "Apache Maven" | sed -E 's/Apache Maven ([0-9]+\.[0-9]+\.[0-9]+).*/\1/')
            print_success "Maven version: ${mvn_version}"
        fi
        cd "$SCRIPT_DIR"
        return 0
    fi
    
    # Check for system Maven
    if command -v mvn &> /dev/null; then
        local mvn_version
        mvn_version=$(mvn --version 2>&1 | grep -i "Apache Maven" | sed -E 's/Apache Maven ([0-9]+\.[0-9]+\.[0-9]+).*/\1/')
        
        if version_gte "$mvn_version" "$REQUIRED_MAVEN_VERSION"; then
            print_success "Maven ${mvn_version} found (required: ${REQUIRED_MAVEN_VERSION}+)"
            return 0
        else
            print_warning "Maven ${mvn_version} found, but ${REQUIRED_MAVEN_VERSION}+ is recommended"
            return 0
        fi
    fi
    
    print_warning "Maven not found, but Maven Wrapper in project can be used"
    return 0
}

check_graalvm() {
    print_section "GraalVM & Native Image (for native builds)"
    
    # Check if running on GraalVM
    local java_vendor
    java_vendor=$(java -XshowSettings:properties -version 2>&1 | grep "java.vendor" | head -1 || true)
    
    local is_graalvm=false
    if echo "$java_vendor" | grep -qi "graalvm\|oracle"; then
        # Double check with native-image
        if command -v native-image &> /dev/null; then
            is_graalvm=true
            local ni_version
            ni_version=$(native-image --version 2>&1 | head -1)
            print_success "GraalVM detected with native-image"
            print_info "Version: $ni_version"
        fi
    fi
    
    if [[ "$is_graalvm" == false ]]; then
        if command -v native-image &> /dev/null; then
            print_success "native-image tool found"
            local ni_version
            ni_version=$(native-image --version 2>&1 | head -1)
            print_info "Version: $ni_version"
        else
            print_warning "GraalVM native-image not found"
            print_info "Native builds can use container-based builds (Docker required)"
            print_info "Or install GraalVM: https://www.graalvm.org/downloads/"
            
            if [[ "$CHECK_ONLY" == false ]]; then
                OPTIONAL_MISSING+=("graalvm")
            fi
            return 1
        fi
    fi
    
    # Check GRAALVM_HOME
    if [[ -n "${GRAALVM_HOME:-}" ]]; then
        print_success "GRAALVM_HOME is set: ${GRAALVM_HOME}"
    else
        print_warning "GRAALVM_HOME is not set (optional for container builds)"
    fi
    
    return 0
}

check_docker() {
    print_section "Docker (for container builds and deployment)"
    
    if ! command -v docker &> /dev/null; then
        print_warning "Docker not found"
        print_info "Docker is optional but recommended for:"
        print_info "  - Container-based native builds (no local GraalVM needed)"
        print_info "  - Building container images"
        print_info "  - Running with Docker Compose"
        OPTIONAL_MISSING+=("docker")
        return 1
    fi
    
    # Check if Docker daemon is running
    if ! docker info &> /dev/null 2>&1; then
        print_warning "Docker is installed but not running"
        print_info "Start Docker daemon to enable container builds"
        OPTIONAL_MISSING+=("docker-running")
        return 1
    fi
    
    local docker_version
    docker_version=$(docker --version | sed -E 's/Docker version ([0-9]+\.[0-9]+\.[0-9]+).*/\1/')
    print_success "Docker ${docker_version} is installed and running"
    
    # Check Docker Compose
    if command -v docker-compose &> /dev/null || docker compose version &> /dev/null 2>&1; then
        print_success "Docker Compose is available"
    else
        print_warning "Docker Compose not found (optional, for local development)"
    fi
    
    return 0
}

check_permissions() {
    print_section "File Permissions"
    
    local mvnw_path="${PROJECT_ROOT}/app/mvnw"
    
    if [[ -f "$mvnw_path" ]]; then
        if [[ -x "$mvnw_path" ]]; then
            print_success "Maven wrapper (mvnw) is executable"
        else
            print_warning "Maven wrapper is not executable, fixing..."
            chmod +x "$mvnw_path"
            print_success "Made mvnw executable"
        fi
    fi
    
    return 0
}

# ==============================================================================
# Installation Instructions
# ==============================================================================

show_installation_instructions() {
    local os
    os=$(detect_os)
    
    if [[ ${#MISSING_DEPS[@]} -eq 0 ]] && [[ ${#OPTIONAL_MISSING[@]} -eq 0 ]]; then
        return 0
    fi
    
    print_section "Installation Instructions"
    
    for dep in "${MISSING_DEPS[@]:-}"; do
        case "$dep" in
            java)
                echo
                echo "Java ${REQUIRED_JAVA_VERSION}+ Installation:"
                case "$os" in
                    macos)
                        echo "  Using Homebrew:"
                        echo "    brew install openjdk@17"
                        echo "    sudo ln -sfn \$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
                        echo ""
                        echo "  Or using SDKMAN:"
                        echo "    curl -s 'https://get.sdkman.io' | bash"
                        echo "    sdk install java 17.0.9-tem"
                        ;;
                    linux)
                        echo "  Debian/Ubuntu:"
                        echo "    sudo apt update && sudo apt install openjdk-17-jdk"
                        echo ""
                        echo "  RHEL/CentOS/Fedora:"
                        echo "    sudo dnf install java-17-openjdk-devel"
                        echo ""
                        echo "  Or using SDKMAN:"
                        echo "    curl -s 'https://get.sdkman.io' | bash"
                        echo "    sdk install java 17.0.9-tem"
                        ;;
                    *)
                        echo "  Download from: https://adoptium.net/temurin/releases/?version=17"
                        ;;
                esac
                ;;
        esac
    done
    
    for dep in "${OPTIONAL_MISSING[@]:-}"; do
        case "$dep" in
            graalvm)
                echo
                echo "GraalVM Installation (optional, for native builds):"
                case "$os" in
                    macos)
                        echo "  Using SDKMAN (recommended):"
                        echo "    sdk install java 17.0.9-graal"
                        echo ""
                        echo "  Using Homebrew:"
                        echo "    brew install --cask graalvm-jdk17"
                        echo "    export GRAALVM_HOME=\$(/usr/libexec/java_home -v 17)"
                        ;;
                    linux)
                        echo "  Using SDKMAN (recommended):"
                        echo "    sdk install java 17.0.9-graal"
                        echo ""
                        echo "  Manual installation:"
                        echo "    Download from: https://www.graalvm.org/downloads/"
                        echo "    Extract and set GRAALVM_HOME and JAVA_HOME"
                        ;;
                    *)
                        echo "  Download from: https://www.graalvm.org/downloads/"
                        ;;
                esac
                echo ""
                echo "  After installation, install native-image component:"
                echo "    gu install native-image"
                echo ""
                echo "  Note: You can skip GraalVM installation and use container-based"
                echo "        native builds instead (requires Docker)."
                ;;
            docker|docker-running)
                echo
                echo "Docker Installation (optional):"
                case "$os" in
                    macos)
                        echo "  Using Homebrew:"
                        echo "    brew install --cask docker"
                        echo "    # Then start Docker Desktop from Applications"
                        ;;
                    linux)
                        echo "  Official Docker installation:"
                        echo "    curl -fsSL https://get.docker.com | sh"
                        echo "    sudo usermod -aG docker \$USER"
                        echo "    # Log out and back in for group changes to take effect"
                        ;;
                    *)
                        echo "  Download from: https://www.docker.com/products/docker-desktop/"
                        ;;
                esac
                ;;
        esac
    done
}

# ==============================================================================
# Environment Summary
# ==============================================================================

print_summary() {
    print_section "Environment Summary"
    
    local os
    os=$(detect_os)
    local arch
    arch=$(detect_arch)
    
    echo "Operating System: $os ($arch)"
    echo "Project Root:     $PROJECT_ROOT"
    echo
    
    if [[ ${#MISSING_DEPS[@]} -gt 0 ]]; then
        print_error "Missing required dependencies: ${MISSING_DEPS[*]}"
        echo
        echo "Please install the required dependencies and run this script again."
        return 1
    fi
    
    if [[ ${#OPTIONAL_MISSING[@]} -gt 0 ]]; then
        print_warning "Some optional dependencies are missing: ${OPTIONAL_MISSING[*]}"
        echo
        echo "The exporter can still be built and run, but some features"
        echo "may not be available."
    fi
    
    print_success "Environment is ready for building Greengage DB Exporter!"
    echo
    echo "Next steps:"
    echo "  1. Build JVM version:    ./scripts/build.sh --jvm"
    echo "  2. Build native version: ./scripts/build.sh --native"
    echo "  3. Build Docker image:   ./scripts/build.sh --docker"
    echo
    
    return 0
}

# ==============================================================================
# Main
# ==============================================================================

main() {
    local CHECK_NATIVE=false
    local CHECK_DOCKER=false
    local CHECK_ONLY=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --check-only)
                CHECK_ONLY=true
                shift
                ;;
            --native)
                CHECK_NATIVE=true
                shift
                ;;
            --docker)
                CHECK_DOCKER=true
                shift
                ;;
            --all)
                CHECK_NATIVE=true
                CHECK_DOCKER=true
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
    
    # Arrays to track missing dependencies
    MISSING_DEPS=()
    OPTIONAL_MISSING=()
    
    print_header
    
    # Required checks
    check_java || true
    check_maven || true
    check_permissions || true
    
    # Optional checks
    if [[ "$CHECK_NATIVE" == true ]]; then
        check_graalvm || true
    fi
    
    if [[ "$CHECK_DOCKER" == true ]]; then
        check_docker || true
    fi
    
    # Show installation instructions if not in check-only mode
    if [[ "$CHECK_ONLY" == false ]]; then
        show_installation_instructions
    fi
    
    # Print summary and exit with appropriate code
    print_summary
    exit_code=$?
    
    exit $exit_code
}

main "$@"

