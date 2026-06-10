# Monitoring

Prometheus + Loki + Promtail run on the VM. Grafana runs locally and connects via SSH tunnel.

## VM services

Deployed automatically via CI when `monitoring/**` or `docker-compose.yml` changes, or manually:

```bash
docker compose up -d prometheus loki promtail
```

Verify:
```bash
curl localhost:9091/api/v1/targets   # all 3 scrape targets should be UP
curl localhost:3100/ready            # Loki ready
```

## Local Grafana setup

**1. Start Grafana:**
```bash
docker run -d --name grafana -p 3001:3000 grafana/grafana
```

**2. Open SSH tunnel** (keep this terminal open while using Grafana):
```bash
ssh -N -L 9091:localhost:9091 -L 3100:localhost:3100 pyatkinmv@34.6.236.165
```

**3. Open Grafana** at http://localhost:3001 (login: admin / admin)

**4. Add data sources** (Connections → Data sources → Add):
- **Prometheus** — URL: `http://host.docker.internal:9091`
- **Loki** — URL: `http://host.docker.internal:3100`
- **Google Cloud Monitoring** — Authentication: paste the contents of `grafana-gcp-key.json` (see below)

> On Linux use `http://172.17.0.1:9091` and `http://172.17.0.1:3100` instead of `host.docker.internal`.

**4a. Generate the GCM key** (one-time, store locally — do not commit):
```bash
gcloud iam service-accounts keys create ~/grafana-gcp-key.json \
  --iam-account=github-ci@gen-lang-client-0721625814.iam.gserviceaccount.com
```
The `github-ci` SA has `roles/monitoring.viewer` — enough to read Cloud Monitoring metrics.

**5. Import dashboard:**
Dashboards → New → Import → upload `monitoring/dashboards/overview.json`

When importing, Grafana will ask you to map three datasources: Prometheus, Loki, and **Google Cloud Monitoring**. Select the datasource you added in step 4.

## Key metrics

| Metric | Description |
|--------|-------------|
| `dictara_build{job}` | Build info — `git_commit` and `build_time` labels (gateway, tg-bot) |
| `dictara_jobs{status}` | Job counts by status (pending/processing/done/failed/summarizing) |
| `dictara_delivery_undelivered` | Telegram deliveries pending retry (attempt_count < 10) |
| `dictara_delivery_exhausted` | Deliveries that gave up after 10 retries |
| `http_server_requests_seconds_*` | Gateway HTTP latency/rate |
| `jvm_memory_used_bytes` | JVM heap usage (gateway, tg-bot) |

> Note: Micrometer strips `_info` and `_total` suffixes from Gauge names when exporting to Prometheus.
> Registered as `dictara_build_info` / `dictara_jobs_total` in code → scraped as `dictara_build` / `dictara_jobs`.

## Transcriber monitoring (Cloud Run)

The transcriber runs on Cloud Run and is not scraped by Prometheus (would cause cold starts).
Its metrics come from **Google Cloud Monitoring** directly:

| GCM metric | Description |
|-----------|-------------|
| `run.googleapis.com/container/instance_count{state="active"}` | Instances actively processing — `> 0` means transcriber is working |
| `run.googleapis.com/container/instance_count{state="idle"}` | Instances running but idle |
| `run.googleapis.com/request_count` | Request rate |

These are visible in the "Transcriber active instances" panel in the dashboard (requires GCM datasource configured).
You can also view them at: **Cloud Console → Cloud Run → transcriber → Metrics**.

## Log queries (Loki)

Transcriber logs are **not** in Loki — they go to Google Cloud Logging (Cloud Run captures stdout/stderr automatically).
View them at: **Cloud Console → Cloud Run → transcriber → Logs**.

```
{service="gateway"}
{service="tg-bot"}
{service="gateway"} |= "ERROR"
{service="tg-bot"} |= "send failed"
{service="tg-bot"} |= "Audio received"
```
