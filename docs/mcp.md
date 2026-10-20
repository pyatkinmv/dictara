# MCP: access for AI agents

An agent (Claude Code, OpenCode) connects to `POST /api/mcp` (the gateway's `/mcp`, behind nginx's `/api` prefix) with a personal token and can read the user's transcripts, tags and speakers. Everything here is kept apart from the rest of the gateway: the `mcp` package, the `mcp` database schema and this page.

## What it is, in short

- **Protocol:** JSON-RPC 2.0 over HTTP, one URL, no sessions (every request stands alone, as the newest MCP revision asks; the older `initialize` handshake is answered too). Methods: `initialize`, `ping`, `tools/list`, `tools/call`; notifications are accepted and ignored. `GET` and `DELETE` on `/mcp` are 405 (no stream to listen on, no session to end). Our own thin implementation on Spring MVC (`mcp/protocol/McpController.kt`), no MCP library: the part of the protocol we need is small, and the auth, rights, audit and the paid-work guard stay in our hands. The tools are defined apart from the protocol, so the protocol layer can be swapped for the official SDK later without touching them.
- **A token** (`dtr_` and 43 random characters) belongs to **one user** and gives **read** or **write** access (write includes read). It lives 90 days (fixed, the user is not asked). Only its SHA-256 is stored (`mcp.access_tokens`); the secret is shown once, when it is created.
- **The user comes from the token only.** A tool never takes a user id from its arguments, and tools and the facade cannot even see the HTTP request (ArchUnit checks that). Another user's transcription is "not found", exactly as in the app.
- **Paid work is never available**, to any token. See below.

## Tokens: how a user gets and loses one

`/mcp-tokens` is for the signed-in app only (the session JWT); an MCP token is not accepted there, so a token can neither mint another token nor extend its own life.

```http
POST   /mcp-tokens            {"name": "OpenCode on laptop", "access": "read"}   (access: read, the default, or write)
→ 201 {"id": "…", "name": "…", "prefix": "dtr_Zk3p", "access": "read", "status": "active",
       "created_at": "…", "expires_at": "…", "token": "dtr_Zk3p…"}               (token: this one time)
GET    /mcp-tokens            → {"tokens": [{id, name, prefix, access, status, created_at, expires_at, last_used_at?}]}
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
- Settings (`dictara.mcp.*`): `token-ttl-days` (90), `expiring-soon-days` (14), `max-active-tokens` (10), `rate-limit-per-minute` (120), `max-result-chars` (60000).
- Tests: `gateway/src/test/kotlin/com/dictara/gateway/mcp/`.
