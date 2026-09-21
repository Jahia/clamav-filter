/**
 * SEC-418 — the scan gate is deny-by-default, so neither the HTTP verb nor the declared
 * Content-Type can opt an upload out of scanning.
 *
 * The SEC-141 fix (release 1.0.4) generalised scanning from multipart-only to an allow-list of
 * exactly two further shapes: a body whose Content-Type starts with `application/octet-stream`, and
 * a `PUT` carrying a body. Everything else kept the unscanned pass-through, which left three gaps
 * that were reproduced live on 8.2.3.2 and 8.2.4.0-SNAPSHOT:
 *
 *   - method gap — the verb test was `"PUT".equalsIgnoreCase(...)`, so `PATCH` and `DELETE` bodies
 *     were never considered;
 *   - case gap   — the media-type test was a case-SENSITIVE `startsWith`, while RFC 7231 §3.1.1.1
 *     makes media types case-insensitive, so `Application/OCTET-STREAM` slipped past;
 *   - type gap   — only `application/octet-stream` counted as a binary body, so a `POST` declaring
 *     `application/pdf` or `text/plain` was not scanned at all.
 *
 * `ClamavFilter` is registered with `setMatchAllUrls(true)` at order 0.5f, so it runs BEFORE any
 * application handler. That is what makes these assertions clean: a 403 can only have come from the
 * filter, whatever the target URL would otherwise have answered. The fiche's own live run had to
 * read a log-line delta precisely because on 1.0.4 the bypassed arms reached the application and
 * answered 400/404/405 — indistinguishable from a block if you only read status codes. Here the
 * expected outcome IS the filter's 403, so the status is a sound discriminator.
 *
 * The negative cases matter just as much: the structured-API media types stay exempt so that a
 * clamd outage cannot fail-close Jahia's own GraphQL and login traffic.
 */
describe('ClamAV Filter — deny-by-default scan gate (SEC-418)', () => {
    // Any URL works: the filter is global and runs before the application handler.
    const targetUrl = '/cms/render/live/en/sites/systemsite/home.html'
    // Raw EICAR bytes, unescaped — a JSON-encoded form would double-escape the literal backslash
    // and could silently defeat detection.
    const eicar = 'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'

    before(() => {
        cy.login()
    })

    // --- the three gaps the fiche reproduced live ------------------------------------------

    const bypassShapes = [
        { label: 'PATCH with a non-octet-stream type (method gap)', method: 'PATCH', contentType: 'application/pdf' },
        { label: 'DELETE carrying a body (method gap)', method: 'DELETE', contentType: 'application/pdf' },
        {
            label: 'POST with a case-variant octet-stream type (case gap)',
            method: 'POST',
            contentType: 'Application/OCTET-STREAM',
        },
        { label: 'POST declaring application/pdf (type gap)', method: 'POST', contentType: 'application/pdf' },
        { label: 'POST declaring text/plain (type gap)', method: 'POST', contentType: 'text/plain' },
    ]

    bypassShapes.forEach(({ label, method, contentType }) => {
        it(`scans and rejects EICAR sent as ${label}`, () => {
            cy.request({
                method,
                url: targetUrl,
                body: eicar,
                headers: { 'Content-Type': contentType },
                failOnStatusCode: false,
            }).then((response) => {
                expect(
                    response.status,
                    `${method} ${contentType} must be intercepted by ClamavFilter, not passed to the application`,
                ).to.eq(403)
            })
        })
    })

    // --- SEC-141 coverage that must not regress ---------------------------------------------

    it('still scans and rejects EICAR on a PUT with an octet-stream body', () => {
        cy.request({
            method: 'PUT',
            url: targetUrl,
            body: eicar,
            headers: { 'Content-Type': 'application/octet-stream' },
            failOnStatusCode: false,
        }).then((response) => {
            expect(response.status, 'the SEC-141 PUT/octet-stream path must still be scanned').to.eq(403)
        })
    })

    // --- the deliberate exemptions -----------------------------------------------------------

    // text/x-gwt-rpc is the GWT-RPC transport behind jContent / Content Manager / Page Composer.
    // It is on the exemption list because an earlier run of this very suite proved that scanning it
    // fail-closes the whole authoring UI (every POST /gwt/*.gwt answered 503) while the daemon is
    // unreachable.
    const skippedTypes = [
        'application/json',
        'application/x-www-form-urlencoded',
        'application/graphql',
        'text/x-gwt-rpc',
    ]

    skippedTypes.forEach((contentType) => {
        it(`passes a ${contentType} body through without scanning it`, () => {
            // These carry parsed request data, not files, and are exempt on purpose: scanning them
            // would make a clamd outage fail-close Jahia's own API and login traffic. Sending EICAR
            // here asserts the exemption itself, not the scanner — the body must NOT be blocked.
            cy.request({
                method: 'POST',
                url: targetUrl,
                body: eicar,
                headers: { 'Content-Type': contentType },
                failOnStatusCode: false,
            }).then((response) => {
                expect(
                    response.status,
                    `${contentType} is on the scan-exemption list and must reach the application`,
                ).to.not.eq(403)
            })
        })
    })

    it('leaves a clean body untouched whatever the verb', () => {
        cy.request({
            method: 'PATCH',
            url: targetUrl,
            body: 'clean PATCH content, no threats',
            headers: { 'Content-Type': 'application/pdf' },
            failOnStatusCode: false,
        }).then((response) => {
            expect(response.status, 'clean content must never be blocked by ClamavFilter').to.not.eq(403)
        })
    })

    it('leaves a bodyless request untouched', () => {
        cy.request({ method: 'GET', url: targetUrl, failOnStatusCode: false }).then((response) => {
            expect(response.status, 'a GET carries no body and must not be scanned').to.not.eq(403)
        })
    })
})
