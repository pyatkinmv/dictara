# app — Flutter Web Client

Flutter web client (Material 3) served as a Docker container (nginx) on port 3000. The redesigned interface: transcriptions list and detail page, Tags and Speakers libraries, upload dialog, AI suggestions. Design: `docs/redesign-plan.md` (history), integration with the gateway: [docs/local-backend-integration.md](../docs/local-backend-integration.md).

Login is mandatory: `LoginOverlay` blocks the whole window, blurred, until `AuthController.isLoggedIn`. The sidebar profile panel shows the real signed-in name and the gateway's own plan/usage.

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
      gateway_client.dart                  JSON over HTTP under /api, bearer token, 401 → onUnauthorized, GatewayException
      auth_api.dart                        POST /auth/login-by-username, GET /auth/login-link/{token}
      profile_api.dart                     GET /users/me — plan name and usage, dynamic per plan (no unit is hardcoded)
    models/     Plain data classes (transcription, detail, tags, speakers, suggestions, upload options)
    state/      ChangeNotifier controllers: list (paging, filters, polling), detail, tag and speaker libraries,
                auth_controller.dart (login flow + session), profile_controller.dart (follows AuthController)
    pages/      Screens (transcriptions, detail with its tabs, tags, speakers, upload dialog, login overlay)
    shell/      App frame: sidebar (nav, usage panel), topbar, profile menu (name, plan, "Sign out")
    widgets/    Shared widgets (modal, popover, status badge, transcription row, ErrorReporter...)
    services/   Browser-only pieces behind conditional imports (`*_stub.dart` / `*_web.dart`): file chooser,
                multipart uploader (XHR, upload progress), ".txt" saver, token_store (localStorage), external_link
    theme/      Palette and Material theme
  test/
    support/    Test doubles: fake_transcription_repository.dart, fake_auth_api.dart, fake_profile_api.dart, in_memory_token_store.dart
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
- **Phone layout:** one code base, no separate build. Below `mobileBreakpoint` (700 px, `shell/breakpoints.dart`, `isMobileWidth(context)`) the sidebar is a drawer opened by the topbar's menu button, the transcription header puts its buttons under the title, the Overview's file facts go under the summary, the upload options stack, and the player drops its volume slider (keeps mute). At 700 px and above nothing changes. Check a new screen at 375 px: `test/mobile_layout_test.dart` pumps every page at that width and fails on overflow, and pins the desktop layout.
- **Regenerate summary:** the Overview's summary card has a "Regenerate" button (`pages/detail/overview_view.dart`) that asks first (`regenerate_dialog.dart`), then `POST /transcriptions/{id}/regenerate-summary`. How many times a transcription may be regenerated is the user's plan (the gateway sends `used` and `max`): the dialog says "once" / "N more times" for a limited plan and nothing for a plan with no limit; with no limit the button stays and the card says "Last regenerated 15 Oct" beside it; when the limit is reached it is replaced by "Regenerated 15 Oct"; a plan with none gets no button. When the last attempt failed, a red line under the card's header says why ("Couldn't regenerate the summary. The summary service has run out of credits."), until the next attempt. While it runs the card shows "Regenerating…" and the old summary stays (`TranscriptionDetailController` re-reads the transcription every 3 s until it is done).
- **Playback on the Transcript tab** (`pages/detail/transcript_view.dart`): the phrase being spoken is the last one that has started (`TranscriptionDetail.phraseAt(position)`, so it stays marked through a pause). It gets a bar on the left, a time in the accent color and medium weight; once playback has started, what was said is dark and what is ahead is faded (before that the transcript looks as it always did). A play button (`_PlayFromHere`) shows at the start of a row under the pointer and plays from that phrase (`AudioPlayerController.seekAndPlay`); there is no such column on a phone, which has no pointer. The list is a `ScrollablePositionedList` that follows the phrase being spoken; any movement of the list that is not its own scroll (wheel, drag, scroll bar) stops it following, and playing again or a jump of the audio (a seek) turns it back on. Only the rows react to a change of phrase (a `ValueNotifier` that changes at most a few times a minute), not to every position update (about four a second).
- **Browser APIs** (`package:web`, XHR upload with progress, file chooser, saving a file) live only in `services/*_web.dart`; the stubs let the VM tests run. Verify anything web-only with `flutter build web`, the tests do not compile it.
- **Ids and names:** a transcription is identified by the gateway's `job_id`; tags and speakers are looked up by name and resolved to gateway ids in the repository. A diarizer voice `SPEAKER_00` is "slot 1" on the page.
- **Uploads:** the file is never copied into memory (the browser `Blob` goes into `FormData`); no timeout; cancellable; duplicates are answered by the gateway with the existing transcription.
- **Suggestions** (title, tags of the library, speakers) come from the gateway with the transcription; accepting or rejecting is a POST, then the detail is read again. A suggested speaker exists in the library only after it is accepted.
- **Profile panel:** `ProfileController` follows `AuthController` (loads on login, clears on logout) and is the one optional controller `DictaraApp` builds a harmless default for (`NoopProfileApi`, never resolves) when a test does not pass one — unlike `authController`, which is required, because a missing profile is only a blank panel, never a security gap. `unit` in `GET /users/me`'s usage is never assumed to be "transcriptions" in the UI; the server names it, so a future plan limited by something else needs no Flutter change.

## Authentication

Telegram only, mandatory: `@username` → the gateway asks the bot to confirm → `AuthController` polls `GET /auth/login-link/{token}` every 3 s → JWT + display name, kept in `localStorage` (`token_store.dart`). `LoginOverlay` blocks the window (blurred, no dismiss — nothing works without a session) until `AuthController.isLoggedIn`; its card has three stages (`LoginStage`): entering a username, "check your Telegram", and "we couldn't find you" (still polling — the same attempt resolves if the person starts the bot afterwards). A 401 on any request calls `GatewayClient.onUnauthorized`, wired to `AuthController.logout`. `main_material.dart` owns the one `GatewayClient` and `AuthController` for the app; widget tests use `test/support/fake_auth_api.dart` and either a fresh (logged-out) or seeded (`test_utils.dart`'s `signedInAuthController()`) session. Old client code for the same flow (`lib/shared/auth_service.dart`, `api_client.dart`) was deleted with the old UI; it is in git history (`e226aa9 feat(auth): Telegram-based web login with JWT`) and was the reference for the confirm/poll shape.

## Versioning

The sidebar shows `v<appVersion>` (`lib/app_version.dart`, currently `0.14.0-beta`), equal to `version:` in `pubspec.yaml`; `test/app_version_test.dart` fails if they differ. Semantic versioning: **patch** (0.9.0 to 0.9.1) for a fix that changes nothing new, **minor** (0.9 to 0.10) for a new feature or visible change, **major** only for a deliberate break (`1.0.0` is the public launch; the `-beta` label goes then). Any change that alters what a user sees or can do bumps it in the same pull request, and the PR description says which version and why. Changes that are invisible to users (tests, refactors, docs) do not.
