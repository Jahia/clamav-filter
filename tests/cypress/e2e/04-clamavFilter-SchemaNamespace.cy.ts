import { DocumentNode } from 'graphql'

/**
 * D1 — regression canary for the GraphQL schema shape.
 *
 * The schema registers a single `clamav` namespace field on Query/Mutation
 * (`ClamavQueryExtension`/`ClamavMutationExtension`) with `settings`/`ping`/`scanTest`/
 * `saveSettings` nested underneath, rather than flat `clamavXxx` root fields. Flat root fields
 * collide across modules — two bundles registering the same global field breaks the entire
 * GraphQL schema — so the namespacing must not regress.
 *
 * `01-clamavFilter.cy.ts` and `02-clamavFilter-Permissions.cy.ts` exercise the nested shape;
 * this spec is the one that asserts the flat shape is actively REJECTED, using the
 * `flatSettings`/`flatPing` fixtures as negative cases.
 *
 * (AGENTS.md documented the flat names until the namespace refactor was reflected there; the
 * docs and this canary now agree.)
 */
describe('ClamAV Filter — GraphQL schema is namespaced (clamav{...}), not flat (D1 regression canary)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql')
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const ping: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/ping.graphql')
    // The pre-refactor flat shape — must NOT exist in the schema.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const flatSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/flatSettings.graphql')
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const flatPing: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/flatPing.graphql')

    // `cy.apollo` yields ApolloQueryResult | FetchResult, and GraphQL errors surface as
    // `graphQLErrors` (Apollo client errors) or `errors` (raw GraphQL response) depending on the
    // path. Narrowing from `unknown` keeps both readable without annotating the callbacks below
    // as `never`, which current Cypress/Apollo typings reject outright.
    const errorsOf = (result: unknown): ReadonlyArray<{ message: string }> => {
        const r = result as {
            graphQLErrors?: ReadonlyArray<{ message: string }>
            errors?: ReadonlyArray<{ message: string }>
        }
        return r.graphQLErrors ?? r.errors ?? []
    }

    before(() => {
        cy.login()
    })

    it('the correct nested clamav{ settings } shape succeeds', () => {
        cy.apollo({ query: getSettings }).then((result) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0)
            const settings = (result as unknown as { data: { clamav: { settings: { host: string } } } }).data.clamav
                .settings
            expect(settings).to.have.property('host')
        })
    })

    it('the correct nested clamav{ ping } shape succeeds', () => {
        cy.apollo({ query: ping }).then((result) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0)
            const pingResult = (result as unknown as { data: { clamav: { ping: boolean } } }).data.clamav.ping
            expect(pingResult).to.be.a('boolean')
        })
    })

    it('the documented-but-wrong flat "clamavSettings" field does not exist in the schema', () => {
        cy.apollo({ query: flatSettings }).then((result) => {
            const errs = errorsOf(result)
            expect(errs, 'schema validation errors for the nonexistent flat field').to.have.length.greaterThan(0)
            expect(errs.map((e: { message: string }) => e.message).join(' ')).to.contain('clamavSettings')
        })
    })

    it('the documented-but-wrong flat "clamavPing" field does not exist in the schema', () => {
        cy.apollo({ query: flatPing }).then((result) => {
            const errs = errorsOf(result)
            expect(errs, 'schema validation errors for the nonexistent flat field').to.have.length.greaterThan(0)
            expect(errs.map((e: { message: string }) => e.message).join(' ')).to.contain('clamavPing')
        })
    })
})
