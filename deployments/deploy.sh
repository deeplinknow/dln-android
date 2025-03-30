#!/bin/bash

# Comprehensive deployment script for DeepLinkNow Android SDK
# This script:
# 1. Increments the version number
# 2. Builds the project
# 3. Creates a bundle for Central Portal
# 4. Uploads the bundle to Central Portal
# 5. Publishes the deployment

# Exit on any error
set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to display messages
function log() {
  echo -e "${GREEN}[DEPLOY]${NC} $1"
}

function warn() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

function error() {
  echo -e "${RED}[ERROR]${NC} $1"
  exit 1
}

# Function to check GPG setup
function check_gpg() {
  log "Checking GPG setup..."
  
  # Export GPG_TTY
  export GPG_TTY=$(tty)
  
  # Check if pinentry-mac is installed
  if ! command -v pinentry-mac &> /dev/null; then
    warn "pinentry-mac is not installed. Installing it now..."
    brew install pinentry-mac
  fi
  
  # Update the GPG agent configuration
  mkdir -p ~/.gnupg
  chmod 700 ~/.gnupg
  echo "pinentry-program /opt/homebrew/bin/pinentry-mac" > ~/.gnupg/gpg-agent.conf
  echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
  
  # Restart the GPG agent
  gpgconf --kill gpg-agent
  
  # Test GPG signing
  echo "test" | gpg --clearsign > /dev/null 2>&1
  if [ $? -ne 0 ]; then
    error "GPG signing test failed. Please check your GPG configuration."
  fi
  
  # Check if signing properties are in gradle.properties
  if ! grep -q "signing.keyId=" gradle.properties || \
     ! grep -q "signing.password=" gradle.properties || \
     ! grep -q "signing.key=" gradle.properties; then
    error "Missing signing properties in gradle.properties. Please add signing.keyId, signing.password, and signing.key."
    log "To generate signing.key, run: gpg --export-secret-keys --armor 25EE847B > /tmp/private.key"
    log "Then copy the contents of /tmp/private.key into your gradle.properties file as signing.key=<paste-key-here>"
    exit 1
  fi
  
  log "GPG setup verified."
}

# Function to increment version
function increment_version() {
  log "Incrementing version number..."
  
  # Get current version from build.gradle.kts - fixing the version extraction to handle newlines
  CURRENT_VERSION=$(grep -o 'version = "[^"]*"' build.gradle.kts | head -1 | cut -d'"' -f2)
  
  if [ -z "$CURRENT_VERSION" ]; then
    error "Could not find version in build.gradle.kts"
  fi
  
  log "Current version: $CURRENT_VERSION"
  
  # Parse version components
  IFS='.' read -r -a VERSION_PARTS <<< "$CURRENT_VERSION"
  MAJOR=${VERSION_PARTS[0]}
  MINOR=${VERSION_PARTS[1]}
  PATCH=${VERSION_PARTS[2]}
  
  # Increment patch version
  PATCH=$((PATCH + 1))
  
  # Create new version
  NEW_VERSION="$MAJOR.$MINOR.$PATCH"
  log "New version: $NEW_VERSION"
  
  # Update version in build.gradle.kts - using perl instead of sed for better multiline handling
  perl -i -pe "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" build.gradle.kts
  
  # Also update the version in the publication section
  perl -i -pe "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" build.gradle.kts
  
  log "Version updated to $NEW_VERSION"
  return 0
}

