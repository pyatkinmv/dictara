# Dictara — Product & Design Reference

Dictara transcribes audio and video files using Whisper with optional speaker diarization, then enriches results with AI-generated summaries and suggestions. Users interact via a Flutter web app; this document describes the UI screens, domain entities, and user flows to guide backend implementation.

Source of truth for UI behavior: `designs/1.9/cards-teal.html`.

---

## Domain Model

### Job (Transcription)

The central entity. Created when a user uploads a file; transitions through `pending → processing → done`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | int | |
| `title` | string | User-editable display name. AI may suggest a rename via Suggestions. |
| `original_filename` | string | Raw filename of the uploaded file (e.g. `product_sync_2025_06_23.mp4`). Stored separately from `title` — user can rename without losing the original. |
| `status` | enum | `pending` / `processing` / `done` |
| `processing_pct` | int? | Present only when `status = processing`. |
| `duration_sec` | int | |
| `file_size_bytes` | long | |
| `language` | string | Detected or user-specified (e.g. `EN`). |
| `word_count` | int | Approximate. |
| `uploaded_at` | datetime | |
| `speaker_count` | int | Number of distinct speaker slots detected by the transcriber. |
| `tags` | Tag[] | Many-to-many with Tag library. |
| `transcript` | Segment[] | Ordered list of transcript segments. |
| `summary` | Summary? | Nullable; populated after transcription completes. |
| `suggestions` | Suggestions? | Nullable; AI hints pending user review. Removed once all items are resolved. |

---

### Segment

One row of the transcript.

| Field | Type | Notes |
|-------|------|-------|
| `index` | int | 0-based position in the transcript. |
| `timestamp` | string | Display string, e.g. `"0:34"`. |
| `speaker_slot` | int | 1-based slot number assigned by the diarizer. |
| `text` | string | Transcribed speech. |

---

### Speaker

Global library of named speakers. Independent of any specific job — one speaker can appear across many transcriptions.

| Field | Type | Notes |
|-------|------|-------|
| `id` | int | |
| `name` | string | Full name, e.g. `"Alexei Sorokin"`. |
| `color` | string | Hex color assigned at creation. Used for visual identification across the UI. |
| `description` | string | Role and topics. Used by AI for automatic speaker identification in future jobs. |
| `transcription_count` | int | |
| `total_minutes` | int | Cumulative speaking time across all jobs. |

---

### Tag

Global library of tags. Independent of any specific job.

| Field | Type | Notes |
|-------|------|-------|
| `id` | int | |
| `label` | string | Lowercase slug, e.g. `"meeting"`. |
| `color` | string | Hex color assigned at creation. |
| `description` | string | What this tag represents. Used by AI for automatic tagging of future jobs. |
| `transcription_count` | int | |

**Color palette** — new tags and speakers cycle through these 10 colors in order (index = `library.size % 10` at creation time):
`#2563EB` `#059669` `#7C3AED` `#D97706` `#0891B2` `#6366F1` `#EF4444` `#EC4899` `#0D9488` `#F59E0B`

---

### Summary

AI-generated after transcription completes. Nullable on Job.

| Field | Type |
|-------|------|
| `text` | string — paragraph summary |
| `key_points` | string[] — bullet list |
| `action_items` | string[] — bullet list |

---

### Suggestions

AI-generated hints attached to a Job after it completes. Nullable. The three sub-fields are independently resolvable — a user can accept the title but reject all tags, for example. The record is removed (or marked fully resolved) once every item has been accepted or rejected.

| Field | Type | Notes |
|-------|------|-------|
| `title` | string? | Suggested new title for the job. |
| `tag_candidates` | `{label, color}[]` | Tags to suggest adding. |
| `speaker_mappings` | `{slot, name, color}[]` | Maps diarizer slot numbers to named speakers. |

**Generation:** The gateway calls Gemini after transcription, using an extended prompt that returns a structured JSON response containing `transcriptionName`, `tagsCandidates`, `speakersMappings`, `summary`, `keyPoints`, and `actionItems`. Results are parsed and stored (likely in a dedicated `suggestions` table). Exact schema TBD.

---

## Screens

### Transcriptions List

The home screen. Shows all of the user's jobs.

**Displayed:**
- Search bar — live filters by title as the user types (no submit button)
- **Filter** dropdown — multi-select by Status (Done / Processing / Pending) and by Tag; active filter count shown as a badge on the button
- **Sort** dropdown — Newest first (default) / Oldest first / Longest / Name A→Z; button highlights when non-default sort is active
- Row list, applying both filters and sort

