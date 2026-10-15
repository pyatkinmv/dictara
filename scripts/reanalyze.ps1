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
# The admin key is read on the VM from the gateway's own environment, so it never passes through this machine.
$header = '-H "X-Admin-Key: $(docker exec dictara-gateway-1 printenv ADMIN_KEY)"'
$curl = 'curl -s -w "\nHTTP %{http_code}\n" -H "Content-Type: application/json"'

function Invoke-Remote([string]$command, [string]$body) {
    if ($body) { $body | ssh -i $key $vm $command } else { ssh -i $key $vm $command }
}

switch ($Action) {
    'status' { Invoke-Remote "$curl $header http://localhost:8080/admin/reanalyze/status" $null }
    'stop' { Invoke-Remote "$curl $header -X POST http://localhost:8080/admin/reanalyze/stop" $null }
    default {
        if (-not $UserId -or -not $IdsFile) { throw '-UserId and -IdsFile are required for dryrun and run' }
        $ids = Get-Content $IdsFile | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        $body = @{ user_id = $UserId; ids = @($ids); dry_run = ($Action -eq 'dryrun'); force = [bool]$Force } | ConvertTo-Json -Compress
        Write-Host "$Action : $(@($ids).Count) ids"
        Invoke-Remote "$curl $header -X POST --data-binary @- http://localhost:8080/admin/reanalyze" $body
    }
}
