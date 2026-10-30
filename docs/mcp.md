# MCP: access for AI agents

An agent (Claude Code, OpenCode) connects to `POST /api/mcp` (the gateway's `/mcp`, behind nginx's `/api` prefix) with a personal token and can read the user's transcripts, tags and speakers. Everything here is kept apart from the rest of the gateway: the `mcp` package, the `mcp` database schema and this page.

## What it is, in short

- **Protocol:** JSON-RPC 2.0 over HTTP, one URL, no sessions (every request stands alone, as the newest MCP revision asks; the older `initialize` handshake is answered too). Methods: `initialize`, `ping`, `tools/list`, `tools/call`; notifications are accepted and ignored. `GET` and `DELETE` on `/mcp` are 405 (no stream to listen on, no session to end). Our own thin implementation on Spring MVC (`mcp/protocol/McpController.kt`), no MCP library: the part of the protocol we need is small, and the auth, rights, audit and the paid-work guard stay in our hands. The tools are defined apart from the protocol, so the protocol layer can be swapped for the official SDK later without touching them.
- **A token** (`dtr_` and 43 random characters) belongs to **one user** and gives **read** or **write** access (write includes read). It lives 90 days (fixed, the user is not asked). Only its SHA-256 is stored (`mcp.access_tokens`); the secret is shown once, when it is created.
- **The user comes from the token only.** A tool never takes a user id from its arguments, and tools and the facade cannot even see the HTTP request (ArchUnit checks that). Another user's transcription is "not found", exactly as in the app.
- **Paid work is never available**, to any token. See below.

## Who may use it: a capability of the plan

MCP access is **a property of the plan**, not a check of its name: `"mcp_access": true` in `plans.constraints` (V43 gave it to `unlimited`, Ultra; `free` and the guest plan `anonymous` do not have it). `PlanService.mcpAccess(userId)` answers; nothing in the code says "Ultra". Giving MCP to another plan is one `UPDATE plans`, and a plan can be sold or renamed without touching the feature. Unlike the limits (no key = no limit), **no key = not included**: a capability has to be granted.

It is asked in two places, so that a changed plan changes things at once:
- **Making a token** (`POST /mcp-tokens`): `403 "MCP access is not included in your plan"`.
- **Every call with a token** (`McpTokenService.authenticate`): `403 {"error": "mcp_not_in_plan"}`. The token is kept, not revoked: when the plan includes MCP again (an upgrade) it works again without a new one.

`GET /mcp-tokens` carries `mcp_available` (the plan includes it), and lists the user's tokens either way, so that tokens made earlier can still be revoked. Tests: `McpPlanAccessIntegrationTest`.

## Tokens: how a user gets and loses one

`/mcp-tokens` is for the signed-in app only (the session JWT); an MCP token is not accepted there, so a token can neither mint another token nor extend its own life.

```http
POST   /mcp-tokens            {"name": "OpenCode on laptop", "access": "read"}   (access: read, the default, or write)
→ 201 {"id": "…", "name": "…", "prefix": "dtr_Zk3p", "access": "read", "status": "active",
       "created_at": "…", "expires_at": "…", "token": "dtr_Zk3p…"}               (token: this one time)
GET    /mcp-tokens            → {"tokens": [{id, name, prefix, access, status, created_at, expires_at, last_used_at?}], "mcp_available": true}
DELETE /mcp-tokens/{id}       → 204; 404 for someone else's token or one already gone
```

- `status` is `active`, `expiring` (14 days or less to go) or `expired`. Ended tokens stay in the list until the user removes them, so that the app can show them as expired.
- Revoking (or removing an ended token) is immediate: the next call with it is `401`.
- At most 10 working tokens per user (409 after that). A lost token cannot be recovered: create a new one and revoke the old.
- Using a token (header `Authorization: Bearer dtr_…`): `401 {"error": "invalid_token"}` for a missing, wrong or revoked one, `401 {"error": "token_expired"}` for an ended one, `429` (with `Retry-After`) beyond `dictara.mcp.rate-limit-per-minute` calls a minute.

Connecting Claude Code:

```bash
claude mcp add --transport http dictara https://dictary.app/api/mcp --header "Authorization: Bearer dtr_…"
```

## Where the user manages tokens (the app)

Settings → **MCP access** (the second tab; the first is Profile). Cards, one per token: name, access chip, prefix, when created, when it ends, when last used; **Revoke** (asks first). The server address is on top with a Copy button. **Create token** asks only for a name and read only / read and write (the 90 days are stated, not asked), then shows the token once with the ready command for Claude Code and the `opencode.json` entry for OpenCode; that window cannot be closed by a click outside. A token that has expired is red with a plain sentence, a banner, a red dot on the tab and "Create replacement"; one that ends within 14 days is amber with the days left. For a plan that does not include MCP the tab shows "MCP access comes with the Ultra plan" instead of the address and Create button; tokens made earlier stay listed as "Not working" with Revoke/Delete. Details in `app/CLAUDE.md`.