# Function to build the project
function build_project() {
  log "Building project..."
  
  # Verify signing configuration first
  ./gradlew verifySigningConfig
  
  # Clean first
  ./gradlew clean
  
  # Publish to local Maven repository (this will trigger signing)
  ./gradlew publishReleasePublicationToLocalMavenRepository
  
  if [ $? -ne 0 ]; then
    error "Build failed. Check the error messages above."
  fi
  
  # Create the bundle
  ./gradlew createCentralPortalBundle
  
  if [ $? -ne 0 ]; then
    error "Bundle creation failed. Check the error messages above."
  fi
  
  # Verify that signature files exist in the local Maven repo
  # Make sure we get the exact version from the current build.gradle.kts file
  VERSION=$(grep -o 'version = "[^"]*"' build.gradle.kts | head -1 | cut -d'"' -f2)
  ARTIFACT_PATH="build/local-maven-repo/com/deeplinknow/dln-android/${VERSION}"
  
  log "Checking for artifacts and signatures in: ${ARTIFACT_PATH}"
  
  # Define required files
  REQUIRED_FILES=(
    "dln-android-${VERSION}.pom:Module POM"
    "dln-android-${VERSION}.aar:Android Archive"
    "dln-android-${VERSION}-sources.jar:Sources JAR"
    "dln-android-${VERSION}-javadoc.jar:Javadoc JAR"
    "dln-android-${VERSION}.module:Gradle Module Metadata"
  )
  
  MISSING_FILES=0
  MISSING_SIGNATURES=0
  
  # Print all files in directory for debugging
  log "Directory contents:"
  ls -la "${ARTIFACT_PATH}" || {
    warn "Directory not found: ${ARTIFACT_PATH}"
    mkdir -p "${ARTIFACT_PATH}"
    warn "Created directory: ${ARTIFACT_PATH}"
  }
  echo ""
  
  # Check each required file and its signature
  log "Checking required files and signatures:"
  for ENTRY in "${REQUIRED_FILES[@]}"; do
    FILE="${ENTRY%%:*}"
    DESCRIPTION="${ENTRY#*:}"
    
    printf "%-40s" "${DESCRIPTION}:"
    
    if [ -f "${ARTIFACT_PATH}/${FILE}" ]; then
      echo -n "✓ "
    else
      echo -n "✗ "
      MISSING_FILES=$((MISSING_FILES + 1))
      warn "Missing file: ${FILE}"
    fi
    
    if [ -f "${ARTIFACT_PATH}/${FILE}.asc" ]; then
      echo "✓ (signed)"
    else
      echo "✗ (unsigned)"
      MISSING_SIGNATURES=$((MISSING_SIGNATURES + 1))
      warn "Missing signature: ${FILE}.asc"
    fi
  done
  
  echo ""
  if [ $MISSING_FILES -gt 0 ] || [ $MISSING_SIGNATURES -gt 0 ]; then
    error "Found $MISSING_FILES missing files and $MISSING_SIGNATURES missing signatures. Cannot proceed with upload."
  fi
  
  # Verify signatures
  log "Verifying signatures..."
  for ENTRY in "${REQUIRED_FILES[@]}"; do
    FILE="${ENTRY%%:*}"
    if [ -f "${ARTIFACT_PATH}/${FILE}.asc" ]; then
      gpg --verify "${ARTIFACT_PATH}/${FILE}.asc" "${ARTIFACT_PATH}/${FILE}" > /dev/null 2>&1
      if [ $? -eq 0 ]; then
        log "✓ Valid signature for ${FILE}"
      else
        error "Invalid signature for ${FILE}"
      fi
    fi
  done
  
  BUNDLE_PATH="$(pwd)/build/central-portal/central-portal-bundle.zip"
  log "Bundle created at: $BUNDLE_PATH"
  
  # Check bundle contents
  log "Verifying bundle contents..."
  unzip -l "$BUNDLE_PATH" | grep ".asc" || error "No signature files found in bundle!"
  
  return 0
}

