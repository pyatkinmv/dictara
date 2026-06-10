# Monitoring

Prometheus + Loki + Promtail run on the VM. Grafana runs locally and connects via SSH tunnel.
The transcriber runs on Cloud Run — its metrics come from Google Cloud Monitoring, not Prometheus
(direct scraping would cause cold starts). See [docs/deployment.md](../docs/deployment.md) for
production infrastructure details.

---

## Architecture

```
VM (Docker)                         Local machine
──────────────────────────────      ──────────────────────────────
gateway      ──metrics──▶ Prometheus ◀── SSH tunnel ── Grafana
tg-bot       ──metrics──▶           ◀── SSH tunnel ── (port 9091)
app-material ──metrics──▶
node-exporter──metrics──▶
                                    Grafana also reads Cloud Monitoring
Docker logs  ──▶ Promtail ──▶ Loki ◀── SSH tunnel ── (port 3100)
                                     directly (GCM datasource, no tunnel)
```

---

## First-time setup from scratch

### 1. VM services (auto-deployed by CI)

Started automatically when `monitoring/**` or `docker-compose.yml` changes.
To start manually on the VM:

```bash
docker compose up -d prometheus loki promtail node-exporter
```

Verify:
```bash
curl localhost:9091/api/v1/targets   # scrape targets should be UP
curl localhost:3100/ready            # Loki ready
```

### 2. Generate GCM key for Grafana (one-time, store locally — do not commit)

```bash
gcloud iam service-accounts keys create ~/grafana-gcp-key.json \
  --iam-account=github-ci@gen-lang-client-0721625814.iam.gserviceaccount.com
```

The `github-ci` SA has `roles/monitoring.viewer` — sufficient to read all Cloud Monitoring metrics.

### 3. Start Grafana locally

```bash
docker run -d --name grafana -p 3001:3000 grafana/grafana
```

### 4. Open SSH tunnel (keep this terminal open while using Grafana)

```bash
ssh -N -L 9091:localhost:9091 -L 3100:localhost:3100 pyatkinmv@34.6.236.165
```

### 5. Open Grafana at http://localhost:3001 (login: admin / admin)

### 6. Add data sources (Connections → Data sources → Add)

| Datasource | Settings |
|-----------|---------|
| **Prometheus** | URL: `http://host.docker.internal:9091` |
| **Loki** | URL: `http://host.docker.internal:3100` |
| **Google Cloud Monitoring** | Authentication: paste contents of `~/grafana-gcp-key.json` |

> On Linux replace `host.docker.internal` with `http://172.17.0.1`.

### 7. Import dashboard

Dashboards → New → Import → upload `monitoring/dashboards/overview.json`

Grafana will ask to map three datasources: **Prometheus**, **Loki**, **Google Cloud Monitoring**.
Select the datasources added in step 6.

---

## Dashboard panels

### Deployments section

| Panel | Source | Description |
|-------|--------|-------------|
| Service health | Prometheus | `up` for gateway / tg-bot / app-material — green UP / red DOWN |
| transcriber | GCM | `instance_count{state="active"}` — grey IDLE (scaled to 0) / green ACTIVE (processing) |
| Service health history | Prometheus | Timeline of UP/DOWN states |
| Current deployments | Prometheus | git_commit + build_time for gateway, tg-bot, app-material |

### Job Pipeline section

| Panel | Source | Description |
|-------|--------|-------------|
| Queue depth (pending) | Prometheus | `dictara_jobs{status="pending"}` |
| Jobs by status | Prometheus | All statuses: pending / processing / summarizing / done / failed |

### Gateway HTTP section

| Panel | Source | Description |
|-------|--------|-------------|
| Business request rate | Prometheus | Req/s for user-facing endpoints |
| Latency p99 | Prometheus | `http_server_requests_seconds` p99 |
| Technical request rate | Prometheus | Req/s for internal endpoints |

### JVM section

| Panel | Source | Description |
|-------|--------|-------------|
| JVM heap used | Prometheus | `jvm_memory_used_bytes` for gateway and tg-bot |
| JVM GC pause | Prometheus | GC pause duration rate |

### Infrastructure section

| Panel | Source | Description |
|-------|--------|-------------|
| Host CPU / RAM / Swap / Disk | Prometheus | node-exporter VM metrics |
| Process CPU | Prometheus | `process_cpu_usage` for gateway and tg-bot |
| Transcriber active instances | GCM | `instance_count{state="active"}` over time |

### Transcriber (Cloud Run) section

All panels use Google Cloud Monitoring. Data appears with ~2 min delay (GCM ingest delay).
`No data` when transcriber is scaled to 0 (idle) — this is normal.

| Panel | Metric | Description |
|-------|--------|-------------|
| GPU utilization | `container/gpu/utilizations` p99 | L4 GPU load during transcription |
| Request latency p99 | `request_latencies` p99 | Time from request entering container to response |
| Startup latency p99 (cold start) | `container/startup_latencies` p99 | Time to start a new container instance |

### Logs section

| Panel | Source | Description |
|-------|--------|-------------|
| All service logs | Loki | gateway + tg-bot Docker logs |

---

## Key Prometheus metrics

| Metric | Description |
|--------|-------------|
| `dictara_build{job}` | `git_commit` and `build_time` labels (gateway, tg-bot) |
| `dictara_jobs{status}` | Job counts by status (pending / processing / summarizing / done / failed) |
| `dictara_delivery_undelivered` | Telegram deliveries pending retry (attempt_count < 10) |
| `dictara_delivery_exhausted` | Deliveries that gave up after 10 retries |
| `http_server_requests_seconds_*` | Gateway HTTP latency / rate |
| `jvm_memory_used_bytes` | JVM heap usage (gateway, tg-bot) |

> Micrometer strips `_info` / `_total` suffixes: registered as `dictara_build_info` / `dictara_jobs_total` in code, scraped as `dictara_build` / `dictara_jobs`.

---

## Transcriber logs

Transcriber logs are **not** in Loki — Cloud Run captures stdout/stderr to Google Cloud Logging automatically.

View at: **Cloud Console → Cloud Run → transcriber → Logs**
or: `gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=transcriber" --limit=50`

---

## Useful Loki queries

```
{service="gateway"}
{service="tg-bot"}
{service="gateway"} |= "ERROR"
{service="tg-bot"} |= "send failed"
{service="tg-bot"} |= "Audio received"
```
