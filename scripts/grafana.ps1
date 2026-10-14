# Opens local Grafana on prod data: SSH tunnel to Prometheus/Loki, Grafana container, datasources, dashboard.
# Idempotent: run it again any time. Needs Docker Desktop running and the key ~/.ssh/google_compute_engine
# (docs/ops/runbook.md, "Access"). Open http://localhost:3001 (admin / admin, bound to 127.0.0.1 only).
$ErrorActionPreference = 'Stop'
$key = Join-Path $env:USERPROFILE '.ssh\google_compute_engine'
$vm = 'pyatkinmv@34.6.236.165'
$grafana = 'http://localhost:3001'
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin:admin')) }

function Test-Port($port) {
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $port); $true } catch { $false } finally { $c.Close() }
}

if (-not (Test-Port 9091)) {
    Start-Process ssh -WindowStyle Hidden -ArgumentList '-N', '-L', '9091:localhost:9091', '-L', '3100:localhost:3100',
        '-o', 'ServerAliveInterval=30', '-o', 'ExitOnForwardFailure=yes', '-i', $key, $vm
    Start-Sleep 4
    if (-not (Test-Port 9091)) { throw 'SSH tunnel did not come up (key missing? VM reachable?)' }
}

if (-not (docker ps -a --filter name=^grafana$ --format '{{.Names}}')) {
    docker run -d --name grafana -p 127.0.0.1:3001:3000 -v grafana-data:/var/lib/grafana --restart unless-stopped grafana/grafana | Out-Null
} elseif (-not (docker ps --filter name=^grafana$ --format '{{.Names}}')) {
    docker start grafana | Out-Null
}
for ($i = 0; $i -lt 30; $i++) {
    try { Invoke-RestMethod "$grafana/api/health" | Out-Null; break } catch { Start-Sleep 2 }
}

function Add-Datasource($body) {
    try { Invoke-RestMethod "$grafana/api/datasources" -Method Post -Headers $auth -ContentType 'application/json' -Body ($body | ConvertTo-Json) | Out-Null }
    catch { if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw } }
}
Add-Datasource @{ name = 'Prometheus (prod)'; uid = 'prometheus'; type = 'prometheus'; access = 'proxy'; url = 'http://host.docker.internal:9091'; isDefault = $true }
Add-Datasource @{ name = 'Loki (prod)'; uid = 'loki'; type = 'loki'; access = 'proxy'; url = 'http://host.docker.internal:3100' }

$dash = Get-Content (Join-Path $PSScriptRoot '..\monitoring\dashboards\overview.json') -Raw | ConvertFrom-Json
$dash.PSObject.Properties.Remove('id')
$import = @{ dashboard = $dash; overwrite = $true; inputs = @(
    @{ name = 'DS_PROMETHEUS'; type = 'datasource'; pluginId = 'prometheus'; value = 'prometheus' },
    @{ name = 'DS_LOKI'; type = 'datasource'; pluginId = 'loki'; value = 'loki' },
    @{ name = 'DS_GCM'; type = 'datasource'; pluginId = 'stackdriver'; value = 'gcm' }) }
Invoke-RestMethod "$grafana/api/dashboards/import" -Method Post -Headers $auth -ContentType 'application/json' -Body ($import | ConvertTo-Json -Depth 100) | Out-Null

Write-Host "Ready: $grafana/d/dictara-overview"
