# Редизайн Flutter-клиента — план и контекст

Рабочий документ ветки `redesign`. Цель — не потерять контекст между сессиями: что решили, что делаем, в каком порядке и что уже известно про окружение.

## Цель

Привести Flutter-клиент (`app/`) в соответствие с HTML-дизайном `designs/1.9/cards-teal.html` — итеративно, по одному экрану за раз.

- Сначала **полный кликабельный макет на Flutter** с заглушками вместо данных (как моки в HTML). К gateway и серверу пока не ходим.
- Всё делаем **локально, в браузере**, прод не трогаем.
- Только Web, только широкий экран (макет рассчитан на сайдбар 220 px). Мобильную вёрстку и отдельное десктоп-приложение не делаем.
- Совпадение с дизайном — по вёрстке, цветам, отступам, состояниям и поведению, **не пиксель в пиксель**.
- Потом по экранам подключаем реальный API. Места, где нужны изменения на сервере, фиксируем по ходу (раздел «Изменения сервера» ниже и `docs/api-gaps.md`).

## Источники

| Что | Где |
|-----|-----|
| Дизайн (основной — **teal**) | `designs/1.9/cards-teal.html`. `cards-blue.html` и `cards-violet.html` отличаются только цветовыми токенами, разметка и логика идентичны. Старые версии 1.0–1.8 удалены (остались в истории git) |
| Продукт и домен: сущности, экраны, потоки | [docs/design.md](design.md) |
| Расхождения дизайна и gateway: схема, эндпоинты | [docs/api-gaps.md](api-gaps.md) |
| Рефакторинг `transcripts` как центральной сущности (уже реализован миграциями V27–V29) | удалён в коммите `abed267`, читается через `git show abed267^:docs/transcriptions-refactor.md` |

Токены teal (из макета): `--accent #0D9488`, `--al #F0FDF9`, `--ad #0F766E`, `--bg #F2F7F6`, `--sb-bg #E8F4F2`, `--text #0F2922`, `--text2 #4B6E6A`, `--text3 #8FAEAC`, `--border #D1E7E5`, статусы `--ok #16A34A`/`#DCFCE7`, `--warn #D97706`/`#FEF3C7`. Радиус 12, ширина сайдбара 220, шрифт Inter, иконки Material Symbols Rounded.

## Принятые решения

| Тема | Решение |
|------|---------|
| Ветка | `redesign`, от `master`. Вся работа только в ней |
| Защита прода | CI (`.github/workflows/ci.yml`) собирает образы и деплоит только на `push` в `master`; `pull_request` в `master` запускает только тесты. Пока работаем в ветке, прод не затрагивается |
| Где код | Правим тот же Flutter-код в `app/lib/`, отдельных папок под новый UI не заводим |
| Старый код | Новый UI показывается сразу; `TranscribePage`, `HistorySection` удаляем по мере замены. `ApiClient`, `AuthService` и текущие модели пока не трогаем — понадобятся при подключении сервера |
| Состояние | `provider` + `ChangeNotifier` (в проекте уже есть `AuthService extends ChangeNotifier`) |
| Навигация | `go_router`, у экранов свои адреса |
| Данные | Пока заглушки: интерфейс репозитория + `FakeRepository` с данными из HTML. Появится в итерации 2, когда нужны первые данные |
| Тема | teal; тема параметризуется, blue и violet — заменой токенов |
| Threads, Settings | Не в первой очереди, делаем в конце, если решим |
| Гранулярность | Итеративно, один коммит на итерацию, после каждой смотрим результат в браузере рядом с HTML |

## План

- [x] **0. Подготовка**
  - [x] Создать ветку `redesign`
  - [x] Проверить запуск на этой машине: `flutter run -d web-server` работает (`-d chrome` не проверялся, см. «Как запускать приложение»)
  - [x] Добавить зависимости `provider`, `go_router` (заодно `pubspec.lock` очищен от устаревших записей)
  - [x] Положить шрифт Inter в assets: `app/assets/fonts/` (Regular, Medium, SemiBold, Bold, ExtraBold из Inter 4.1, лицензия SIL OFL рядом), подключён в `pubspec.yaml`
- [ ] **1. Каркас:** тема teal, сайдбар (логотип, навигация, usage, профиль-меню), топбар с Upload, go_router с пустыми страницами `/transcriptions`, `/threads`, `/tags`, `/speakers`, `/settings`; `main_material.dart` переключается на каркас; обновить `test/widget_test.dart`
- [ ] **2. Список транскрипций** на моках: поиск, фильтр (статус, теги), сортировка, строки со статусом и прогрессом
- [ ] **3. Деталь:** шапка (хлебные крошки, переименование заголовка, мета) и вкладка Transcript
- [ ] **4. Деталь:** вкладка Overview (summary, key points, action items, панель Details)
- [ ] **5. Деталь:** ряд тегов и дропдаун «Add tag»
- [ ] **6. Деталь:** ряд спикеров и Speaker Picker
- [ ] **7. Деталь:** баннер AI-подсказок (Accept all / Dismiss / по одной)
- [ ] **8. Страница Tags** с модалками создания, правки, удаления
- [ ] **9. Страница Speakers**
- [ ] **10. Модалка Upload**
- [ ] **11. Threads и Settings** (по желанию)

