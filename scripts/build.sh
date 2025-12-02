#!/usr/bin/env bash
#
# Greengage DB Exporter - Build Script
#
# This script provides various build options for the Greengage DB Exporter
# including JVM builds, GraalVM native builds, and Docker image builds.
#
# Usage:
#   ./build.sh [OPTIONS]
#
# Options:
#   --jvm              Build standard JVM version (uber-jar)
#   --native           Build native executable with GraalVM
#   --native-container Build native executable using Docker container
#   --docker-jvm       Build Docker image with JVM version
#   --docker-native    Build Docker image with native executable
#   --clean            Clean build artifacts before building
#   --skip-tests       Skip running tests
#   --help             Show this help message
#

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="${PROJECT_ROOT}/app"

# Build configuration
ARTIFACT_NAME="greengage-exporter"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
DOCKER_TAG="${DOCKER_TAG:-latest}"

# Maven wrapper
MAVEN_CMD="${APP_DIR}/mvnw"

# Colors for output
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    CYAN='\033[0;36m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    CYAN=''
    NC=''
fi

# ==============================================================================
# Helper Functions
# ==============================================================================

print_header() {
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  Greengage DB Exporter - Build Script${NC}"
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
    echo -e "${CYAN}▶${NC} $1"
}

show_help() {
    cat << EOF
Greengage DB Exporter - Build Script

This script provides various build options for the Greengage DB Exporter
including JVM builds, GraalVM native builds, and Docker image builds.

Usage:
    ./build.sh [OPTIONS]

Build Types:
    --jvm              Build standard JVM version (default)
                       Output: app/target/quarkus-app/quarkus-run.jar
    
    --native           Build native executable with local GraalVM
                       Requires: GraalVM with native-image installed
                       Output: app/target/exporter-*-runner
    
    --native-container Build native executable using Docker container
                       Requires: Docker
                       Output: app/target/exporter-*-runner
    
    --docker-jvm       Build Docker image with JVM version
                       Requires: Docker, prior JVM build
                       Output: Docker image
    
    --docker-native    Build Docker image with native executable
                       Requires: Docker, prior native build
                       Output: Docker image

Options:
    --clean            Clean build artifacts before building
    --skip-tests       Skip running tests during build
    --verbose          Enable verbose Maven output
    --help, -h         Show this help message

Docker Options (use with --docker-*):
    DOCKER_REGISTRY    Docker registry prefix (default: none)
    DOCKER_TAG         Docker image tag (default: latest)

Examples:
    # Build JVM version with tests
    ./build.sh --jvm

    # Build JVM version, skip tests
    ./build.sh --jvm --skip-tests

    # Clean and build native executable using container
    ./build.sh --native-container --clean --skip-tests

    # Build Docker image (JVM)
    ./build.sh --docker-jvm

    # Build with custom Docker tag
    DOCKER_TAG=1.0.0 ./build.sh --docker-jvm

EOF
}

# Check if required tools are available
check_prerequisites() {
    local mode="$1"
    
    # Check Maven wrapper
    if [[ ! -x "$MAVEN_CMD" ]]; then
        print_error "Maven wrapper not found or not executable at: $MAVEN_CMD"
        exit 1
    fi
    
    # Check Java
    if ! command -v java &> /dev/null; then
        print_error "Java not found. Please run ./scripts/setup-env.sh first."
        exit 1
    fi
    
    case "$mode" in
        native)
            if ! command -v native-image &> /dev/null; then
                print_error "native-image not found. Install GraalVM or use --native-container instead."
                exit 1
            fi
            ;;
        native-container|docker-jvm|docker-native)
            if ! command -v docker &> /dev/null; then
                print_error "Docker not found. Please install Docker."
                exit 1
            fi
            if ! docker info &> /dev/null 2>&1; then
                print_error "Docker daemon is not running. Please start Docker."
                exit 1
            fi
            ;;
    esac
}

