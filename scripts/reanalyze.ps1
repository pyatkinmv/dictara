<#
.SYNOPSIS
  Drives the one-off backlog run on prod: asks Gemini again about old transcriptions (summary, title, tags, speakers).

.DESCRIPTION
  Talks to the gateway's /admin/reanalyze on the VM (localhost:8080, over ssh; the key is read inside the gateway
  container and never shown). See docs/ops/runbook.md, "Backlog run", and gateway/CLAUDE.md. Every real call costs money:
  always look at -Action dryrun first (it asks Gemini nothing and says what the ids would cost).

.PARAMETER Action
  dryrun  what the ids in -IdsFile would do and cost; nothing is asked
  run     start the batch (one at a time, in the background on the VM)
  status  how far it is, what it used and cost so far
  stop    stop after the transcription it is on

.PARAMETER UserId
  Whose transcriptions (every id has to be theirs).

.PARAMETER IdsFile
  A text file with one transcription id per line.

.PARAMETER Force
  Also those the backlog run already did.

.EXAMPLE
  .\scripts\reanalyze.ps1 -Action dryrun -UserId <uuid> -IdsFile ids10.txt
  .\scripts\reanalyze.ps1 -Action run    -UserId <uuid> -IdsFile ids10.txt
  .\scripts\reanalyze.ps1 -Action status
#>
param(
    [Parameter(Mandatory)][ValidateSet('dryrun', 'run', 'status', 'stop')][string]$Action,
    [string]$UserId,
    [string]$IdsFile,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$key = Join-Path $env:USERPROFILE '.ssh\google_compute_engine'
$vm = 'pyatkinmv@34.6.236.165'

# The commands go to the VM as a script on stdin, so that no quoting of ssh gets between us and curl. The admin key is
# read there, from the gateway's own environment, and never passes through this machine.
$prelude = @'
KEY=$(docker exec dictara-gateway-1 printenv ADMIN_KEY)
CURL() { curl -s -w '\nHTTP %{http_code}\n' -H "X-Admin-Key: $KEY" "$@"; }
'@

function Invoke-Remote([string]$commands) {
    # A file with LF line endings and no byte-order mark: piping a string would add a BOM before the first line (bash then
    # takes `KEY=...` for a command and prints it, key included) and CRLF line endings.
    $script = (($prelude + "`n" + $commands + "`n") -replace "`r`n", "`n")
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $script, (New-Object System.Text.UTF8Encoding($false)))
        cmd /c "ssh -i `"$key`" $vm bash -s < `"$tmp`""
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}

switch ($Action) {
    'status' { Invoke-Remote 'CURL http://localhost:8080/admin/reanalyze/status' }
    'stop' { Invoke-Remote 'CURL -X POST http://localhost:8080/admin/reanalyze/stop' }
    default {
        if (-not $UserId -or -not $IdsFile) { throw '-UserId and -IdsFile are required for dryrun and run' }
        $ids = @(Get-Content $IdsFile | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $body = @{ user_id = $UserId; ids = $ids; dry_run = ($Action -eq 'dryrun'); force = [bool]$Force } | ConvertTo-Json -Compress
        Write-Host "$Action : $($ids.Count) ids"
        Invoke-Remote ("CURL -X POST -H 'Content-Type: application/json' --data-binary @- http://localhost:8080/admin/reanalyze <<'JSON'`n$body`nJSON")
    }
}