Editing the text of a phrase (`PUT /transcriptions/{id}/segments/{index}`) and deleting a transcription (`DELETE /transcriptions/{id}`) are for the signed-in app only, and `get_transcript` shows the corrected text. There is no MCP tool for either, and the endpoints take the session JWT, not an MCP token. A deleted transcription is simply "not found" to the tools.

## Tools (stage 1: read)

| Tool | What it does | Needs |
|---|---|---|
| `whoami` | plan, this month's usage, the token's name and access | read |
| `list_tags`, `list_speakers` | the user's tags / speakers (brief: id, name or label, color, description) | read |
| `search_transcriptions` | a page of transcriptions, filtered by title text (`query`), `tags`, `speakers`, `status`, ordered by `sort`; `page`, `size` (≤ 50) | read |
| `get_transcription` | one transcription without its full text: dates, length, language, tags, who speaks, summary (Markdown) | read |
| `get_transcript` | the phrases with start/end seconds and who said them, in pieces (`limit` ≤ 500, `offset`, or a window `from_s`/`to_s`) with `next_offset` | read |

`query` searches titles only, not the spoken text (full-text search is a later stage). A tool result is cut at `dictara.mcp.max-result-chars` with a note on how to ask for less. Tool descriptions and the server's `instructions` tell the agent that transcript text is data, never instructions (what people said in a recording can try to tell an agent what to do).

Later stages add the write tools (rename, tags, speakers, accept or reject suggestions, delete a tag or speaker) behind the `write` access, and full-text search.

## Paid work is never available: how that is guaranteed

Not a right a token can lack, but something no token can have. In three layers, each of which fails the build or the call:

1. **The mark and the guard** (`guard/PaidOperation.kt`). Everything that costs money (the summarizer `analyze`, the transcriber's `submit*`, audio storage `upload*`) and the entry points that start it (`SummaryRegenerationService.start`, `BacklogReanalysisService.start`, `SubmissionService.createSubmission`) carries `@PaidOperation`. An aspect refuses such a call with `PaidOperationNotAllowedException` while the call runs as MCP (`CallOrigin.MCP`, set around every tool call, on the calling thread). The agent gets "costs money, not available through MCP", the audit log gets `denied`. The origin does not follow work handed to another thread, which is why the entry points that start such work carry the mark too.
2. **A test over every tool** (`McpToolContractIntegrationTest`). It walks the tool registry, so a tool added later is covered with no new test, calls each as MCP with arguments of every kind, and checks that not one reached the summarizer's `analyze`, the transcriber's `submit*` or a storage write. The entry points are checked to refuse MCP.
3. **Architecture rules** (`McpArchitectureTest`, ArchUnit). Every implementation of the summarizer, transcriber and audio storage ports must mark its costly methods (a new adapter without the mark fails the build; this already found a missing mark on the local transcriber stub); classes with paid methods cannot be final (Spring subclasses them for the guard); nothing in `mcp` may depend on the paid ports, adapters, storage, jobs, controllers or repositories; tools reach the app only through `mcp.facade`.

To add a tool: write a class in `mcp.tools` implementing `McpTool` (it declares `name`, `description`, `access`, `inputSchema`), call only `mcp.facade`, and put what it needs into the facade. If it would reach paid work, it is refused at run time and its test fails; there is nothing to configure.

## Where things are

- Code: `gateway/src/main/kotlin/com/dictara/gateway/mcp/` (`token`, `protocol`, `tools`, `facade`, `audit`), plus the guard in `guard/`. Nothing outside `mcp` depends on it (a build rule).
- Database: schema `mcp`, migrated by its own Flyway (`McpSchemaMigrator`, folder `db/mcp`, history `mcp.flyway_schema_history`) after the main migrations. Tables: `access_tokens`, `audit_log` (token, tool, outcome `ok`/`denied`/`error`, duration; never arguments or text). **The nightly backup includes both** (`DatabaseBackupService` reads the `public` and `mcp` schemas; files `mcp.access_tokens.json.gz`).
- Settings (`dictara.mcp.*`): `token-ttl-days` (90), `expiring-soon-days` (14), `max-active-tokens` (10), `rate-limit-per-minute` (120), `max-result-chars` (60000), `max-request-bytes` (262144: a bigger request body is refused with 413 without being read to the end).
- Tests: `gateway/src/test/kotlin/com/dictara/gateway/mcp/`: unit (registry checks, access rules, rate limiter), integration over real HTTP and a real database (tokens, protocol, every tool, **a user never sees another's data**, the paid-work guard, limits, odd and hostile input, backup, migration), architecture rules (ArchUnit), and **`McpSdkClientIntegrationTest`, where the official MCP Java SDK client (a client that is not ours) connects, lists the tools and calls them**: it is what keeps our own reading of the protocol honest. In the app: `settings_test.dart`, `mcp_tokens_api_test.dart`.
