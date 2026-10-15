# Production runbook

Prod: one VM `dictara-main` (GCP project `gen-lang-client-0721625814`, zone `europe-west4-a`, `34.6.236.165`, 2 GB RAM, docker compose under `~/dictara`), transcriber on Cloud Run. Deploy = push to `master` (`docs/deployment.md`). Request path: CloudFlare → Caddy on the VM (443, `/etc/caddy/Caddyfile`) → nginx in `app-material` (:3000) → gateway. The origin also answers directly, bypassing CloudFlare.

## Access

Tools: `gcloud` and `gh` (winget: `Google.CloudSDK`, `GitHub.cli`), logged in with `gcloud auth login` / `gh auth login --web`.
SSH key: created by `gcloud compute ssh pyatkinmv@dictara-main --zone europe-west4-a` (it appends the key to the VM metadata, existing keys such as CI's `github-actions` stay). Afterwards plain ssh works:

```bash
ssh -i ~/.ssh/google_compute_engine pyatkinmv@34.6.236.165 'docker ps'
```

On Windows the key must be readable only by you or the PowerShell/Windows `ssh` (used by `scripts\grafana.ps1`) ignores it ("UNPROTECTED PRIVATE KEY FILE", "Permission denied (publickey)"): `icacls $key /inheritance:r; icacls $key /remove "*S-1-3-4"; icacls $key /grant:r "$($env:USERNAME):F"`. Git Bash's ssh does not check this.

The user is in the `docker` group, i.e. root-equivalent: keep the key private. Keys live in Console → Compute Engine → Metadata → SSH Keys (project) and VM → Edit → SSH Keys. Do not edit the `ssh-keys` metadata value by hand (it is overwritten whole, CI's key would go).

## Grafana, metrics, logs

`scripts\grafana.ps1` (tunnel + Grafana + datasources + dashboard), then http://localhost:3001 → Dictara Overview. Metrics and logs are kept 15 s / 14 days on the VM. Without Grafana, the tunnel alone (`ssh -N -L 9091:localhost:9091 -L 3100:localhost:3100 ...`) lets you query `localhost:9091` (Prometheus) and `localhost:3100` (Loki).

Grafana → Explore → Loki:

```
{service="gateway"} |= "ERROR"          # errors (services: gateway, tg-bot, postgres, app-material, loki, prometheus)
{service="gateway"} |= "Submission accepted"
{service="tg-bot"} |= "send failed"
```

Transcriber logs are in Cloud Logging: `gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=transcriber" --limit=50`

## What is deployed

```bash
curl -s localhost:9091/api/v1/query --data-urlencode 'query=dictara_build'   # git_commit + build_time of gateway and tg-bot (through the tunnel)
gcloud run revisions list --service transcriber --region europe-west4 --limit 5
```

## Backlog run: ask Gemini again about old transcriptions

For transcriptions made before the analysis was as good as it is now: the summary is replaced and what the user has not settled (title, tags, speakers) is proposed again (accepted and rejected ones stay); the user's own "Regenerate" button is not touched or counted. `POST /admin/reanalyze` on the gateway, with `ADMIN_KEY`, on the VM only (nginx answers 404 for `/api/admin/`); drive it with `scriptseanalyze.ps1`. **It costs money** (about 1.5-3 cents a recording at `gemini-2.5-flash`, mostly the model's "thinking" tokens), so go in steps and look at each:

1. Pick ids with SQL (the user's finished transcriptions, a varied first ten), write them one per line to a file.
2. `scriptseanalyze.ps1 -Action dryrun -UserId <uuid> -IdsFile ids10.txt`: which ids are eligible, which are left out and why (`not_owner`, `not_found`, `not_ready`, `already_done`, `duplicate`, `invalid`), estimated tokens and cost. Nothing is asked.
3. `-Action run`: one transcription after another (pause 1.5 s), in the background. `-Action status` shows done / failed, tokens, the cost so far and the last errors; `-Action stop` stops after the one it is on. A batch stops by itself after 5 failures in a row (Gemini down, quota), and only one runs at a time.
4. Look at the results in the app (the user accepts the new titles, tags and speakers by hand), then 100, then the rest. The same ids sent again are skipped (`already_done`, marked in `submissions.analysis_backfilled_at`) unless `-Force`.

At most 300 ids a call. Logs: `{service="gateway"} |= "Backlog reanalysis"`; each run is also a row in `job_runs` (`reanalyze_backlog`).

## Health checks (read-only)

```bash
ssh -i ~/.ssh/google_compute_engine pyatkinmv@34.6.236.165 'docker ps; docker stats --no-stream; free -m; df -h /'
curl -s https://dictary.app/api/health                                        # {"status":"ok"}
# DB (schema version, last background jobs):
ssh ... 'docker exec dictara-postgres-1 psql -U dictara -d dictara -c "select max(version::int) from flyway_schema_history where success" -c "select job_name,status,finished_at from job_runs order by started_at desc limit 5"'
```

Normal: schema version = latest `V*.sql`, `daily_db_backup` completed within 24 h, `dictara_jobs{status="pending|processing"}` returns to 0, 5xx rate 0, available RAM above ~0.4 GB (the VM already uses swap).

## Audio storage

The audio bucket has no lifecycle rule (removed 2026-10-14, it deleted files after 90 days). A recording whose file is gone shows "Audio is no longer available"; re-sending the same file (Telegram bot, any options) brings it back without a new transcription. Missing audio per month: compare `audio_meta` rows with `gcloud storage ls -r gs://<bucket>/audio/`.

## Before a release

1. Note what runs now: `dictara_build` and `docker inspect --format '{{index .RepoDigests 0}}' dictara-gateway-1` (images are only tagged `:latest`; the digest is the only way back).
2. Dump the DB: `ssh ... 'docker exec dictara-postgres-1 pg_dump -U dictara dictara | gzip' > dictara-$(date +%F).sql.gz`.
3. After the push: watch `gh run watch`, then `dictara_build`, the health checks above, and Loki `ERROR` for ~30 minutes.

## Rollback

Gateway: on the VM, `docker compose` with the old image pinned by digest (`image: ghcr.io/pyatkinmv/dictara/gateway@sha256:...`), `docker compose up -d gateway`. Migrations only add, the old gateway runs on the new schema. Transcriber: `gcloud run services update-traffic transcriber --to-revisions=<previous>=100 --region europe-west4`.
