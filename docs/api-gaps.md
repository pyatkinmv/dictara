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
| Tag / Speaker color | Derived on the frontend from tag name hash — NOT returned by the API |
| Speaker slot strings | Keep `SPEAKER_00` / `SPEAKER_01` as-is — no normalization |
| Plan usage display | Keep `max_submissions` count; UI shows "N of M transcriptions" |
| All user actions on detail page | Persist immediately to backend |
| Suggestions source | 1 vs 2 Gemini calls — TBD (needs benchmarking) |
| Speaker Picker persistence | Architecture TBD (needs separate design) |
| Summary structured fields | Deferred — not critical for initial frontend |

---

## 1. Schema Gaps

### 1.1 Summaries — structured fields *(deferred)*

`summaries.text` stores a plain text string with markdown-style formatting
(`📝 Summary`, `Key points: •`, `✅ Action items:`). The design renders three
distinct UI sections: a summary paragraph, a key-points list, and an action-items list.

**Required when undeferred:** add `key_points TEXT[]` and `action_items TEXT[]` columns;
change Gemini prompt to return JSON; update `SummarizerPort` return type.

---

### 1.2 Suggestions table *(TBD)*

No table or concept exists for AI-generated suggestions. A new table is needed.

**Approximate shape:**

```sql
CREATE TABLE suggestions (
    id              UUID PRIMARY KEY,
    submission_id   UUID NOT NULL REFERENCES submissions(id),
    title_suggestion TEXT,
    tag_candidates  JSONB,    -- [{label}]
    speaker_mappings JSONB,   -- [{slot: "SPEAKER_00", name}]
    resolved_at     TIMESTAMP
);
```

One row per job. Marked resolved (or deleted) once every item is actioned.

> **TBD:** exact schema depends on whether suggestions are produced by 1 or 2 Gemini calls
> (see §4.2). Do not finalize until that decision is made.

---

### 1.3 Speaker Picker persistence *(TBD)*

No mechanism to persist user-assigned speaker→slot mappings.

Two candidate approaches:

| Approach | Pros | Cons |
|----------|------|------|
| Mapping table `(submission_id, slot VARCHAR, speaker_id)` | Non-destructive; easy to undo | Join on every transcript read |
| Rewrite segment rows with `speaker_id` FK | Simpler reads | Mutates original data |

**This document records the requirement; leaves the architecture open.**

---

### 1.4 Missing fields — verify before adding

| Field | Where to store | Status |
|-------|----------------|--------|
| `word_count` INTEGER | `transcripts` | Not in DB — add if needed |
| `file_size_bytes` BIGINT | `audio_meta` | In DB as `size_bytes` — just expose in API |

---

### 1.5 Usage display

No schema change needed. `plans.max_submissions` stays as-is.

`GET /users/me` (§2.6) returns:

```json
{ "used_submissions": 12, "max_submissions": 50, "plan_name": "Pro" }
```

---

## 2. Missing Endpoints

### 2.1 Tag library

```
GET    /tags              → [{id, label, description, transcription_count}]
POST   /tags              Body: {label, description?}   → tag
PUT    /tags/{id}         Body: {label?, description?}
DELETE /tags/{id}         → {affected_transcriptions: N}
```

Deleting a tag removes it from all transcripts but must not delete other data.
Response includes the count of affected transcripts so the client can show a confirmation.

---

### 2.2 Speaker library

```
GET    /speakers          → [{id, name, description, transcription_count, total_minutes}]
POST   /speakers          Body: {name, description?}    → speaker
PUT    /speakers/{id}     Body: {name?, description?}
DELETE /speakers/{id}     → {affected_transcriptions: N}
```

---

### 2.3 Job ↔ Tag association

Current `POST /jobs/{id}/tags` accepts `{tag: "name"}` (a plain string). Needs replacing:

```
POST   /jobs/{id}/tags           Body: {tag_id}  — link existing tag
                              OR Body: {label, description?}  — create tag in library then link
DELETE /jobs/{id}/tags/{tagId}   — removes tag from this job only (library kept); tagId is UUID
```

The delete endpoint currently takes a tag name in the path — change to UUID.

---

### 2.4 Suggestions *(TBD — depends on §1.2)*

```
POST /jobs/{id}/suggestions/accept-all
POST /jobs/{id}/suggestions/dismiss-all

POST /jobs/{id}/suggestions/tags/{label}/accept
POST /jobs/{id}/suggestions/tags/{label}/reject

POST /jobs/{id}/suggestions/speakers/{slot}/accept
POST /jobs/{id}/suggestions/speakers/{slot}/reject
```

