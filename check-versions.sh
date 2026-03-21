#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
RESET='\033[0m'

ok()   { echo -e "  ${GREEN}✓${RESET} $*"; }
fail() { echo -e "  ${RED}✗${RESET} $*"; }
info() { echo -e "  ${YELLOW}→${RESET} $*"; }
header() { echo -e "\n${BOLD}$*${RESET}"; }

check_service() {
    local name="$1"
    local url="$2"
    local response

    if ! response=$(curl -sf --max-time 3 "$url" 2>/dev/null); then
        fail "$name  —  unreachable ($url)"
        return
    fi

    local git_commit build_time
    git_commit=$(echo "$response" | grep -o '"git.commit[^"]*":"[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')
    build_time=$(echo "$response" | grep -o '"build.time[^"]*":"[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')

    # fallback: prometheus label format  git_commit="abc"
    if [ -z "$git_commit" ]; then
        git_commit=$(echo "$response" | grep -o 'git_commit="[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')
    fi
    if [ -z "$build_time" ]; then
        build_time=$(echo "$response" | grep -o 'build_time="[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')
    fi

    if [ -n "$git_commit" ]; then
        ok "$name"
        info "git_commit : ${git_commit}"
        info "build_time : ${build_time:-unknown}"
    else
        fail "$name  —  responded but no version metadata found"
        info "url: $url"
    fi
}

echo -e "${BOLD}=== Dictara Service Versions ===${RESET}"

header "Gateway  (Spring Boot Actuator)"
check_service "gateway" "http://localhost:8080/actuator/info"

header "Gateway  (health)"
response=$(curl -sf --max-time 3 "http://localhost:8080/actuator/health" 2>/dev/null) || { fail "unreachable"; response=""; }
if [ -n "$response" ]; then
    status=$(echo "$response" | grep -o '"status":"[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')
    db=$(echo "$response" | grep -o '"db":{"status":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"')
    disk_free=$(echo "$response" | grep -o '"free":[0-9]*' | grep -o '[0-9]*')
    disk_free_gb=$(echo "scale=1; ${disk_free:-0} / 1073741824" | bc)
    ok "health  status=${status}  db=${db:-unknown}  disk_free=${disk_free_gb}GB"
fi

header "Transcriber  (FastAPI + prometheus)"
check_service "transcriber" "http://localhost:8000/metrics"

header "tg-bot  (Micrometer + JDK HTTP)"
check_service "tg-bot" "http://localhost:9090/metrics"

header "App  (nginx static)"
check_service "app" "http://localhost:3000/version.json"

echo ""
