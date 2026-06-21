# Production Deployment

## Topology

In production, most services run as Docker containers on a single VM. The transcriber runs on Google Cloud Run (GPU) to avoid keeping a GPU-equipped VM running 24/7.

| Service | Location | Notes |
|---------|----------|-------|
| `gateway` | VM — Docker | Spring Boot API, auth, orchestration |
| `postgres` | VM — Docker | Persistent volume `postgres-data` |
| `tg-bot` | VM — Docker | Long-polling; outbound only |
| `telegram-bot-api` | VM — Docker | Local Bot API for >20 MB Telegram files |
| `app-material` | VM — Docker | Flutter web, served by nginx on port 3000 |
| `transcriber` | Google Cloud Run | GPU L4; called by gateway via `TRANSCRIBER_URL` |
| prometheus / loki / promtail / node-exporter | VM — Docker | Observability stack |

The transcriber has `profiles: [local]` in `docker-compose.yml` so it does **not** start with a plain `docker compose up`. To run it locally instead of Cloud Run, see below.

---

## Transcriber on Cloud Run

### Image

Built from `./transcriber/Dockerfile` and pushed to Google Artifact Registry:

```bash
docker build -t REGION-docker.pkg.dev/PROJECT/REPO/transcriber:latest ./transcriber
docker push REGION-docker.pkg.dev/PROJECT/REPO/transcriber:latest
```

### Deploying to Cloud Run

```bash
gcloud run deploy transcriber \
  --image REGION-docker.pkg.dev/PROJECT/REPO/transcriber:latest \
  --region REGION \
  --no-allow-unauthenticated \
  --gpu 1 --gpu-type nvidia-l4 \
  --cpu 8 --memory 32Gi \
  --timeout 3600 \
  --set-env-vars HF_TOKEN=...,WHISPER_MODELS=small,turbo
```

### Authentication (OIDC)

Cloud Run requires a valid OIDC token on every request. The gateway's service account must have the `roles/run.invoker` IAM role on the Cloud Run service. The gateway fetches a token automatically via Application Default Credentials (ADC) when `TRANSCRIBER_URL` points to a `*.run.app` URL.

### Setting TRANSCRIBER_URL on the VM

Add to `.env` on the VM (or pass via docker compose env):

```
TRANSCRIBER_URL=https://transcriber-XXXXXXXXXX-REGION.run.app
```

---

## GCS Audio Storage

### Why

Cloud Run enforces a hard 32 MiB HTTP request body limit. Streaming large audio files directly to the transcriber would return `413`. Instead, the gateway uploads files to GCS and submits the transcriber job by `gs://` reference — the transcriber downloads the object directly from GCS, bypassing the HTTP body entirely.

### Bucket

- **Name**: `gen-lang-client-0721625814-uploads`
- **Path pattern**: `gs://<bucket>/audio/<uuid>/<filename>`
- **Lifecycle rule**: objects auto-deleted after **90 days** (configured on the bucket, not in application code)

### Enabling

Set `GCS_UPLOADS_BUCKET` in gateway environment:

```
GCS_UPLOADS_BUCKET=gen-lang-client-0721625814-uploads
```

`GCS_UPLOADS_BUCKET` is **required** — the legacy in-DB BLOB path (`audio_content` table) has been removed. Without it, audio uploads will fail.

---

## GCS Maintenance Jobs

Two background jobs keep the GCS bucket lean. Every run is recorded in the `job_runs` table (`job_name`, `status`, `rows_affected`, `finished_at`).

| Job | Schedule (UTC) | What it does |
|-----|---------------|--------------|
| `dedup_storage_uris` | Daily 03:00 | Finds `audio_meta` rows with the same `content_hash` and rewrites them all to the oldest `storage_uri`, so duplicate GCS objects become unreferenced and are eventually deleted by the bucket lifecycle rule |
| `cleanup_orphaned_gcs_objects` | Weekly Sunday 04:00 | Lists all objects in the bucket, deletes any not referenced by `audio_meta.storage_uri`. Skips objects younger than 1 hour (grace period for in-flight uploads) |

These are no-ops when `GCS_UPLOADS_BUCKET` is not set.

---

## Upload Deduplication

When a file is uploaded, the gateway computes a SHA-256 hash of its content and stores it in `audio_meta.content_hash`. On subsequent uploads of the same file (same hash, same model/diarize settings), the existing `job_id` is returned immediately — no re-transcription, no new GCS object.

The daily `dedup_storage_uris` job further consolidates any rows that share a `content_hash` but ended up with different `storage_uri` values (e.g. uploaded before dedup was in place), pointing them all to the oldest URI.

---

## CI/CD Pipeline

All pushes to `master` trigger: **test → build-images → deploy**.

### gateway, tg-bot, app-material

Images are built in GitHub Actions and pushed to GitHub Container Registry. The VM never builds images — it only pulls and runs them.

**Path-based detection** — only changed services are rebuilt:

| Source dir | Image |
|------------|-------|
| `gateway/**` | `ghcr.io/pyatkinmv/dictara/gateway:latest` |
| `tg-bot/**` | `ghcr.io/pyatkinmv/dictara/tg-bot:latest` |
| `app/**` | `ghcr.io/pyatkinmv/dictara/app-material:latest` |

**Deploy step (SSH to VM):**
```bash
git pull
docker compose pull <service>
docker compose up -d --remove-orphans --force-recreate <service>
```

The registry is public; no credentials needed to pull from the VM.

### transcriber (Cloud Run)

When `transcriber/**` changes, GitHub Actions automatically:

1. Authenticates to GCP using the `github-ci` service account (key stored as `GCP_SA_KEY` secret)
2. Builds and pushes the Docker image to Artifact Registry — tagged both `:latest` and `:<git-sha>`
3. Runs `gcloud run deploy transcriber --image ... --region europe-west4` — updates only the image; all other Cloud Run settings (GPU, memory, env vars) are preserved

**Service account**: `github-ci@gen-lang-client-0721625814.iam.gserviceaccount.com`
**Roles**: `artifactregistry.writer`, `run.developer`, `iam.serviceAccountUser` (on the Compute Engine default SA)

To verify a deploy succeeded:
```bash
gcloud run revisions list --service transcriber --region europe-west4 --limit 5
```

---

## Running transcriber locally

Use the `local` Docker Compose profile to start the transcriber container alongside the other services. The gateway's default `TRANSCRIBER_URL=http://transcriber:8000` picks it up automatically.

```bash
# Start everything including transcriber
docker compose --profile local up -d

# Or start only the transcriber alongside an already-running stack
docker compose --profile local up -d transcriber
```

Note: first start downloads models to the `model-cache` volume (~4–5 GB, ~15 min).

---

## CloudFlare CDN

`dictary.app` is behind CloudFlare. Static Flutter assets (`main.dart.js`, etc.) are cached with `Cache-Control: max-age=14400` (4 hours).

After each `app-material` deploy, CI automatically purges the CloudFlare cache so users always get the latest build. This requires two GitHub Actions secrets:

| Secret | Where to find it |
|--------|-----------------|
| `CF_ZONE_ID` | CloudFlare Dashboard → dictary.app → API section (bottom right) |
| `CF_API_TOKEN` | My Profile → API Tokens → token with Zone / Cache Purge permission |

To purge manually: CloudFlare Dashboard → Caching → Configuration → **Purge Everything**.
