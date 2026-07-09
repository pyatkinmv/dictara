# Рефакторинг: `transcriptions` как центральная сущность

## Проблема

Сейчас центральная таблица — `submissions`. Это «запрос пользователя на транскрибацию»: какой файл, какую модель использовать, с диаризацией или нет. Технический артефакт.

Но именно на `submissions` сейчас вешаются **доменные атрибуты результата**:

- теги — на что? на запрос? нет, на **текст транскрипта**
- спикеры — кто говорил в запросе? нет, кто говорил **в записи**
- title (планируется) — название чего? **транскрипта**, не технического запроса

Это семантически неверно и будет запутывать всех, кто придёт в код.

---

## Как должно быть

Два чётко разделённых понятия:

**`transcriptions`** — то, что видит и трогает пользователь:
- название (`title`)
- теги
- спикеры
- ссылка на исходный файл
- ссылка на submission, который это произвёл

**`submissions`** — внутренний технический job:
- параметры обработки (модель, язык, диаризация)
- статус и прогресс
- попытки (`stage_attempts`)
- сырой результат (`transcripts`, `summaries`)

---

## Пример: как это выглядит сейчас vs как должно быть

### Сейчас

```
submissions (id=abc)
  user_id = user_1
  audio_id = file_xyz
  model = "turbo"
  language = "auto"
  diarize = true
  status = "done"
  ← теги вешаются сюда (submission_tags)
  ← спикеры вешаются сюда (submission_speakers)

transcripts (submission_id=abc)
  segments = [{speaker: "SPEAKER_00", text: "Добрый день..."}]
  formatted_text = "..."
  audio_duration_s = 864.5
```

### Как должно быть

```
transcriptions (id=trn_1)
  user_id = user_1
  audio_id = file_xyz
  title = "Встреча по продукту Q3"     ← название транскрипта
  source = "web"
  created_at = ...
  ← теги вешаются сюда (transcription_tags)
  ← спикеры вешаются сюда (transcription_speakers)

submissions (id=sub_abc)
  transcription_id = trn_1             ← принадлежит транскрипции
  model = "turbo"
  language = "auto"
  diarize = true
  num_speakers = 2
  summary_mode = "short"
  status = "done"

transcripts (submission_id=sub_abc)    ← результат конкретного submission
  segments = [...]
  formatted_text = "..."
  audio_duration_s = 864.5
```

---

## Что нужно сделать

### 1. Новая таблица `transcriptions`

```sql
CREATE TABLE transcriptions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id),
    audio_id    UUID NOT NULL REFERENCES audio_meta(id),
    title       TEXT,
    source      VARCHAR(20) NOT NULL DEFAULT 'web',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 2. Добавить FK в `submissions`

```sql
ALTER TABLE submissions
    ADD COLUMN transcription_id UUID REFERENCES transcriptions(id);
```

Перенести в `transcriptions`: `user_id`, `audio_id`, `source` из `submissions`.  
Из `submissions` эти колонки можно убрать (остаются только технические параметры).

### 3. Перенести теги

```
submission_tags  →  transcription_tags
  submission_id      transcription_id
  tag_id             tag_id
```

### 4. Перенести спикеров

```
submission_speakers  →  transcription_speakers
  submission_id          transcription_id
  speaker_id             speaker_id
```

### 5. Перевести `telegram_deliveries`

```sql
-- было: job_id FK → submissions
-- стало: transcription_id FK → transcriptions
ALTER TABLE telegram_deliveries
    RENAME COLUMN job_id TO transcription_id;
```

Телеграм-бот доставляет результат пользователю — это доменное событие, относится к транскрипции.

### 6. Обновить dedup-логику

Сейчас `findDuplicate()` ищет существующий `submission` с тем же `(user_id, content_hash, model, language, diarize, ...)` и возвращает `submission_id`.

После рефакторинга: при дубликате возвращаем `transcription_id` — пользователь уже получил этот транскрипт, новый submission не нужен.

### 7. Переписать Kotlin-код

- Новый entity `TranscriptionEntity` + `TranscriptionRepository`
- `SubmissionEntity` получает поле `transcriptionId`
- `SubmissionService`, `OrchestratorService`, `SubmissionStateService` — обновить flow создания: сначала INSERT `transcriptions`, потом INSERT `submissions` с `transcription_id`
- Все контроллеры: `/transcriptions` и `/jobs/{id}` отдают `transcription_id` как основной ID
- `submission_tags`/`submission_speakers` → `transcription_tags`/`transcription_speakers`

### 8. Статус транскрипции

Статус для пользователя берётся из `submissions.status` (единственного submission, 1:1 на данный момент).  
В будущем — если появятся ретраи, статус = статус последнего submission.

---

## Что остаётся без изменений

- `transcripts` — сырые данные транскрипта, ссылаются на `submission_id` (это правильно: они результат конкретного job'а)
- `summaries` — аналогично, ссылаются на `submission_id`
- `stage_attempts` — ссылаются на `submission_id` (технические попытки этапов)
- `audio_meta` — без изменений
- `plans`, `users`, `auth_identities` — без изменений

---

## Порядок выполнения

1. Flyway-миграция: создать `transcriptions`, добавить `transcription_id` в `submissions`, перенести `submission_tags` → `transcription_tags`, `submission_speakers` → `transcription_speakers`, обновить `telegram_deliveries`
2. Kotlin: entity + repository для `transcriptions`
3. Kotlin: обновить submission flow (создание пары transcription + submission)
4. Kotlin: обновить сервисы и контроллеры — везде где раньше был `submissionId` как внешний ID, теперь `transcriptionId`
5. Kotlin: обновить dedup-логику
6. Тесты

Миграцию данных делать не нужно — это новое развёртывание, данных в проде ещё нет.
