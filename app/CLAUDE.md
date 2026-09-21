# app — Flutter Web Client

Flutter web client (Material 3) served as a Docker container (nginx) on port 3000. The redesigned interface: transcriptions list and detail page, Tags and Speakers libraries, upload dialog, AI suggestions. Design: `docs/redesign-plan.md` (history), integration with the gateway: [docs/local-backend-integration.md](../docs/local-backend-integration.md).

**Not done yet (before the first merge to `master`): login.** The client sends no `Authorization` header; locally the gateway's `local` profile puts every request under a seeded dev user. See "Итерация 9" in the integration document.

## Structure

```
app/
  lib/
    material/main_material.dart   Entry point (also the Docker build target): real gateway repository, snack-bar error reporting
    app.dart                      DictaraApp: providers (repository, controllers, ErrorReporter), MaterialApp.router
    router.dart                   go_router routes
    data/
      transcription_repository.dart        The interface every screen talks to
      http_transcription_repository.dart   Implementation over the gateway's HTTP API
      gateway_client.dart                  JSON over HTTP under /api, GatewayException, message extraction
    models/     Plain data classes (transcription, detail, tags, speakers, suggestions, upload options)
    state/      ChangeNotifier controllers: list (paging, filters, polling), detail, tag and speaker libraries
    pages/      Screens (transcriptions, detail with its tabs, tags, speakers, upload dialog)
    shell/      App frame: sidebar, topbar, profile menu
    widgets/    Shared widgets (modal, popover, status badge, transcription row, ErrorReporter...)
    services/   Browser-only pieces behind conditional imports (`*_stub.dart` / `*_web.dart`): file chooser,
                multipart uploader (XHR, upload progress), ".txt" saver
    theme/      Palette and Material theme
  test/
    support/fake_transcription_repository.dart   In-memory double of the repository; only tests use it
  web_dev_config.yaml   Dev server proxy: /api/ → localhost:8080 (the gateway)
  Dockerfile            flutter build web → nginx:alpine
  nginx.conf            Static files; /api/ → gateway:8080 with the /api prefix cut
```

## Run and test

Start the gateway with the `local` profile first (`scripts\dev.ps1`), then:

```bash
flutter run -d web-server --web-port 3001 -t lib/material/main_material.dart
flutter analyze lib test
flutter test          # runs on the Dart VM, no browser needed
```

The app always talks to the gateway; there is no mock mode (it was removed before the first merge). The Docker image is built in CI (`FLUTTER_TARGET=lib/material/main_material.dart`).

## Key decisions

- **State:** `provider` + `ChangeNotifier` controllers; screens know only `TranscriptionRepository`. Widget tests run the real app on `FakeTranscriptionRepository`; `http_repository_test.dart` tests the HTTP repository with `MockClient`.
- **No CORS:** the app calls `/api/...` on its own origin; nginx (production) or the dev server (`web_dev_config.yaml`) forwards it to the gateway and cuts `/api`.
- **Errors:** an action that fails and is not handled by its screen ends in `runZonedGuarded` in `main` and is shown by `ErrorReporter` in a snack bar (the gateway's own message, or a plain sentence for connection/server trouble). In the browser such errors do not reach `PlatformDispatcher.onError`. The Upload dialog shows its own error in red. The list's timed refresh retries silently.
- **Browser APIs** (`package:web`, XHR upload with progress, file chooser, saving a file) live only in `services/*_web.dart`; the stubs let the VM tests run. Verify anything web-only with `flutter build web`, the tests do not compile it.
- **Ids and names:** a transcription is identified by the gateway's `job_id`; tags and speakers are looked up by name and resolved to gateway ids in the repository. A diarizer voice `SPEAKER_00` is "slot 1" on the page.
- **Uploads:** the file is never copied into memory (the browser `Blob` goes into `FormData`); no timeout; cancellable; duplicates are answered by the gateway with the existing transcription.
- **Suggestions** (title, tags of the library, speakers) come from the gateway with the transcription; accepting or rejecting is a POST, then the detail is read again. A suggested speaker exists in the library only after it is accepted.

## Authentication (old client, for reference)

The previous client logged in through Telegram (`@username` → confirmation message from the bot → polling `GET /api/auth/login-link/{token}` → JWT in `localStorage` → `Authorization: Bearer`). Its code (`lib/shared/auth_service.dart`, `api_client.dart`) was deleted with the old UI; it is in git history (`e226aa9 feat(auth): Telegram-based web login with JWT`).