Every endpoint returns the updated `suggestions` sub-object (or `null` when all resolved).

---

### 2.5 Speaker assignment *(TBD — depends on §1.3)*

```
PUT /jobs/{id}/speakers/{slot}
Body: { "speaker_id": "uuid" }               ← existing speaker
   OR { "name": "Alice", "scope": "slot"|"segment", "segment_index": 7 }
```

---

### 2.6 User profile

```
GET /users/me → { id, name, plan_name, used_submissions, max_submissions }
```

---

## 3. Changes to Existing Endpoints

### 3.1 GET /transcriptions (list)

Currently returns all transcriptions for a user in one shot — no pagination, no filtering,
no sorting. This won't scale. Needs a full rework:

**Pagination:** cursor-based (by `created_at` + `id`) or offset — TBD. Supports
infinite-scroll on the frontend.

**Query params to add:**

| Param | Values | Notes |
|-------|--------|-------|
| `sort` | `newest` (default) \| `oldest` \| `longest` \| `name` | `longest` sorts by `audio_meta.duration_s` |
| `status` | `done,processing,pending` (comma-separated) | filter by status |
| `tag_ids` | `uuid1,uuid2` (comma-separated) | filter by tags (AND or OR — TBD) |
| `q` | string | see §3.1.1 |
| `limit` | integer | page size |
| `cursor` | opaque string | pagination cursor |

**Fields missing from each item:**

| Field | Notes |
|-------|-------|
| `duration_sec` | stored in `audio_meta.duration_s` — just map it |
| `speaker_count` | count of distinct speaker slots in segments |
| `processing_pct` | integer; present only when `status = "processing"` |
| `tags` | currently `["name"]` — change to `[{id, label}]` |

#### 3.1.1 Search

Search is a large standalone task. Key open questions:

- **Scope:** title only, or also transcript text? Title-only is trivial; full-text over
  transcript content is much harder (segments are stored as JSONB, not a flat text column).
- **Engine:** PostgreSQL `tsvector`/`tsquery` (built-in, no infra) vs external (Elasticsearch,
  Typesense — overkill for now). Start with Postgres FTS on a generated `tsvector` column.
- **Multilingual:** Whisper detects language per transcript — the FTS dictionary must match.
  Postgres supports per-row dictionaries but it requires a custom configuration.

**Recommendation for v1:** search by title only using `ILIKE` or a simple `tsvector` index.
Full transcript search is a separate feature — do not block list-view pagination on it.

---

### 3.2 GET /jobs/{id} (detail)

Fields missing from the response:

| Field | Notes |
|-------|-------|
| `original_filename` | `audio_meta.original_name` — return alongside `title` |
| `file_size_bytes` | `audio_meta.size_bytes` — already in DB |
| `word_count` | not in DB yet (see §1.4) |
| `speaker_count` | count of distinct slots in segments |
| `tags` | currently `["name"]` — change to `[{id, label}]` |
| `summary.key_points` | deferred (§1.1) |
| `summary.action_items` | deferred (§1.1) |
| `suggestions` | TBD (§1.2) |
| `speaker_assignments` | map of slot → `{speaker_id, name}` — resolves speaker names without touching segments |

---

## 4. Gemini / AI Integration

### 4.1 New structured response contract *(deferred with §1.1)*

`SummarizerPort` currently returns `String`. When §1.1 is undeferred, it needs to return:

```
summary            String
keyPoints          List<String>
actionItems        List<String>
transcriptionName  String?
tagCandidates      List<{label}>
speakerMappings    List<{slot, name}>
```

### 4.2 1 vs 2 Gemini calls *(TBD)*

| Option | Description |
|--------|-------------|
| A — single call | One prompt returning all fields above in a single JSON response |
| B — two calls | Existing `summarize()` for summary/points/actions + new `suggestions()` call for title/tags/speakers |

**Decision pending** — test both with real transcripts before committing.

---

## 5. Open Items

| # | Topic | Blocker |
|---|-------|---------|
| 1 | Speaker Picker persistence architecture | Choose between mapping table / segment rewrite / both |
| 2 | 1 vs 2 Gemini calls for suggestions | Benchmark quality and latency |
| 3 | `transcriptions` as primary entity | Long-term refactor; no immediate dependency |
