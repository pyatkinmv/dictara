# Рефакторинг: `transcripts` как центральная доменная сущность

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

**`transcripts`** — то, что видит и трогает пользователь (уже существует):
- текст, сегменты, длительность
- + название (`title`)
- + теги
- + спикеры

**`submissions`** — внутренний технический job:
- параметры обработки (модель, язык, диаризация)
- статус и прогресс
- попытки (`stage_attempts`)

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
  ← теги вешаются сюда (submission_tags)     ← неверно
  ← спикеры вешаются сюда (submission_speakers) ← неверно

transcripts (submission_id=abc)
  segments = [{speaker: "SPEAKER_00", text: "Добрый день..."}]
  formatted_text = "..."
  audio_duration_s = 864.5
```

### Как должно быть

```
submissions (id=abc)
  user_id = user_1
  audio_id = file_xyz
  model = "turbo"
  language = "auto"
  diarize = true
  status = "done"               ← только технические поля

transcripts (submission_id=abc)
  title = "Встреча по продукту Q3"    ← название транскрипта ✓
  segments = [{speaker: "SPEAKER_00", text: "Добрый день..."}]
  formatted_text = "..."
  audio_duration_s = 864.5
  ← теги вешаются сюда (transcript_tags)      ✓
  ← спикеры вешаются сюда (transcript_speakers) ✓
```

---

## Что нужно сделать

### 1. Добавить `title` в `transcripts`

```sql
ALTER TABLE transcripts ADD COLUMN title TEXT;
```

При отдаче API: если `title IS NULL` — фоллбэк на `audio_meta.original_name`.

### 2. Перенести теги

```sql
CREATE TABLE transcript_tags (
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    tag_id        UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (transcript_id, tag_id)
);

-- Перенести данные из submission_tags через join
INSERT INTO transcript_tags (transcript_id, tag_id)
SELECT t.id, st.tag_id
FROM submission_tags st
JOIN transcripts t ON t.submission_id = st.submission_id;

DROP TABLE submission_tags;
```

### 3. Перенести спикеров

```sql
CREATE TABLE transcript_speakers (
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    speaker_id    UUID NOT NULL REFERENCES speakers(id) ON DELETE CASCADE,
    PRIMARY KEY (transcript_id, speaker_id)
);

INSERT INTO transcript_speakers (transcript_id, speaker_id)
SELECT t.id, ss.speaker_id
FROM submission_speakers ss
JOIN transcripts t ON t.submission_id = ss.submission_id;

DROP TABLE submission_speakers;
```

### 4. Обновить Kotlin-код

- `SubmissionTagRepository` / `SubmissionTagEntity` → `TranscriptTagRepository` / `TranscriptTagEntity`
- `SubmissionSpeakerRepository` / `SubmissionSpeakerEntity` → `TranscriptSpeakerRepository` / `TranscriptSpeakerEntity`
- `SubmissionService.addTag` / `removeTag` — джойнить через `transcripts` чтобы получить `transcript_id`
- `TranscriptEntity` — добавить поле `title: String?`
- Список (`GET /transcriptions`): джойн `submissions → transcripts → transcript_tags → tags` вместо `submissions → submission_tags → tags`
- Детали (`GET /jobs/{id}`): аналогично
- `title` в ответах: `transcript?.title ?: audioMeta.originalName`

### 5. Обновить dedup-логику

Без изменений — dedup работает на уровне `submissions` (по `content_hash` + параметрам), это технический уровень и так и должно быть.

---

## Что не меняется

- `submissions` — структура таблицы не трогается
- `transcripts` — все существующие колонки остаются, только добавляем `title`
- `summaries` — ссылается на `submission_id`, остаётся как есть (результат конкретного job'а)
- `stage_attempts` — аналогично
- `telegram_deliveries` — ссылается на `submissions.id`, не трогаем (доставка привязана к конкретному processing job'у)
- `audio_meta`, `plans`, `users`, `auth_identities` — без изменений

---

## Порядок выполнения

1. Flyway-миграция: `ALTER TABLE transcripts ADD COLUMN title TEXT`, создать `transcript_tags` + `transcript_speakers`, перенести данные, удалить `submission_tags` + `submission_speakers`
2. Kotlin: переименовать entity/repository для тегов и спикеров
3. Kotlin: добавить `title` в `TranscriptEntity`
4. Kotlin: обновить `SubmissionService` (tag/speaker методы джойнят через `transcripts`)
5. Kotlin: обновить контроллер и response DTO — добавить `title`, поменять джойны для тегов/спикеров

Миграцию данных делать не нужно — это новое развёртывание, данных в проде ещё нет.
