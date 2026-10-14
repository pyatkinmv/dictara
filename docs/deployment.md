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
- **Path pattern**: `gs://<bucket>/audio/<date>/<uuid>/<filename>`
- **Lifecycle rule**: none. Audio used to be auto-deleted after 90 days; the rule was removed on 2026-10-14 after 819 of 1002 recordings had lost their file (transcripts are in the database and were never affected). Objects are only removed by `cleanup_orphaned_gcs_objects`, and only when no record points at them. GCS soft delete keeps a deleted object for 7 days. Check: `gcloud storage buckets describe gs://<bucket> --format="json(lifecycle_config,soft_delete_policy)"`.

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
| `dedup_storage_uris` | Daily 03:00 | Points `audio_meta` rows with the same `content_hash` at the oldest object that still exists (also heals rows whose object is gone), so duplicate GCS objects become unreferenced and `cleanup_orphaned_gcs_objects` removes them |
| `cleanup_orphaned_gcs_objects` | Weekly Sunday 04:00 | Lists all objects in the bucket, deletes any not referenced by `audio_meta.storage_uri`. Skips objects younger than 1 hour (grace period for in-flight uploads) |

These are no-ops when `GCS_UPLOADS_BUCKET` is not set.

---

## Upload Deduplication

The audio file and the transcription are deduplicated separately (details in `gateway/CLAUDE.md`):

- **The file** is stored once per content: the gateway takes the SHA-256 first (`audio_meta.content_hash`) and, if any record of any user already has an existing object with that content, the new record points at it and nothing is uploaded.
- **A re-send after the stored copy is gone** (old files deleted by the former 90-day rule) brings the audio back for every record with that content and answers with the existing transcription, whatever options the uploader's bot or form had. No new transcription.
- **The transcription** is reused only for the same user, content and options (model, language, diarize, speakers, summary mode); other options are a different result.

The daily `dedup_storage_uris` job consolidates older rows that still point at different objects, to the oldest one that exists.

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
