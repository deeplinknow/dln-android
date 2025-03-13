#!/bin/bash

# Script to run tests for the DeepLinkNow Android SDK

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to display messages
function log() {
  echo -e "${GREEN}[TEST]${NC} $1"
}

function warn() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

function error() {
  echo -e "${RED}[ERROR]${NC} $1"
  exit 1
}

# Parse command line arguments
RUN_UNIT_TESTS=true
RUN_INSTRUMENTED_TESTS=false

while [[ "$#" -gt 0 ]]; do
  case $1 in
    --unit-only) RUN_INSTRUMENTED_TESTS=false; shift ;;
    --instrumented-only) RUN_UNIT_TESTS=false; RUN_INSTRUMENTED_TESTS=true; shift ;;
    --all) RUN_UNIT_TESTS=true; RUN_INSTRUMENTED_TESTS=true; shift ;;
    *) error "Unknown parameter: $1" ;;
  esac
done

# Run unit tests
if [ "$RUN_UNIT_TESTS" = true ]; then
  log "Running unit tests..."
  ./gradlew testDebugUnitTest testReleaseUnitTest
  
  if [ $? -ne 0 ]; then
    error "Unit tests failed. Check the error messages above."
  fi
  
  log "Unit tests completed successfully!"
  
  # Generate and open the test report
  log "Test report available at: $(pwd)/build/reports/tests/testDebugUnitTest/index.html"
fi

# Run instrumented tests
if [ "$RUN_INSTRUMENTED_TESTS" = true ]; then
  log "Running instrumented tests..."
  
  # Check if a device is connected
  DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
  if [ "$DEVICES" -eq 0 ]; then
    warn "No Android devices or emulators found. Instrumented tests will not run."
    warn "Please connect a device or start an emulator and try again."
    exit 1
  fi
  
  ./gradlew connectedAndroidTest
  
  if [ $? -ne 0 ]; then
    error "Instrumented tests failed. Check the error messages above."
  fi
  
  log "Instrumented tests completed successfully!"
  
  # Generate and open the test report
  log "Test report available at: $(pwd)/build/reports/androidTests/connected/index.html"
fi

log "All tests completed successfully!" 