**Per row:**
- Status icon in a colored square (✓ done, spinner processing, clock pending)
- Title (truncated if long)
- Duration and date
- Tag pills (colored) and speaker count badge
- Status badge (Done / Processing / Pending)
- For `processing`: animated progress bar + `N% — transcribing…`
- For `done`: download icon button (visible on row hover)

**Actions:**
- Click a `done` row → open Transcription Detail (non-done rows are not clickable)
- Click **Upload** in topbar → open Upload modal
- Filter / sort changes apply immediately — no separate Apply step

---

### Upload Modal

Accessible from the topbar Upload button on every page.

**Fields:**
- File drop zone — accepted formats: MP3, MP4, M4A, OGG, WAV, FLAC; max 400 MB
- Language: Auto-detect / English / Russian
- Summary: Auto / Short / Full / None
- Speakers: Auto-detect / 1 / 2 / 3 / 4+

**On submit:** Creates a job in `pending` state; file is uploaded to GCS; transcriber is triggered with the `gs://` reference.

---

### Transcription Detail

Opens when a `done` job is clicked. Composed of a sticky header and two tabs.

**Header (always visible):**
- Breadcrumb: Transcriptions › [title] — clicking "Transcriptions" returns to list
- **Title** — inline-editable: pencil icon appears on hover; clicking switches to an input field with Save (✓) and Cancel (✗) buttons; Enter saves, Escape cancels; saving updates the title everywhere (breadcrumb, topbar, list)
- **AI Suggestions banner** — shown if `suggestions` is non-null and has at least one unresolved item (see below)
- Meta row: duration, upload date, Done badge
- **Tags row** — confirmed tag pills + pending suggestion pills + Add tag button
- **Speakers row** — confirmed speaker chips + pending suggestion chips
- Action buttons: Download .txt, Copy

**Tabs:** Transcript (default), Overview

---

#### AI Suggestions Banner

Shown as a single compact row at the top of the detail header:

```
✨  Rename to: Product Sync — Q3 Roadmap  ·  + 3 tags  ·  3 speakers   Accept all   ✗
```

Each element is shown only while its suggestion is still pending:
- `Rename to: [title]` — hidden once the title suggestion is accepted or rejected
- `+ N tags` — count decreases as individual tags are resolved; hidden when zero
- `N speakers` — same
- The `·` separator between title and tags/speakers — hidden if only one side is pending

**Banner actions:**
- **Accept all** — resolves all remaining suggestions at once
- **✗** — dismisses (rejects) all remaining suggestions

Both actions show a brief loading spinner while the backend processes the request.

**Per-item actions** (inline, without using the banner buttons):
- Each pending tag renders as a ghost dashed pill with ✓ and ✗ icons
- Each pending speaker renders as a ghost chip: `✨ Speaker N → ● [Suggested Name] ✓ ✗`

**What accepting does:**
- Tag: adds the tag to this job; creates a new Tag in the library if the label doesn't exist yet
- Speaker: maps the slot number to the named speaker in this job's transcript; creates a new Speaker in the library if they don't exist yet

**What rejecting does:** removes the suggestion from pending — no data written to the job or library.

The banner auto-hides once all suggestions have been resolved.

---

#### Tags Row

Shows confirmed tags as colored pills followed by any pending AI suggestion pills.

- Clicking ✗ on a confirmed tag pill removes it from this job (does not delete it from the library)
- **+ Add tag** button opens a dropdown:
  - Search input filters the tag library
  - Clicking an existing tag adds it to the job
  - If the typed query doesn't match any existing tag, a **Create "[query]"** option appears — creates the tag in the library and adds it to the job
  - Enter key: adds exact match if found, otherwise creates; Escape closes

---

#### Speakers Row

Shows one chip per speaker slot (slot count = number of speakers detected by the diarizer).

| State | Renders as |
|-------|------------|
| AI suggestion pending for slot | `✨ Speaker N → ● Name ✓ ✗` (ghost dashed chip) |
| Suggestion accepted (slot confirmed) | `● Name` (plain chip) |

Speaker resolution order when rendering the transcript:
1. Manual segment override set by the user via the Speaker Picker
2. Confirmed mapping for that slot (accepted suggestion or previous assignment)
3. Pending AI suggestion — slot is colored with the suggestion color; name stays `"Speaker N"`
4. Fallback — grey, `"Speaker N"`

---

#### Transcript Tab

The transcript is rendered as **speaker runs**: consecutive segments by the same effective speaker are grouped under one block with a colored left border. When the speaker changes, a new block starts.

**Run block:**
- Header: colored dot + speaker name (uppercase, bold) — clicking opens the **Speaker Picker**
- Body: one row per segment — `[timestamp]  [text]`

