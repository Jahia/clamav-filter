# clamav-filter

Jahia OSGi module that intercepts file uploads via a servlet filter and scans them with a ClamAV daemon. Admin UI at `/jahia/administration/clamavFilter`.

## Key Facts

- **artifactId**: `clamav-filter` | **version**: `1.0.1-SNAPSHOT`
- **Java package**: `org.jahia.community.clamav`
- **jahia-depends**: `default,graphql-dxm-provider`
- **No Blueprint/Spring** — pure OSGi DS; config via `ConfigurationAdmin` (PID `org.jahia.community.clamav`)

## Architecture

| Class | Role |
|-------|------|
| `ClamavFilter` | `javax.servlet.Filter`; wraps request in `MultiReadHttpServletRequest`, streams body to ClamAV, rejects uploads if scan fails |
| `MultiReadHttpServletRequest` | `HttpServletRequestWrapper` allowing multiple reads of the input stream |
| `ClamavService` | OSGi service interface: `ping()` and `scan(InputStream)` |
| `ClamavServiceImpl` | Opens socket to ClamAV daemon, implements INSTREAM protocol |
| `ClamavConfig` | `@Designate` OSGi component; exposes host/port/timeouts |
| `ClamavConstants` | Defaults: host `localhost`, port `3310`, conn timeout `2000` ms, read timeout `20000` ms |
| `Result` / `Status` | Scan result value objects; `Status`: `PASSED`, `FAILED`, `ERROR` |
| `ClamavQueryExtension` | GraphQL queries |
| `ClamavMutationExtension` | GraphQL mutations |

## GraphQL API

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `clamavSettings` → `{host, port, connectionTimeout, readTimeout}` | Returns config or defaults if service absent |
| Query | `clamavPing` → Boolean | Tests socket connection to daemon |
| Query | `clamavScanTest(content: String!)` → `{status, signature}` | `content` is base64-encoded; `status` values: PASSED/FAILED/ERROR/CONNECTION_FAILED |
| Mutation | `clamavSaveSettings(host, port, connectionTimeout, readTimeout)` → Boolean | Writes via `ConfigurationAdmin`; all params optional (null → keep current) |

All operations require `admin` permission.

## OSGi Configuration

File: `org.jahia.community.clamav.cfg`

| Property | Type | Default |
|---|---|---|
| `host` | String | `localhost` |
| `port` | int | `3310` |
| `connection_timeout` | int | `2000` ms |
| `read_timeout` | int | `20000` ms |

Saved via `ConfigurationAdmin`, applied immediately (no bundle restart needed).

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

- The filter only fires on multipart requests containing file uploads — plain JSON/GraphQL calls are not scanned
- `clamavScanTest` accepts **base64** content (not raw bytes) — Cypress fixtures encode test files with `btoa()`
- If `ClamavService` is null (e.g. ClamAV unreachable on activation), `ping()` returns `false` and `clamavScanTest` returns `CONNECTION_FAILED`
- CSS Modules in Cypress: match with `[class*="cf_..."]`
