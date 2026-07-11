import {DocumentNode} from 'graphql';

/**
 * D1 — regression canary for the GraphQL schema shape.
 *
 * README.md/AGENTS.md document a FLAT root-field API (`clamavSettings`, `clamavPing`,
 * `clamavScanTest`, `clamavSaveSettings`), but the actual schema registers a single `clamav`
 * namespace field on Query/Mutation (`ClamavQueryExtension`/`ClamavMutationExtension`), with
 * `settings`/`ping`/`scanTest`/`saveSettings` nested underneath it. `01-clamavFilter.cy.ts` and
 * `02-clamavFilter-Permissions.cy.ts` already exclusively use the correct nested shape, so this is
 * the first test that explicitly asserts the flat shape is REJECTED — the actual regression guard
 * this divergence calls for (see Stage 2 finding D1 / Stage 4 gap #21).
 */
describe('ClamAV Filter — GraphQL schema is namespaced (clamav{...}), not flat (D1 regression canary)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const ping: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/ping.graphql');
    // The documented-but-wrong flat shape from README.md/AGENTS.md — must NOT exist in the schema.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const flatSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/flatSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const flatPing: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/flatPing.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    before(() => {
        cy.login();
    });

    it('the correct nested clamav{ settings } shape succeeds', () => {
        cy.apollo({query: getSettings}).then((result: never) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0);
            const settings = (result as {data: {clamav: {settings: {host: string}}}}).data.clamav.settings;
            expect(settings).to.have.property('host');
        });
    });

    it('the correct nested clamav{ ping } shape succeeds', () => {
        cy.apollo({query: ping}).then((result: never) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0);
            const pingResult = (result as {data: {clamav: {ping: boolean}}}).data.clamav.ping;
            expect(pingResult).to.be.a('boolean');
        });
    });

    it('the documented-but-wrong flat "clamavSettings" field does not exist in the schema', () => {
        cy.apollo({query: flatSettings}).then((result: never) => {
            const errs = errorsOf(result);
            expect(errs, 'schema validation errors for the nonexistent flat field').to.have.length.greaterThan(0);
            expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('clamavSettings');
        });
    });

    it('the documented-but-wrong flat "clamavPing" field does not exist in the schema', () => {
        cy.apollo({query: flatPing}).then((result: never) => {
            const errs = errorsOf(result);
            expect(errs, 'schema validation errors for the nonexistent flat field').to.have.length.greaterThan(0);
            expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('clamavPing');
        });
    });
});
