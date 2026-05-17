# clamav-filter

Jahia OSGi module that intercepts file uploads via a servlet filter and scans them with a ClamAV daemon. Admin UI at `/jahia/administration/clamavFilter`.

## Key Facts

- **artifactId**: `clamav-filter` | **version**: `1.0.1-SNAPSHOT` | parent: `jahia-modules` `8.2.1.0`
- **Java package**: `org.jahia.community.clamav`
- **jahia-depends**: `default,graphql-dxm-provider` (graphql-dxm-provider 3.4.0)
- **No Blueprint/Spring** — pure OSGi DS (`_dsannotations` in maven-bundle-plugin); config via `ConfigurationAdmin` + `ManagedService` (PID `org.jahia.community.clamav`)

## Architecture

| Class | Role |
|-------|------|
| `ClamavFilter` | Extends Jahia `AbstractServletFilter` (order `0.5f`, matchAllUrls); scopes to multipart uploads (excluding Spring Webflow when `webflowToken` is present) and Forms octet-stream uploads at `/modules/forms/live/fileupload`. Wraps the request in `MultiReadHttpServletRequest`, scans every part, **forwards the wrapped request** to the chain (no TOCTOU gap), fail-closes on scanner unavailability. |
| `MultiReadHttpServletRequest` | `HttpServletRequestWrapper` that buffers the body once into `byte[]` for replay. Bounded by `maxBytes` constructor arg; throws `RequestTooLargeException` (extends `IOException`) when exceeded. |
| `ClamavService` | OSGi service interface: `ping()` and `scan(InputStream)` |
| `ClamavServiceImpl` | Opens socket to ClamAV daemon, implements INSTREAM protocol; bounds reply reads, sanitizes log messages (CRLF strip + truncate), explicit US-ASCII/UTF-8 charsets |
| `ClamavConfig` | `ManagedService` + OSGi component; `volatile` fields; `updated()` validates **atomically** — rejects with `ConfigurationException` if port out of range or timeouts ≤0 / > `MAX_TIMEOUT_MS` |
| `ClamavConstants` | Defaults: host `localhost`, port `3310`, conn timeout `2000` ms, read timeout `20000` ms. Bounds: `MIN_PORT=1`, `MAX_PORT=65535`, `MAX_TIMEOUT_MS=300_000`, `DEFAULT_MAX_SCAN_BYTES=100 MiB`, `MAX_BASE64_INPUT_CHARS=140_000_000` |
| `Result` / `Status` | Scan result value objects; `Status`: `PASSED`, `FAILED`, `ERROR` |
| `ClamavGraphQLExtensionsProvider` | Marker `DXGraphQLExtensionsProvider` component that registers the query/mutation type extensions |
| `ClamavQueryExtension` | GraphQL queries |
| `ClamavMutationExtension` | GraphQL mutations |

## Filter response codes

| Outcome | HTTP status |
|---|---|
| Clean | passes downstream (using the wrapped request) |
| Infected (`Status.FAILED`) | `403 Forbidden` |
| Body exceeds `DEFAULT_MAX_SCAN_BYTES` | `413 Payload Too Large` |
| `clamavService == null`, ping fails, or `Status.ERROR` | `503 Service Unavailable` (fail-closed) |
| Unexpected `IOException` / `ServletException` / `MultipartException` | `500 Internal Server Error` |

## GraphQL API

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `clamavSettings` → `{host, port, connectionTimeout, readTimeout}` | Returns config or defaults if service absent |
| Query | `clamavPing` → Boolean | Tests socket connection to daemon |
| Query | `clamavScanTest(content: String!)` → `{status, signature}` | `content` is base64-encoded; `status` values: PASSED/FAILED/ERROR/CONNECTION_FAILED |
| Mutation | `clamavSaveSettings(host, port, connectionTimeout, readTimeout)` → Boolean | Writes via `ConfigurationAdmin`; all params optional (null → keep current). Validates inputs and returns `false` on rejection. |

All operations require `admin` permission.

### `clamavSaveSettings` input validation

- `host`: non-empty, length ≤ 253, character whitelist `[A-Za-z0-9.\-:\[\]]` (rejects path separators, whitespace, scheme injection)
- `port`: within `[MIN_PORT, MAX_PORT]` (1–65535)
- `connectionTimeout` / `readTimeout`: `> 0` and `≤ MAX_TIMEOUT_MS` (300 000 ms)
- `clamavScanTest` rejects base64 input longer than `MAX_BASE64_INPUT_CHARS` (140 M chars)

## OSGi Configuration

File: `org.jahia.community.clamav.cfg`

| Property | Type | Default |
|---|---|---|
| `host` | String | `localhost` |
| `port` | int | `3310` |
| `connection_timeout` | int | `2000` ms |
| `read_timeout` | int | `20000` ms |

Saved via `ConfigurationAdmin`, applied immediately (no bundle restart needed). `ClamavConfig.updated()` validates the entire dictionary before mutating any field — invalid updates are rejected wholesale via `ConfigurationException` and leave the running config untouched.

## Build

```bash
mvn clean install          # Full build
yarn build                 # Frontend only
yarn watch                 # Frontend dev watch
yarn lint                  # ESLint
```

- Frontend entry: `src/javascript/index.js` → component under `src/javascript/ClamavFilter/`
- CSS modules use `cf_` prefix (e.g. `cf_loading`, `cf_alert--success`)
- Admin route target: `administration-server-systemHealth:10`

## Tests (Cypress Docker)

```bash
cd tests
cp .env.example .env          # fill JAHIA_IMAGE, JAHIA_LICENSE
yarn install
./ci.build.sh
./ci.startup.sh               # waits for Jahia + ClamAV, provisions module, runs Cypress
```

- Tests: `tests/cypress/e2e/01-clamavFilter.cy.ts`
- Docker Compose includes a ClamAV container; tests include ping, scan clean/infected, save settings
- `assets/provisioning.yml` installs `graphql-dxm-provider`

## Gotchas

- The filter scans only multipart uploads (excluding `webflowToken`-bearing Spring Webflow posts) and Forms octet-stream uploads to `/modules/forms/live/fileupload`. Other requests (JSON, GraphQL, GETs) pass through untouched.
- The filter forwards the **wrapped** request to the chain — downstream code consumes the same buffered bytes that were scanned. Removing the wrapper would reopen a TOCTOU gap.
- Request bodies above `DEFAULT_MAX_SCAN_BYTES` (100 MiB) are rejected with `413` before scanning to avoid unauthenticated heap-DoS.
- Scanner unreachable / `Status.ERROR` is **fail-closed** (`503`). Do not change this without a documented threat-model review.
- `clamavScanTest` accepts **base64** content (not raw bytes) — Cypress fixtures encode test files with `btoa()`; oversize inputs return `ERROR`.
- If `ClamavService` is null (e.g. ClamAV unreachable on activation), `clamavPing` returns `false` and `clamavScanTest` returns `CONNECTION_FAILED`.
- CSS Modules in Cypress: match with `[class*="cf_..."]`
