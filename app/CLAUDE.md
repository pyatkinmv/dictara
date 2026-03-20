# app — Flutter Web Client

Flutter web client served as a Docker container on port 3000.

## Structure

```
app/
  lib/
    shared/
      api_client.dart       HTTP client — calls /api/* proxied by nginx to gateway:8080
      auth_service.dart     AuthService (ChangeNotifier) — Telegram login, JWT storage
      history_section.dart  HistorySection widget — accordion list of past transcriptions
      models.dart           Data classes: JobResult, TranscriptSegment, ProgressInfo, HistoryItem
    material/
      main_material.dart    Entry point for Material 3 build
      transcribe_page.dart  Material 3 UI
  Dockerfile                Multi-stage: flutter build web --target=... → nginx:alpine
  nginx.conf                Serves static files; proxies /api/ → gateway:8080
```

The `material/` variant uses Material 3 widgets. The `shared/` directory contains all API calls, data models, auth, and history logic shared across the app.

## Build

```bash
docker compose build app-material
docker compose up -d app-material
```

## Authentication

`AuthService` is a `ChangeNotifier` wired into both pages. On startup it restores a persisted JWT from `localStorage`. Token is passed as `Authorization: Bearer <jwt>` on every API call via `ApiClient.setToken()`.

**Login flow:**
1. User enters Telegram `@username` → `POST /api/auth/login-by-username`
2. Gateway looks up the user in `auth_identities`:
   - Found + `bot_started=true` → creates a login notification → bot sends Telegram confirm message → status `notified`
   - Not found or `bot_started=false` → status `unknown_user` → hint shown to open bot
3. Web polls `GET /api/auth/login-link/{token}` every 3s
4. On confirm: gateway returns `{ confirmed: true, token: <jwt>, display_name: "..." }` → JWT stored, dialog closes

## History section

`HistorySection` loads on login, clears on logout. Metadata list fetched from `GET /api/transcriptions`. Content lazy-loaded via `GET /api/jobs/{jobId}` on expand, cached in memory. In-progress items poll every 3s. Download button for done items (blob URL, `.txt`).

New item prepended automatically when a transcription completes in the current session.

## App flow

```
idle → [pick file] → [Transcribe button] → uploading → processing (poll every 3s) → done
                                                                                   ↘ error → retry → idle
```

Progress phases: `transcribing` (seconds processed / total), `diarizing` (0–100%), `summarizing`.

## Key decisions

- **No CORS** — nginx proxies `/api/` to `gateway:8080`; browser sees same origin
- **nginx resolver** — uses `resolver 127.0.0.11` + `set $upstream` variable to defer DNS at startup
- **No state management lib** — `StatefulWidget` + `setState` is sufficient
- **`dart:html`** — used for the download button (blob URL trick). Deprecated in favour of `package:web` but JS builds work fine. Not Wasm-compatible
- **`AuthService` + `GlobalKey<HistorySectionState>`** — auth state propagated via `ChangeNotifier`; parent calls `addItem()` on history after each completed job
