# Dictara — API Gap Analysis

`docs/design.md` defines the full product UX and domain model. This document compares
that design against the current gateway implementation and lists every schema change,
missing endpoint, and response-shape fix required before a frontend can be built against
the API. It is a checklist, not an implementation spec — implementation details belong in
the individual tickets or PRs.

Source of truth for UI behaviour: `designs/1.9/cards-teal.html`.

---

## Decisions captured here

| Topic | Decision |
|-------|----------|
| Job title field | `submissions.title TEXT NULL`; fallback to `original_name` |
| Tag / Speaker color | Derived at runtime from palette (`index % 10`); NOT stored in DB |
| Speaker slot strings | Keep `SPEAKER_00` / `SPEAKER_01` as-is — no normalization |
| Plan usage display | Keep `max_submissions` count; UI shows "N of M transcriptions" |
| All user actions on detail page | Persist immediately to backend |
| Suggestions source | 1 vs 2 Gemini calls — TBD (needs benchmarking) |
| Speaker Picker persistence | Architecture TBD (needs separate design) |

---

## 1. Schema Gaps

### 1.1 Job title

`submissions` (the job-level table) has no `title` column — only `audio_meta.original_name`.

**Required:** `ALTER TABLE submissions ADD COLUMN title TEXT NULL;`

- Read: if `title IS NULL` return `original_name` as the title in all responses.
- Write: `PATCH /jobs/{id}` updates this field.

> **Future direction** (no immediate action): make `transcriptions` the primary entity exposed
> to clients; `submissions` becomes an internal retry/attempt concept. Nothing in this document
> depends on that restructure.

---

### 1.2 Tag and Speaker colors

`tags` and `speakers` tables have no `color` column and none is needed.

