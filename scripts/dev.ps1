<#
.SYNOPSIS
  Starts the local development environment: Postgres in Docker, then the gateway with the `local` profile.

.DESCRIPTION
  See docs/local-backend-integration.md. The gateway runs on the host (`gradle bootRun`), with a stub
  transcriber, audio kept in the database and no login. It refuses to start against anything but the
  `dictara_dev` database on localhost.

  Needs Docker and Gradle 8.7 (the version CI uses) on PATH. The gateway's own Gradle wrapper points at a
  different version, so it is not used.

.PARAMETER DbOnly
  Start (or check) the database and stop there, e.g. to run the gateway from IntelliJ instead.
#>
param(
    [switch]$DbOnly
)

# Not 'Stop': Windows PowerShell 5.1 turns anything a native command writes to stderr (docker prints its
# progress there) into an error. Failures are detected through $LASTEXITCODE instead.
$root = Split-Path -Parent $PSScriptRoot

foreach ($tool in @('docker') + $(if (-not $DbOnly) { 'gradle' })) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "'$tool' is not on PATH. Install it first (see docs/local-backend-integration.md)."
    }
}

Write-Host 'Starting Postgres (dictara-dev)...'
docker compose -f "$root\docker-compose.dev.yml" up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Could not start Postgres.' }

if ($DbOnly) {
    Write-Host 'Postgres is up on localhost:5432 (database dictara_dev). Run the gateway with the profile "local".'
    return
}

Write-Host 'Starting the gateway with the local profile (Ctrl+C stops it; Postgres keeps running)...'
Set-Location "$root\gateway"
$env:SPRING_PROFILES_ACTIVE = 'local'
gradle bootRun --console=plain