# Get the current version from pom.xml
get_version() {
    cd "$APP_DIR"
    "$MAVEN_CMD" help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "unknown"
}

# ==============================================================================
# Build Functions
# ==============================================================================

build_clean() {
    print_step "Cleaning build artifacts..."
    cd "$APP_DIR"
    "$MAVEN_CMD" clean $MAVEN_OPTS
    print_success "Clean completed"
}

build_jvm() {
    print_step "Building JVM version..."
    cd "$APP_DIR"
    
    local mvn_args=("package")
    
    if [[ "$SKIP_TESTS" == true ]]; then
        mvn_args+=("-DskipTests")
    fi
    
    "$MAVEN_CMD" "${mvn_args[@]}" $MAVEN_OPTS
    
    local jar_path="${APP_DIR}/target/quarkus-app/quarkus-run.jar"
    if [[ -f "$jar_path" ]]; then
        print_success "JVM build completed successfully"
        echo
        print_info "Output: ${jar_path}"
        print_info "To run: java -jar ${jar_path}"
        
        # Show artifact size
        local size
        size=$(du -h "$jar_path" | cut -f1)
        print_info "Size: $size"
    else
        print_error "Build failed - JAR not found"
        exit 1
    fi
}

build_native() {
    print_step "Building native executable with local GraalVM..."
    cd "$APP_DIR"
    
    local mvn_args=("package" "-Dnative")
    
    if [[ "$SKIP_TESTS" == true ]]; then
        mvn_args+=("-DskipTests")
    fi
    
    "$MAVEN_CMD" "${mvn_args[@]}" $MAVEN_OPTS
    
    local runner_path
    runner_path=$(find "${APP_DIR}/target" -maxdepth 1 -name "*-runner" -type f 2>/dev/null | head -1)
    
    if [[ -n "$runner_path" ]] && [[ -f "$runner_path" ]]; then
        print_success "Native build completed successfully"
        echo
        print_info "Output: ${runner_path}"
        print_info "To run: ${runner_path}"
        
        # Show artifact size
        local size
        size=$(du -h "$runner_path" | cut -f1)
        print_info "Size: $size"
    else
        print_error "Build failed - native executable not found"
        exit 1
    fi
}

build_native_container() {
    print_step "Building native executable using Docker container..."
    
    # Detect platform for container build
    local platform=""
    case "$(uname -m)" in
        arm64|aarch64)
            platform="linux/amd64"
            print_info "ARM64 detected - building for linux/amd64 platform"
            ;;
    esac
    
    cd "$APP_DIR"
    
    local mvn_args=(
        "package"
        "-Dnative"
        "-Dquarkus.native.container-build=true"
    )
    
    if [[ -n "$platform" ]]; then
        mvn_args+=("-Dquarkus.native.container-runtime-options=--platform=${platform}")
    fi
    
    if [[ "$SKIP_TESTS" == true ]]; then
        mvn_args+=("-DskipTests")
    fi
    
    "$MAVEN_CMD" "${mvn_args[@]}" $MAVEN_OPTS
    
    local runner_path
    runner_path=$(find "${APP_DIR}/target" -maxdepth 1 -name "*-runner" -type f 2>/dev/null | head -1)
    
    if [[ -n "$runner_path" ]] && [[ -f "$runner_path" ]]; then
        print_success "Native container build completed successfully"
        echo
        print_info "Output: ${runner_path}"
        print_info "Note: This binary is for Linux x86_64"
        
        # Show artifact size
        local size
        size=$(du -h "$runner_path" | cut -f1)
        print_info "Size: $size"
    else
        print_error "Build failed - native executable not found"
        exit 1
    fi
}