## Изменения сервера, найденные по ходу

Сюда добавляем то, что выясняется при реализации экранов. Полный список известных расхождений — в [api-gaps.md](api-gaps.md).

_Пока пусто._

## Известные проблемы и несоответствия

- **Цвет тегов и спикеров.** `design.md` описывает поле `color`, назначаемое при создании по циклу из 10 цветов. `api-gaps.md` решает иначе: цвет считается на фронтенде по хэшу имени и API его не возвращает. Нужно выбрать одно.
- **Тип `Job.id`.** В `design.md` int, в БД UUID.
- **`app/pubspec.lock` был устаревшим.** В нём были лишние записи (`fluent_ui`, `intl` и др.), которых нет в `pubspec.yaml`. При добавлении `provider` и `go_router` (итерация 0) файл пересобран `flutter pub add`, лишнее ушло.
- **`dart:html`.** `AuthService`, `history_section.dart`, `transcribe_page.dart` используют устаревшую библиотеку. Из-за неё тесты работают только на браузерной платформе. В новом коде её не использовать; для скачивания и буфера обмена брать `package:web`.
- **CI не запускает Flutter-тесты.** В `ci.yml` нет `flutter test`.

## Окружение (Windows, настроено)

| Что | Как |
|-----|-----|
| JDK 21 | Temurin, через winget |
| Gradle 8.7 | `C:\Gradle\gradle-8.7`, в PATH (версия как в CI и Dockerfile) |
| Python 3.12 | через winget; тесты transcriber — в `transcriber/.venv` |
| Flutter 3.47.5 stable | `C:\flutter`, в PATH. Установлен из zip, поэтому `flutter upgrade` не работает |
| Docker | Docker Desktop, WSL2 |
| Chrome | установлен (нужен для `flutter run -d chrome`) |
| `.env` | шаблон в корне (в `.gitignore`), значения `CHANGE_ME` нужно заполнить |

Результаты тестов на момент настройки: `gateway` 75/75, `transcriber` 18/18, `tg-bot` 2/2, `app` 1/1 (в Docker).

**Flutter-тесты на этой машине.** `flutter test --platform chrome` локально зависает: тестовый сервер Flutter 3.47.5 отдаёт 404 на `/canvaskit/chromium/canvaskit.js`, хотя файлы в SDK есть (вероятно, баг на Windows, не проверено). Тесты запускаются в Docker:

```bash
docker run --rm -v "C:\Users\pyatk\IdeaProjects\dictara\app:/src:ro" ghcr.io/cirruslabs/flutter:stable bash -c 'export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && curl -sSfL -o /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb && apt-get install -y -qq /tmp/chrome.deb && cp -r /src /work && cd /work && rm -rf .dart_tool build && flutter pub get && flutter test --platform chrome'
```

Папка монтируется только на чтение, чтобы `pub get` не менял `pubspec.lock` в репозитории.

## Как запускать приложение

Приложение ходит на относительный `/api/...`, который в проде проксирует nginx; на моках это не нужно. Для запуска с реальным gateway понадобятся Postgres + gateway (`docker compose up -d postgres gateway`) и прокси на `/api` (в gateway CORS не настроен). Без Telegram-токена gateway считает пользователя анонимным, так что интерфейс работает и без логина.

### Запуск нового UI на моках (проверено)

Точка входа проекта — `lib/material/main_material.dart` (не `lib/main.dart`), её нужно указывать явно:

```powershell
cd app
flutter run -d chrome -t lib/material/main_material.dart
```

В терминале, где запущен `flutter run`, доступны hot reload (`r`) и hot restart (`R`). Вариант `-d chrome` открывает браузер сам; **он на этой машине пока не проверялся**. Проверен вариант без запуска браузера: `flutter run -d web-server --web-port 3001 -t lib/material/main_material.dart`, затем открыть `http://localhost:3001` вручную (страница поднимается, CanvasKit отдаётся, в консоли ошибок нет; первая сборка ~40 с). Ошибка 404 на CanvasKit касается только тестового сервера `flutter test`, не `flutter run`.

`flutter run` при каждом запуске сам дописывает блок `analyzer.exclude` в `app/analysis_options.yaml`. Это побочный эффект инструмента, в коммиты его не берём (`git checkout -- app/analysis_options.yaml`).
