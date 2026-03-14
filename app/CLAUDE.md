# app — Flutter Web Client

Two UI variants of the same transcription UI, served as separate Docker containers on different ports.

## Structure

```
app/
  lib/
    shared/
      api_client.dart       HTTP client — calls /api/* proxied by nginx
      models.dart           Data classes: JobResult, TranscriptSegment, ProgressInfo
    material/
      main_material.dart    Entry point for Material 3 build
      transcribe_page.dart  Material 3 UI
    fluent/
      main_fluent.dart      Entry point for Fluent UI build
      transcribe_page_fluent.dart  Fluent UI (Windows-style)
  Dockerfile                Multi-stage: flutter build web --target=... → nginx:alpine
  nginx.conf                Serves static files; proxies /api/ → transcriber:8000
```

## UI variants

| Service | Port | Style |
|---------|------|-------|
| `app-material` | 3000 | Material 3 (Google) |
| `app-fluent` | 3001 | Fluent UI (Windows) |

Both variants share `shared/` — identical API calls and data models. Only `build()` and widget helpers differ.

## Build

```bash
# Build one variant
docker compose build app-material
docker compose build app-fluent

# Build both (sequential recommended — parallel Flutter builds are memory-heavy)
docker compose build app-material && docker compose build app-fluent

docker compose up -d app-material app-fluent
```

## Key decisions

- **No CORS** — nginx proxies `/api/` to `transcriber:8000`; browser sees same origin
- **nginx resolver** — uses `resolver 127.0.0.11` + `set $upstream` variable to defer DNS at startup (nginx fails to start if `transcriber` hostname can't be resolved at boot time with a static `proxy_pass`)
- **No state management lib** — `StatefulWidget` + `setState` is sufficient for a single linear state machine (idle → uploading → processing → done/error)
- **`dart:html`** — used for the download button (blob URL trick). Deprecated in favour of `package:web` but JS builds work fine. Not Wasm-compatible

## App flow

```
idle → [pick file] → [Transcribe button] → uploading → processing (poll every 3s) → done
                                                                                   ↘ error → retry → idle
```

Progress phases from the transcriber: `transcribing` (shows seconds processed / total) and `diarizing` (shows 0–100%).