**Speaker Picker** (floating panel, positioned near the clicked header):
- Search input filters the global Speaker library
- Click a speaker to assign them
- Type a name not in the library → **Create "[name]"** option appears
- **Scope radio** before confirming:
  - *This segment only* — overrides just the clicked segment
  - *All segments by [current name]* — overrides every segment currently attributed to the same speaker in this transcript
- Creating a new speaker adds them to the library with a color from the cycling palette

---

#### Overview Tab

Two-column layout.

**Left — Summary card:**
- Summary paragraph
- Key Points (bullet list)
- Action Items (bullet list)

**Right — Details panel (sticky):**

| Chip | Value |
|------|-------|
| Original file | Raw filename, truncated to fit; hovering shows a floating tooltip with the full name — the tooltip is text-selectable so the user can copy it |
| Duration | e.g. `14:22` |
| Speakers | Count of speaker slots |
| Language | e.g. `EN` |
| Words | Approximate word count |
| File size | e.g. `22.4 MB` |
| Uploaded | Date + time, e.g. `22 Jun · 14:41` |

---

### Tags Page

A card grid of all tags in the library.

**Per card:**
- Color accent bar across the top
- Tag label + edit/delete icon buttons (visible on card hover)
- Description text
- "N files" stat
- Up to 2 linked transcription names; "+N more" if there are additional

**Actions:**
- **Add tag** button (header) or the dashed add-card tile at grid end → opens a modal (name + description)
- **Edit** → same modal pre-filled
- **Delete** → confirmation dialog: *"This tag will be removed from N transcription(s). This cannot be undone."*

**Hint banner:** *"Tag descriptions help Dictara automatically suggest and apply tags to new transcriptions."*

---

### Speakers Page

Same card-grid pattern as Tags.

**Per card:**
- Avatar circle with 2-letter initials (colored background)
- Speaker name + edit/delete buttons (on hover)
- Transcription count + total minutes
- Description text
- Up to 2 linked transcription names

**Hint banner:** *"Named speakers help identify voices in future transcriptions automatically."*

---

## Key User Flows

### Flow A — Upload to Done

1. User clicks **Upload** → selects file, sets Language / Summary / Speakers → submits
2. Job created in `pending` state; appears at top of the list
3. Job transitions to `processing` — list row shows animated progress bar and percentage
4. Transcription completes: status → `done`, summary and suggestions populated
5. User clicks the row → Transcription Detail opens with banner visible

---

### Flow B — Reviewing AI Suggestions

1. User opens a `done` job that has suggestions
2. Banner shows: `✨ Rename to: X · + 3 tags · 3 speakers  Accept all  ✗`
3. User options:
   - **Accept all** → title renamed, tags added, speakers mapped; banner disappears
   - **Dismiss all** → nothing applied; banner disappears
   - **Per-tag** → accept or reject each ghost tag pill individually
   - **Per-speaker** → accept or reject each ghost speaker chip individually
4. Every action triggers a spinner while the backend processes
5. Banner auto-hides once the last suggestion is resolved

---

### Flow C — Manual Speaker Assignment

1. User reads the transcript and spots a misidentified speaker
2. Clicks the speaker's run header → Speaker Picker opens near the header
3. Types a name or selects from the list
4. Chooses scope: this segment only, or all segments by that speaker
5. Picker closes → transcript re-renders with the new name and color

---

### Flow D — Tag Management

**Adding:** Click **+ Add tag** → search library → click to add, or type new name → Create → tag added.

**Removing:** Click **✗** on any tag pill → tag removed from this job (library entry preserved).

---

## Sidebar & Global UI

| Element | Description |
|---------|-------------|
| **Nav** | Transcriptions, Threads, Tags, Speakers, Settings |
| **Usage bar** | Minutes used / plan limit; Upgrade link |
| **Profile dropdown** | Opens upward from sidebar bottom: name + email, Manage plan (with tier badge), Give feedback, Support, Sign out (red) |
| **Topbar** | Shows current page title or open item title; always has the Upload button |

---

## Future / TBD

### Threads

Multi-file AI chat sessions. A Thread links to one or more Jobs and lets the user ask questions across them.

**Entities needed:**
- `Thread` — name, list of linked job IDs, created at
- `Message` — thread ID, role (`user` | `ai`), content (HTML allowed for lists), timestamp

**UI:**
- Thread list: cards with thread name, message count, date, linked file chips, last message preview
- Thread detail: context bar showing linked files + "Add file" button, scrollable chat history, textarea + send button

Not prioritized for the initial backend implementation.

### Settings

Placeholder screen. Content TBD.
