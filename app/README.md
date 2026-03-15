# app — Flutter Web Client

Flutter web client for Dictara, served as two separate Docker containers with different UI styles.

| Service | Port | Style |
|---------|------|-------|
| `app-material` | 3000 | Material 3 (Google) |
| `app-fluent` | 3001 | Fluent UI (Windows) |

## Usage

Open http://localhost:3000 (or `:3001` for Fluent).

1. **Login** — click "Login with Telegram", enter your `@username`
2. **Drop a file** — choose any audio or video file
3. **Configure** — model (fast/accurate), language, diarization, summarization
4. **Transcribe** — live progress shown while processing
5. **History** — all your past transcriptions appear below; click to expand and view transcript + summary; download as `.txt`

## Build

```bash
docker compose build app-material && docker compose build app-fluent
docker compose up -d app-material app-fluent
```

See [CLAUDE.md](CLAUDE.md) for architecture details.
