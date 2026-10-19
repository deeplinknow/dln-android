#!/bin/bash

# Script to monitor deployment and auto-publish when ready

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

function log() {
  echo -e "${GREEN}[MONITOR]${NC} $1"
}

function warn() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Get deployment ID
DEPLOYMENT_ID=$(cat deployments/last_deployment_id.txt)
USERNAME=$(grep "centralPortalTokenUsername=" gradle.properties | cut -d'=' -f2)
PASSWORD=$(grep "centralPortalTokenPassword=" gradle.properties | cut -d'=' -f2)
TOKEN=$(echo -n "$USERNAME:$PASSWORD" | base64)

log "Monitoring deployment: $DEPLOYMENT_ID"
log "Press Ctrl+C to stop monitoring"
echo ""

# Monitor every 2 minutes
while true; do
  TIMESTAMP=$(date '+%H:%M:%S')
  echo -n "[$TIMESTAMP] Checking status... "
  
  # Check status
  RESPONSE=$(curl --silent --request GET \
    --header "Authorization: Bearer $TOKEN" \
    "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
  
  # Check for HTTP 500 errors
  HTTP_STATUS=$(echo $RESPONSE | grep -o '"httpStatus":[0-9]*' | cut -d':' -f2)
  if [ "$HTTP_STATUS" = "500" ]; then
    echo "API Error (HTTP 500) - will retry"
  else
    # Extract deployment state
    STATE=$(echo $RESPONSE | grep -o '"deploymentState":"[^"]*"' | cut -d'"' -f4)
    
    if [ ! -z "$STATE" ]; then
      echo "State: $STATE"
      
      case $STATE in
        "VALIDATED")
          log "🎉 Deployment is VALIDATED! Attempting to publish..."
          
          PUBLISH_RESPONSE=$(curl --silent --request POST \
            --header "Authorization: Bearer $TOKEN" \
            "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID")
          
          PUBLISH_ERROR=$(echo $PUBLISH_RESPONSE | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
          if [ -z "$PUBLISH_ERROR" ]; then
            log "✅ Publishing request submitted successfully!"
            log "Monitor for PUBLISHED state and then check Maven Central in 15-30 minutes"
          else
            warn "Publishing failed: $PUBLISH_ERROR"
          fi
          ;;
        "PUBLISHED")
          log "🚀 Deployment is PUBLISHED!"
          log "Check Maven Central in 15-30 minutes:"
          log "https://central.sonatype.com/artifact/com.deeplinknow/dln-android/1.0.17"
          log "https://mvnrepository.com/artifact/com.deeplinknow/dln-android"
          exit 0
          ;;
        "FAILED")
          warn "❌ Deployment FAILED. Check Central Portal UI for details:"
          warn "https://central.sonatype.com/"
          exit 1
          ;;
        "PUBLISHING")
          log "🔄 Currently publishing... please wait"
          ;;
        *)
          echo "($STATE - waiting for VALIDATED)"
          ;;
      esac
    else
      echo "Could not determine state"
    fi
  fi
  
  # Wait 2 minutes before next check
  sleep 120
done 