**Color is derived at read/create time** using a fixed 10-color palette, cycling by
`count % 10` (count = number of tags or speakers in the user's library at creation time):

```
#2563EB  #059669  #7C3AED  #D97706  #0891B2
#6366F1  #EF4444  #EC4899  #0D9488  #F59E0B
```

The color must be stored implicitly by recording the creation-order index, or re-derived
consistently. The gateway currently has no color logic — this needs implementing.

All API responses that include a tag or speaker object must include a `color` field.

---

### 1.3 Summaries — structured fields

`summaries.text` stores a plain text paragraph. The design requires:

**Required:** add `key_points TEXT[]` and `action_items TEXT[]` columns.

The existing `text` column is kept as the summary paragraph.

---

### 1.4 Suggestions table (new)

No table or concept exists for AI-generated suggestions. A new table is needed.

**Approximate shape:**

```sql
CREATE TABLE suggestions (
    id              BIGSERIAL PRIMARY KEY,
    submission_id   BIGINT NOT NULL REFERENCES submissions(id),
    title_suggestion TEXT,
    tag_candidates  JSONB,   -- [{label}]
    speaker_mappings JSONB,  -- [{slot: "SPEAKER_00", name}]
    resolved_at     TIMESTAMP
);
```

One row per job. Marked resolved (or deleted) once every item is actioned.

> **TBD:** exact schema depends on whether suggestions are produced by 1 or 2 Gemini calls
> (see §5.2). Do not finalize until that decision is made.

---

### 1.5 Speaker Picker persistence

There is currently no mechanism to persist user-assigned speaker→slot mappings.

Two candidate approaches — needs a separate design session before implementation:

| Approach | Pros | Cons |
|----------|------|------|
| Mapping table `(submission_id, slot VARCHAR, speaker_id)` | Non-destructive; easy to undo | Join on every transcript read |
| Rewrite segment rows with `speaker_id` FK | Simpler reads | Mutates original data |
| Both (slot-level + per-segment override) | Handles both Picker scopes cleanly | More tables |

**This document records the requirement; leaves the architecture open.**

---

### 1.6 Missing fields — verify before adding

The following fields are in the design domain model but may or may not be stored today.
Verify against the transcriber response and current `audio_meta` / `submissions` columns:

| Field | Where to store | Source |
|-------|----------------|--------|
| `word_count` INTEGER | `audio_meta` or `submissions` | Transcriber or post-processing |
| `file_size_bytes` BIGINT | `audio_meta` | GCS object metadata at upload |
| `language` VARCHAR | `audio_meta` | Detected by Whisper or user-specified |
| `duration_sec` INTEGER | `audio_meta` | Audio metadata |
| `processing_pct` INTEGER | in-memory / transient | Transcriber progress callback |

Add any that are missing.

---

### 1.7 Usage display

No change to the plan limit unit. `plans.max_submissions` stays as-is.

`GET /users/me` (new endpoint, §3.6) returns:

```json
{ "used_submissions": 12, "max_submissions": 50, "plan_name": "Pro" }
```

UI renders this as "12 of 50 transcriptions used".

---

## 2. Missing Endpoints

### 2.1 Job — rename title

```
PATCH /jobs/{id}
Body: { "title": "string" }
```

### 2.2 Tag library

```
GET    /tags              → [{id, label, color, description, transcription_count}]
POST   /tags              Body: {label, description?}   → tag (backend assigns color)
PUT    /tags/{id}         Body: {label?, description?}
DELETE /tags/{id}         → {affected_transcriptions: N}
```

Deleting a tag removes it from all jobs (many-to-many rows) but must not delete other
data. Response includes the count of affected jobs so the client can show a confirmation.

### 2.3 Speaker library

```
GET    /speakers          → [{id, name, color, description, transcription_count, total_minutes}]
POST   /speakers          Body: {name, description?}    → speaker (backend assigns color)
PUT    /speakers/{id}     Body: {name?, description?}
DELETE /speakers/{id}     → {affected_transcriptions: N}
```

### 2.4 Job ↔ Tag association

```
POST   /jobs/{id}/tags    Body: {tag_id} OR {label, description?}
                          If tag_id: link existing tag.
                          If label (no tag_id): create tag in library then link.
DELETE /jobs/{id}/tags/{tagId}    removes tag from this job only (library kept)
```

### 2.5 Suggestions

Six granular endpoints plus two bulk endpoints:

```
POST /jobs/{id}/suggestions/accept-all
POST /jobs/{id}/suggestions/dismiss-all

POST /jobs/{id}/suggestions/tags/{label}/accept
POST /jobs/{id}/suggestions/tags/{label}/reject

POST /jobs/{id}/suggestions/speakers/{slot}/accept    (slot = "SPEAKER_00" etc.)
POST /jobs/{id}/suggestions/speakers/{slot}/reject
```

Every endpoint returns the updated `suggestions` sub-object (or `null` when all resolved)
so the client can re-render the banner without re-fetching the full job.

**Accepting a tag suggestion:** adds the tag to this job; creates the Tag in the library
if the label doesn't already exist.

**Accepting a speaker suggestion:** assigns the named speaker to that slot in this job;
creates the Speaker in the library if they don't already exist.

**Rejecting:** removes the suggestion item — no data written to job or library.

### 2.6 Speaker assignment (architecture TBD)

Placeholder shape; finalise after §1.5 design decision:

```
PUT /jobs/{id}/speakers/{slot}
Body: { "speaker_id": 42 }          ← existing speaker
   OR { "name": "Alice", "scope": "slot"|"segment", "segment_index": 7 }
```

`scope = "slot"` reassigns all segments with that slot; `scope = "segment"` overrides
only the one segment at `segment_index`.

### 2.7 User profile

```
GET /users/me → { id, name, email, plan_name, used_submissions, max_submissions }
```

---

## 3. Changes to Existing Endpoints

### 3.1 GET /transcriptions (list)

Fields missing from each item in the response:

| Field | Notes |
|-------|-------|
| `title` | nullable; fallback to `original_name` |
| `duration_sec` | integer |
| `speaker_count` | count of distinct slots in segments |
| `processing_pct` | integer; present only when `status = "processing"` |
| `tags` | currently plain strings — change to `[{id, label, color}]` |

### 3.2 GET /jobs/{id} (detail)

Fields missing from the response:

| Field | Notes |
|-------|-------|
| `title` | nullable; fallback to `original_name` |
| `original_filename` | stored separately from `title` |
| `duration_sec` | |
| `file_size_bytes` | |
| `language` | e.g. `"EN"` |
| `word_count` | approximate |
| `speaker_count` | |
| `tags` | currently plain strings — change to `[{id, label, color}]` |
| `summary.key_points` | `string[]` |
| `summary.action_items` | `string[]` |
| `suggestions` | `null` or `{title?, tag_candidates[], speaker_mappings[]}` |
| `speaker_assignments` | map of slot → `{speaker_id, name, color}` — lets the client resolve speaker names without touching segments |

Segment rows continue to carry `speaker: "SPEAKER_00"` — no change to the segment shape.

---

## 4. Gemini / AI Integration

### 4.1 New structured response contract

`SummarizerPort` currently returns `String`. It needs to return a structured object:

```
summary            String
keyPoints          List<String>
actionItems        List<String>
transcriptionName  String?          suggested title
tagCandidates      List<{label}>    labels only; gateway assigns colors
speakerMappings    List<{slot, name}>  slot = "SPEAKER_00" etc.
```

### 4.2 1 vs 2 Gemini calls (TBD)

| Option | Description |
|--------|-------------|
| A — single call | One prompt returning all fields above in a single JSON response |
| B — two calls | Existing `summarize()` for summary/points/actions + new `suggestions()` call for title/tags/speakers |

**Decision pending** — test both with real transcripts against Gemini 2.5 Flash before
committing to an approach.

### 4.3 Storage

- Summary fields → `summaries` table (`text`, `key_points[]`, `action_items[]`).
- Suggestions → new `suggestions` table (§1.4).
- `OrchestratorService` calls the enrichment step after transcription completes and
  persists both records.

---

## 5. Open Items (separate design sessions required)

| # | Topic | Blocker |
|---|-------|---------|
| 1 | Speaker Picker persistence architecture | Choose between mapping table / segment rewrite / both |
| 2 | 1 vs 2 Gemini calls for suggestions | Benchmark quality and latency |
| 3 | `transcriptions` as primary entity | Long-term refactor; no immediate dependency |
