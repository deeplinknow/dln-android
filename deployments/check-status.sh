#!/bin/bash

# Script to check the status of the last deployment

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to display messages
function log() {
  echo -e "${GREEN}[STATUS]${NC} $1"
}

function error() {
  echo -e "${RED}[ERROR]${NC} $1"
  exit 1
}

log "Checking deployment status..."

# Get deployment ID
if [ -f "deployments/last_deployment_id.txt" ]; then
  DEPLOYMENT_ID=$(cat deployments/last_deployment_id.txt)
else
  error "No deployment ID found. Have you run the deploy.sh script?"
fi

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

# Extract status
STATUS=$(echo $RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)

log "Deployment ID: $DEPLOYMENT_ID"
log "Status: $STATUS"
echo ""
echo "Full response:"
echo $RESPONSE | python3 -m json.tool

log "You can also check the status in the Central Portal UI:"
log "https://central.sonatype.com/" 