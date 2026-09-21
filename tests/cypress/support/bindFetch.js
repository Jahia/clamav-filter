/**
 * Binds the spec frame's `fetch` to its global object. MUST stay the first import in `e2e.js`.
 *
 * Why: `@jahia/cypress`'s Apollo transport (`src/support/apollo/links.ts`) fetches through
 * `cross-fetch`, whose browser build ends with
 *
 *     exports = ctx.fetch          // dist/browser-ponyfill.js
 *
 * i.e. it re-exports the NATIVE `fetch` detached from the window. Apollo's `HttpLink` then calls it
 * as a bare function, so `this` is `undefined` and Chrome rejects the call:
 *
 *     Failed to execute 'fetch' on 'Window': Illegal invocation
 *
 * Every `cy.apollo()` in the suite fails that way, which reads as a broken module — it is not. A
 * neutral `{ __typename }` query fails through `cy.apollo()` while the same query over
 * `cy.request` returns 200, and patching that one `cross-fetch` line inside the container makes
 * both pass. The defect is in the harness, not in whatever module is under test.
 *
 * `cross-fetch` captures `ctx.fetch` once, at module-evaluation time. ES imports are evaluated in
 * source order before any other statement in the importing module, so replacing `fetch` with a
 * bound copy here — from a module imported ahead of `@jahia/cypress` — means the value it captures
 * is already bound. Binding is semantically transparent: same function, fixed receiver.
 *
 * Remove this file once the fix lands upstream in Jahia/jahia-cypress (bind or wrap the fetch in
 * `links.ts`), or once `cross-fetch` exports a bound reference.
 */
;[
    typeof globalThis === 'undefined' ? undefined : globalThis,
    typeof self === 'undefined' ? undefined : self,
    typeof window === 'undefined' ? undefined : window,
]
    .filter((scope, index, scopes) => scope && scopes.indexOf(scope) === index)
    .forEach((scope) => {
        if (typeof scope.fetch === 'function') {
            scope.fetch = scope.fetch.bind(scope)
        }
    })
