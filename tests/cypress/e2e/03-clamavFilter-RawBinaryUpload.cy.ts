/**
 * U1 (Cypress half) + D2 — proves the raw-binary/PUT scanning path (`ClamavFilter.isRawBinaryUpload`,
 * SEC-141) actually intercepts a real, non-multipart request end to end, against a live daemon.
 *
 * README.md/AGENTS.md's documented limitation (F21) claims WebDAV `PUT` / raw-body JCR-REST uploads
 * are NOT intercepted by this filter. Stage 2 (`02-undocumented-features.md`, finding D2) found this
 * is stale: `ClamavFilter.isRawBinaryUpload()` now scans any non-multipart `PUT` carrying a body,
 * regardless of destination or Content-Type — the filter is a global, `matchAllUrls(true)` servlet
 * filter, so this applies to any authenticated PUT reaching the webapp, not just file-upload
 * endpoints specifically. This spec targets the RESTful JCR API's property-write endpoint
 * (`PUT .../nodes/{uuid}/properties/{name}`, see the jahia-developer-apis skill's jcr-api.md) against
 * the always-present `/users/root` node, since it needs no site/content provisioning beyond what
 * `assets/provisioning.yml` already installs.
 *
 * CORRECTION (SUPPORT-646, see pipeline reports 06/07/08): an earlier pipeline stage found this
 * spec's preflight GET returning 404s (even for indisputably-existing paths like `/sites`) and the
 * base `/version` route 500ing, and mistakenly concluded this was a platform-level defect in the
 * `jcrestapi` 3.3.0 bundle. That conclusion was WRONG. The real cause, confirmed live: Jahia's
 * security-filter bundle protects ALL APIs (GraphQL, RESTful JCR, custom REST, views) by default,
 * and `org.jahia.bundles.api.security.cfg`'s `security.profile` setting defaults to `default` ("no
 * API calls from external origins or non-privileged users"), which blocked these JCR-REST calls —
 * the block manifests as confusing 404s/500s deep in the resource layer rather than a clean 403 at
 * the filter, which is what misled the earlier investigation. `tests/assets/provisioning.yml` now
 * sets `security.profile=open` (via a `config:property-set` karafCommand, disposable test
 * containers only — never acceptable for production) before this spec runs, which fixes it: the
 * preflight now returns 200 and both tests below run and pass normally, not skipped.
 *
 * Notes on request/response shape, kept for future readers:
 *  1. The RESTful JCR API is DEPRECATED. It remains reachable here because `security.profile=open`
 *     disables all security-filter API restrictions in this disposable test environment.
 *  2. The exact request/response shape (HAL `_links.self.href` field name, whether the property PUT
 *     endpoint accepts a raw (non-JSON) request body for a String property, JCR name-escaping) was
 *     confirmed live against a running server.
 *  3. The EICAR payload is sent as the RAW PUT body (not JSON-wrapped) specifically so the exact
 *     EICAR byte sequence (which contains a literal backslash) reaches the daemon unescaped — a JSON
 *     string encoding of EICAR would double-escape that backslash and could silently defeat
 *     detection.
 */
describe('ClamAV Filter — raw-binary / PUT upload scanning (SEC-141, U1/D2)', () => {
    const jcrRestBase = '/modules/api/jcr/v1/default/en'
    const targetPath = '/users/root'
    // The "jcr:title" property name becomes "jcr__title" per the REST API's ":" -> "__" encoding rule.
    const propertyName = 'jcr__title'

    let propertySelfHref: string

    before(() => {
        cy.login()

        // Preflight: resolve /users/root's own REST self-link. A non-2xx here signals a
        // security-filter/provisioning gap (see note 1 above), not a clamav-filter behavior —
        // fails loudly and distinctly from the scan-blocking assertions below.
        cy.request({
            method: 'GET',
            url: jcrRestBase + '/paths' + targetPath,
            failOnStatusCode: false,
        }).then((response) => {
            expect(
                response.status,
                'JCR-REST preflight GET on ' +
                    targetPath +
                    ' (403/404 here means a security-filter/provisioning gap, not a clamav-filter block)',
            ).to.eq(200)
            const selfHref =
                response.body && response.body._links && response.body._links.self
                    ? response.body._links.self.href
                    : undefined
            expect(selfHref, 'HAL self link on the JCR-REST node response').to.be.a('string')
            propertySelfHref = selfHref + '/properties/' + propertyName
        })
    })

    it('a clean raw PUT reaches the JCR-REST handler (not blocked by ClamavFilter)', () => {
        cy.request({
            method: 'PUT',
            url: propertySelfHref,
            body: 'clean raw-binary PUT content, no threats',
            headers: { 'Content-Type': 'text/plain' },
            failOnStatusCode: false,
        }).then((response) => {
            // The filter itself never returns 403 for clean content; whatever the JCR-REST handler's
            // own success/validation status is, it must not be the filter's infected-rejection code.
            expect(response.status, 'clean content must not be blocked by ClamavFilter').to.not.eq(403)
        })
    })

    it('a PUT carrying EICAR content is intercepted and rejected with 403 by ClamavFilter', () => {
        // Raw EICAR bytes, unescaped — see note 3 above.
        const eicar = 'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'
        cy.request({
            method: 'PUT',
            url: propertySelfHref,
            body: eicar,
            headers: { 'Content-Type': 'text/plain' },
            failOnStatusCode: false,
        }).then((response) => {
            expect(
                response.status,
                'EICAR content must be intercepted by ClamavFilter before reaching the JCR-REST handler',
            ).to.eq(403)
        })
    })
})