build_docker_jvm() {
    print_step "Building Docker image (JVM)..."
    
    # Check if JVM build exists
    local jar_path="${APP_DIR}/target/quarkus-app/quarkus-run.jar"
    if [[ ! -f "$jar_path" ]]; then
        print_info "JVM artifacts not found, building first..."
        build_jvm
    fi
    
    cd "$APP_DIR"
    
    local image_name="${ARTIFACT_NAME}"
    if [[ -n "$DOCKER_REGISTRY" ]]; then
        image_name="${DOCKER_REGISTRY}/${image_name}"
    fi
    
    print_info "Building image: ${image_name}:${DOCKER_TAG}-jvm"
    
    docker build \
        -f src/main/docker/Dockerfile.jvm \
        -t "${image_name}:${DOCKER_TAG}-jvm" \
        -t "${image_name}:${DOCKER_TAG}" \
        .
    
    print_success "Docker image built successfully"
    echo
    print_info "Image: ${image_name}:${DOCKER_TAG}-jvm"
    print_info "To run: docker run -it --rm -p 8080:8080 ${image_name}:${DOCKER_TAG}-jvm"
    
    # Show image size
    local size
    size=$(docker images --format "{{.Size}}" "${image_name}:${DOCKER_TAG}-jvm")
    print_info "Size: $size"
}

build_docker_native() {
    print_step "Building Docker image (Native)..."
    
    # Check if native build exists
    local runner_path
    runner_path=$(find "${APP_DIR}/target" -maxdepth 1 -name "*-runner" -type f 2>/dev/null | head -1)
    
    if [[ -z "$runner_path" ]] || [[ ! -f "$runner_path" ]]; then
        print_info "Native artifacts not found, building first..."
        build_native_container
    fi
    
    cd "$APP_DIR"
    
    local image_name="${ARTIFACT_NAME}"
    if [[ -n "$DOCKER_REGISTRY" ]]; then
        image_name="${DOCKER_REGISTRY}/${image_name}"
    fi
    
    print_info "Building image: ${image_name}:${DOCKER_TAG}-native"
    
    docker build \
        -f src/main/docker/Dockerfile.native \
        -t "${image_name}:${DOCKER_TAG}-native" \
        .
    
    print_success "Docker image built successfully"
    echo
    print_info "Image: ${image_name}:${DOCKER_TAG}-native"
    print_info "To run: docker run -it --rm -p 8080:8080 ${image_name}:${DOCKER_TAG}-native"
    
    # Show image size
    local size
    size=$(docker images --format "{{.Size}}" "${image_name}:${DOCKER_TAG}-native")
    print_info "Size: $size"
}

# ==============================================================================
# Main
# ==============================================================================

main() {
    local BUILD_MODE=""
    local DO_CLEAN=false
    SKIP_TESTS=false
    MAVEN_OPTS=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jvm)
                BUILD_MODE="jvm"
                shift
                ;;
            --native)
                BUILD_MODE="native"
                shift
                ;;
            --native-container)
                BUILD_MODE="native-container"
                shift
                ;;
            --docker-jvm)
                BUILD_MODE="docker-jvm"
                shift
                ;;
            --docker-native)
                BUILD_MODE="docker-native"
                shift
                ;;
            --clean)
                DO_CLEAN=true
                shift
                ;;
            --skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --verbose)
                MAVEN_OPTS="-X"
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
    
    # Default to JVM build if no mode specified
    if [[ -z "$BUILD_MODE" ]]; then
        BUILD_MODE="jvm"
    fi
    
    print_header
    
    # Check prerequisites for the chosen build mode
    check_prerequisites "$BUILD_MODE"
    
    # Get version
    local version
    version=$(get_version)
    print_info "Project version: $version"
    echo
    
    # Clean if requested
    if [[ "$DO_CLEAN" == true ]]; then
        build_clean
    fi
    
    # Execute build
    case "$BUILD_MODE" in
        jvm)
            build_jvm
            ;;
        native)
            build_native
            ;;
        native-container)
            build_native_container
            ;;
        docker-jvm)
            build_docker_jvm
            ;;
        docker-native)
            build_docker_native
            ;;
    esac
    
    echo
    print_success "Build completed!"
}

main "$@"