# Function to upload bundle to Central Portal
function upload_bundle() {
  log "Uploading bundle to Central Portal..."
  
  # Get credentials from gradle.properties
  USERNAME=$(grep "centralPortalTokenUsername=" gradle.properties | cut -d'=' -f2)
  PASSWORD=$(grep "centralPortalTokenPassword=" gradle.properties | cut -d'=' -f2)
  
  if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    error "Could not find Central Portal credentials in gradle.properties"
  fi
  
  # Create token
  TOKEN=$(echo -n "$USERNAME:$PASSWORD" | base64)
  
  # Upload bundle
  RESPONSE=$(curl --silent --request POST \
    --header "Authorization: Bearer $TOKEN" \
    --form bundle=@build/central-portal/central-portal-bundle.zip \
    https://central.sonatype.com/api/v1/publisher/upload)
  
  # Check if response is a UUID (which appears to be the deployment ID directly)
  if [[ $RESPONSE =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    DEPLOYMENT_ID=$RESPONSE
  else
    # Try to extract deployment ID from JSON response
    DEPLOYMENT_ID=$(echo $RESPONSE | grep -o '"deploymentId":"[^"]*"' | cut -d'"' -f4)
  fi
  
  if [ -z "$DEPLOYMENT_ID" ]; then
    error "Failed to upload bundle. Response: $RESPONSE"
  fi
  
  log "Bundle uploaded successfully. Deployment ID: $DEPLOYMENT_ID"
  echo $DEPLOYMENT_ID > deployments/last_deployment_id.txt
}

# Function to publish deployment
function publish_deployment() {
  log "Checking deployment status before publishing..."
  
  # Get deployment ID
  DEPLOYMENT_ID=$(cat deployments/last_deployment_id.txt)
  
  if [ -z "$DEPLOYMENT_ID" ]; then
    error "Could not find deployment ID"
  fi
  
  # Get credentials from gradle.properties
  USERNAME=$(grep "centralPortalTokenUsername=" gradle.properties | cut -d'=' -f2)
  PASSWORD=$(grep "centralPortalTokenPassword=" gradle.properties | cut -d'=' -f2)
  
  if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    error "Could not find Central Portal credentials in gradle.properties"
  fi
  
  # Create token
  TOKEN=$(echo -n "$USERNAME:$PASSWORD" | base64)
  
  # Check status first
  STATUS_RESPONSE=$(curl --silent --request GET \
    --header "Authorization: Bearer $TOKEN" \
    "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
  
  # Extract status
  DEPLOYMENT_STATE=$(echo $STATUS_RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)
  
  if [ -z "$DEPLOYMENT_STATE" ]; then
    warn "Could not determine deployment state. Response: $STATUS_RESPONSE"
    log "Waiting 30 seconds before trying to publish..."
    sleep 30
  elif [ "$DEPLOYMENT_STATE" != "VALIDATED" ]; then
    warn "Deployment is not in VALIDATED state (current state: $DEPLOYMENT_STATE). Waiting 30 seconds..."
    log "Full status response: $STATUS_RESPONSE"
    sleep 30
    
    # Check status again
    STATUS_RESPONSE=$(curl --silent --request GET \
      --header "Authorization: Bearer $TOKEN" \
      "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
    
    DEPLOYMENT_STATE=$(echo $STATUS_RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)
    
    if [ "$DEPLOYMENT_STATE" != "VALIDATED" ]; then
      warn "Deployment is still not in VALIDATED state after waiting. Current state: $DEPLOYMENT_STATE"
      warn "You may need to check the deployment status manually and publish later."
      warn "Deployment ID: $DEPLOYMENT_ID"
      return 1
    fi
  fi
  
  log "Publishing deployment..."
  
  # Publish deployment
  RESPONSE=$(curl --silent --request POST \
    --header "Authorization: Bearer $TOKEN" \
    "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
  
  # Check for errors in the response
  ERROR_MESSAGE=$(echo $RESPONSE | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
  
  if [ ! -z "$ERROR_MESSAGE" ]; then
    warn "Publishing request returned an error: $ERROR_MESSAGE"
    warn "Full response: $RESPONSE"
    return 1
  else
    log "Publishing request sent successfully. Response: $RESPONSE"
    return 0
  fi
}

# Function to check deployment status
function check_status() {
  log "Checking deployment status..."
  
  # Get deployment ID
  DEPLOYMENT_ID=$(cat deployments/last_deployment_id.txt)
  
  if [ -z "$DEPLOYMENT_ID" ]; then
    error "Could not find deployment ID"
  fi
  
  # Get credentials from gradle.properties
  USERNAME=$(grep "centralPortalTokenUsername=" gradle.properties | cut -d'=' -f2)
  PASSWORD=$(grep "centralPortalTokenPassword=" gradle.properties | cut -d'=' -f2)
  
  if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    error "Could not find Central Portal credentials in gradle.properties"
  fi
  
  # Create token
  TOKEN=$(echo -n "$USERNAME:$PASSWORD" | base64)
  
  # Check status
  RESPONSE=$(curl --silent --request GET \
    --header "Authorization: Bearer $TOKEN" \
    "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
  
  # Check for errors in the response
  ERROR_MESSAGE=$(echo $RESPONSE | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
  HTTP_STATUS=$(echo $RESPONSE | grep -o '"httpStatus":[0-9]*' | cut -d':' -f2)
  
  if [ ! -z "$ERROR_MESSAGE" ] && [ "$HTTP_STATUS" -ge 400 ]; then
    warn "Deployment status check returned an error: $ERROR_MESSAGE (HTTP $HTTP_STATUS)"
    warn "Full response: $RESPONSE"
    return 1
  else
    # Extract status
    DEPLOYMENT_STATE=$(echo $RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)
    
    if [ ! -z "$DEPLOYMENT_STATE" ]; then
      log "Deployment status: $DEPLOYMENT_STATE"
    else
      log "Could not determine deployment state from response"
    fi
    
    # Pretty print the full response
    if echo "$RESPONSE" | python3 -m json.tool &>/dev/null; then
      echo "$RESPONSE" | python3 -m json.tool
    else
      log "Raw response: $RESPONSE"
    fi
    
    return 0
  fi
}

# Main execution
log "Starting deployment process..."

# Check GPG setup
check_gpg

# Increment version
increment_version

# Build project
build_project

# Upload bundle
upload_bundle

# Publish deployment
if ! publish_deployment; then
  warn "There were issues with publishing the deployment."
  warn "Please check the status manually and publish when ready."
else
  log "Deployment published successfully."
fi

# Check status
if ! check_status; then
  warn "There were issues checking the deployment status."
  warn "Please check the status manually using: ./deployments/check-status.sh"
  exit 1
fi

log "Deployment process completed."
log "You can check the status again by running: ./deployments/check-status.sh" 