# clamav-filter

Jahia OSGi module that intercepts file uploads via a servlet filter and scans them with a ClamAV daemon. Admin UI at `/jahia/administration/clamavFilter`.

## Key Facts

- **artifactId**: `clamav-filter` | **version**: `1.0.5-SNAPSHOT` | parent: `jahia-modules` `8.2.3.2`
- **Java**: builds and targets **Java 17**. The `maven.compiler.release` property alone is **not**
  enough under this parent: it sets `<release>11</release>` inside the `default-compile` /
  `default-testCompile` *execution* configs, and execution-level config beats properties. The pom
  therefore overrides both execution ids explicitly — remove that and the build silently drops to
  `-source 11` and fails on the pattern-matching `instanceof` in `ClamavFilter.sendError`.
  Consequence: the bundle is Java 17 bytecode and needs a JDK 17 Jahia; it will not load on JDK 11.
  (The SonarQube scanner needs a Java 17+ *runtime* for Maven — that is unrelated to the bytecode
  target, and does not by itself require `release=17`.)
- **Surefire**: pinned to `3.5.4` in this pom. The parent declares `2.22.2` (2019), which discovers
  **zero** JUnit 5 tests and still reports `BUILD SUCCESS` — a green build that verifies nothing.
  The `surefire.plugin.version` property says `2.22.2` in both parents and is misleading; the
  build-section declaration is what applies (8.2.1.0 declared `3.6.0-M1`). Guard: `mvn test` must
  report **129** tests, never 0. This also caps `junit-jupiter` at the 5.x line — see the pom comment.
- **OSGi import ranges**: bnd derives them from the build classpath, so a parent bump can silently
  narrow them and break resolution on older Jahia instances. The 8.2.3.2 parent brought
  commons-fileupload 1.6 and narrowed that import to `[1.6,2)`; the pom pins it back to `[1.3,2)`
  (only `ServletFileUpload.isMultipartContent()` is used, unchanged across 1.x). Diff the
  `Import-Package` header against the previous release after any parent bump.
- **Java package**: `org.jahia.community.clamav`
- **jahia-depends**: `default,graphql-dxm-provider` (graphql-dxm-provider 3.4.0)
- **No Blueprint/Spring** — pure OSGi DS (`_dsannotations` in maven-bundle-plugin); config via `ConfigurationAdmin` + `ManagedService` (PID `org.jahia.community.clamav`)

## Architecture

| Class | Role |
|-------|------|
| `ClamavFilter` | Extends Jahia `AbstractServletFilter` (order `0.5f`, matchAllUrls); scopes to **all** multipart uploads (Media Manager, Spring Webflow, etc.) plus every raw binary body — any `application/octet-stream` content type (including Forms uploads at `/modules/forms/live/fileupload`) and any `PUT` with a body (WebDAV / JCR-REST binary writes), per `isRawBinaryUpload`. Scanning is deliberately **not** gated on any client-supplied parameter (a `webflowToken`-based skip was removed — it let an uploader disable scanning). Rejects oversize bodies up front via declared `Content-Length`. Wraps the request in `MultiReadHttpServletRequest`, scans every part, **forwards the wrapped request** to the chain (no TOCTOU gap), fail-closes on scanner unavailability. |
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

All operations are nested under a single `clamav` namespace container on `Query` and `Mutation`
(see the "hierarchical namespace" rule below). There are **no** flat `clamavXxx` root fields —
`tests/cypress/e2e/04-clamavFilter-SchemaNamespace.cy.ts` asserts they are absent, using the
`flatPing.graphql` / `flatSettings.graphql` fixtures as negative cases.

| Operation | Path | Notes |
|-----------|------|-------|
| Query | `clamav { settings }` → `{host, port, connectionTimeout, readTimeout}` | Returns config or defaults if service absent |
| Query | `clamav { ping }` → Boolean | Tests socket connection to daemon |
| Query | `clamav { scanTest(content: String!) }` → `{status, signature}` | `content` is base64-encoded; `status` values: PASSED/FAILED/ERROR/CONNECTION_FAILED |
| Mutation | `clamav { saveSettings(host, port, connectionTimeout, readTimeout) }` → Boolean | Writes via `ConfigurationAdmin`; all params optional (null → keep current). Validates inputs and returns `false` on rejection. |

```graphql
query { clamav { settings { host port connectionTimeout readTimeout } ping } }
```

Java-side mapping: `ClamavQueryExtension` / `ClamavMutationExtension` each contribute one
`@GraphQLField` named `clamav` whose **return type** (`ClamavQuery` / `ClamavMutation`) supplies the
namespace; the individual operations are `@GraphQLField` instance methods on those types.

All operations require the module-specific `clamavAdmin` permission (resolved on the JCR root node). The module ships an assignable `clamav-filter-administrator` role (`src/main/import/roles.xml`) granting only `administrationAccess` + `clamavAdmin`, so the settings/test endpoints can be delegated without granting full server `admin`.

### `clamav { saveSettings }` input validation

- `host`: non-empty, length ≤ 253, character whitelist `[A-Za-z0-9.\-:\[\]]` (rejects path separators, whitespace, scheme injection)
- `port`: within `[MIN_PORT, MAX_PORT]` (1–65535)
- `connectionTimeout` / `readTimeout`: `> 0` and `≤ MAX_TIMEOUT_MS` (300 000 ms)
- `clamav { scanTest }` rejects base64 input longer than `MAX_BASE64_INPUT_CHARS` (140 M chars)

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

- The filter scans all multipart uploads, any `application/octet-stream` body (including Forms uploads to `/modules/forms/live/fileupload`), and any `PUT` carrying a body (WebDAV / JCR-REST). Requests with no binary body (JSON, GraphQL, GETs, form-encoded POSTs) pass through untouched. Do **not** reintroduce a skip based on a client-supplied parameter (e.g. `webflowToken`) — it is an attacker-toggleable AV bypass.
- The filter forwards the **wrapped** request to the chain — downstream code consumes the same buffered bytes that were scanned. Removing the wrapper would reopen a TOCTOU gap.
- Request bodies above `DEFAULT_MAX_SCAN_BYTES` (100 MiB) are rejected with `413` before scanning to avoid unauthenticated heap-DoS.
- Scanner unreachable / `Status.ERROR` is **fail-closed** (`503`). Do not change this without a documented threat-model review.
- `clamav { scanTest }` accepts **base64** content (not raw bytes) — Cypress fixtures encode test files with `btoa()`; oversize inputs return `ERROR`.
- If `ClamavService` is null (e.g. ClamAV unreachable on activation), `clamav { ping }` returns `false` and `clamav { scanTest }` returns `CONNECTION_FAILED`.
- CSS Modules in Cypress: match with `[class*="cf_..."]`
