#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."

GATEWAY_URL="${1:-http://localhost:8080}"

# Export spec from running gateway
echo "Fetching spec from $GATEWAY_URL/v3/api-docs.yaml ..."
curl -sf "$GATEWAY_URL/v3/api-docs.yaml" > openapi.yaml
echo "Spec saved to openapi.yaml"

# Generate Dart models for Flutter apps
echo "Generating Dart client -> app/lib/generated/ ..."
MSYS_NO_PATHCONV=1 docker run --rm -v "${PWD}:/local" openapitools/openapi-generator-cli:latest generate \
  -i /local/openapi.yaml \
  -g dart \
  -o /local/app/lib/generated \
  --additional-properties=pubName=dictara_api

# Generate Kotlin DTOs for tg-bot
echo "Generating Kotlin client -> tg-bot/generated/ ..."
MSYS_NO_PATHCONV=1 docker run --rm -v "${PWD}:/local" openapitools/openapi-generator-cli:latest generate \
  -i /local/openapi.yaml \
  -g kotlin \
  -o /local/tg-bot/generated \
  --additional-properties=packageName=com.dictara.generated,library=jvm-okhttp4,serializationLibrary=jackson

echo ""
echo "Done. Commit openapi.yaml + app/lib/generated/ + tg-bot/generated/ together."
