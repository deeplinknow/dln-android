#!/bin/bash

# Script to manually publish a deployment that might be stuck

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to display messages
function log() {
  echo -e "${GREEN}[PUBLISH]${NC} $1"
}

function warn() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

function error() {
  echo -e "${RED}[ERROR]${NC} $1"
  exit 1
}

log "Manual deployment publishing..."

# Get deployment ID
if [ -f "deployments/last_deployment_id.txt" ]; then
  DEPLOYMENT_ID=$(cat deployments/last_deployment_id.txt)
else
  error "No deployment ID found. Have you run the deploy.sh script?"
fi

if [ -z "$DEPLOYMENT_ID" ]; then
  error "Could not find deployment ID"
fi

# Credentials live in ~/.gradle/gradle.properties (falls back to the project file)
GRADLE_PROPS_FILES="$HOME/.gradle/gradle.properties gradle.properties"
prop() { grep -h "^$1=" $GRADLE_PROPS_FILES 2>/dev/null | head -1 | cut -d'=' -f2-; }
USERNAME=$(prop centralPortalTokenUsername)
PASSWORD=$(prop centralPortalTokenPassword)

if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
  error "Could not find Central Portal credentials in ~/.gradle/gradle.properties"
fi

# Create token
TOKEN=$(echo -n "$USERNAME:$PASSWORD" | base64)

log "Deployment ID: $DEPLOYMENT_ID"

# First, check current status
log "Checking current status..."
STATUS_RESPONSE=$(curl --silent --request GET \
  --header "Authorization: Bearer $TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")

echo "Current status response:"
if echo "$STATUS_RESPONSE" | python3 -m json.tool &>/dev/null; then
  echo "$STATUS_RESPONSE" | python3 -m json.tool
else
  echo "$STATUS_RESPONSE"
fi
echo ""

# Extract status
DEPLOYMENT_STATE=$(echo $STATUS_RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)

if [ ! -z "$DEPLOYMENT_STATE" ]; then
  log "Current deployment state: $DEPLOYMENT_STATE"
  
  if [ "$DEPLOYMENT_STATE" = "PUBLISHED" ]; then
    log "Deployment is already published!"
    log "It may take 15-30 minutes to appear on Maven Central"
    log "Check: https://central.sonatype.com/artifact/com.deeplinknow/dln-android"
    exit 0
  elif [ "$DEPLOYMENT_STATE" = "VALIDATED" ]; then
    log "Deployment is validated and ready for publishing"
  elif [ "$DEPLOYMENT_STATE" = "PUBLISHING" ]; then
    log "Deployment is currently publishing. Please wait..."
    exit 0
  elif [ "$DEPLOYMENT_STATE" = "FAILED" ]; then
    warn "Deployment failed. Check the Central Portal UI for details."
    log "https://central.sonatype.com/"
    exit 1
  else
    warn "Deployment is in state: $DEPLOYMENT_STATE"
    warn "May not be ready for publishing yet"
  fi
else
  warn "Could not determine deployment state"
  warn "Attempting to publish anyway..."
fi

# Attempt to publish
log "Attempting to publish deployment..."
PUBLISH_RESPONSE=$(curl --silent --request POST \
  --header "Authorization: Bearer $TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")

echo "Publish response:"
if echo "$PUBLISH_RESPONSE" | python3 -m json.tool &>/dev/null; then
  echo "$PUBLISH_RESPONSE" | python3 -m json.tool
else
  echo "$PUBLISH_RESPONSE"
fi
echo ""

# Check for errors
ERROR_MESSAGE=$(echo $PUBLISH_RESPONSE | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
HTTP_STATUS=$(echo $PUBLISH_RESPONSE | grep -o '"httpStatus":[0-9]*' | cut -d':' -f2)

if [ ! -z "$ERROR_MESSAGE" ] && [ ! -z "$HTTP_STATUS" ] && [ "$HTTP_STATUS" -ge 400 ]; then
  error "Publishing failed: $ERROR_MESSAGE (HTTP $HTTP_STATUS)"
else
  log "Publishing request submitted"
  log "Please check the Central Portal UI for final status:"
  log "https://central.sonatype.com/"
  log ""
  log "If successful, the artifact should appear on Maven Central within 15-30 minutes:"
  log "https://central.sonatype.com/artifact/com.deeplinknow/dln-android"
fi 