<#
.SYNOPSIS
  Throws away the local development database and creates a fresh one.

.DESCRIPTION
  Removes the dictara-dev Docker volume (all data of the local database, including the audio kept in it)
  and starts an empty Postgres. The next gateway start applies all migrations and the seed again.
  Only the development database is touched. Stop the gateway first.

.PARAMETER Yes
  Do not ask for confirmation.
#>
param(
    [switch]$Yes
)

# Not 'Stop': Windows PowerShell 5.1 turns anything a native command writes to stderr (docker prints its
# progress there) into an error. Failures are detected through $LASTEXITCODE instead.
$root = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "'docker' is not on PATH." }

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Something is listening on port 8080 (the gateway?). Stop it before resetting the database.'
}

if (-not $Yes) {
    $answer = Read-Host 'This deletes ALL data of the local development database. Continue? (y/N)'
    if ($answer -notmatch '^(y|yes)$') { Write-Host 'Cancelled.'; return }
}

docker compose -f "$root\docker-compose.dev.yml" down -v
if ($LASTEXITCODE -ne 0) { throw 'Could not remove the database.' }
docker compose -f "$root\docker-compose.dev.yml" up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Could not start Postgres.' }

Write-Host 'Fresh empty database is up. Start the gateway (scripts/dev.ps1) to migrate and seed it.'
