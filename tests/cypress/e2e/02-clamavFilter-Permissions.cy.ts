import { DocumentNode } from 'graphql'
import { createUser, deleteUser, grantRoles } from '@jahia/cypress'

/**
 * Regression tests for the fine-grained `clamavAdmin` permission.
 *
 * These guard against the gate being silently removed or mismatched across the stack:
 *  - Backend: `@GraphQLRequiresPermission("clamavAdmin")` on the ClamAV GraphQL queries/mutations
 *    is enforced as `session.getNode("/").hasPermission("clamavAdmin")` (root-node ACL check).
 *  - Frontend: `requiredPermission: 'clamavAdmin'` in register.jsx gates the admin route
 *    (`administration-server-systemHealth:10` → /jahia/administration/clamavFilter).
 *  - RBAC content: the module ships the assignable `clamav-filter-administrator` role
 *    (src/main/import/roles.xml) granting only `administrationAccess` + `clamavAdmin`.
 *
 * The "allowed" user is granted that role and nothing else — never `admin` — so the tests prove
 * fine-grained granularity, not merely that a full administrator can pass.
 */
describe('ClamAV Filter — permission enforcement', () => {
    const ROLE_NAME = 'clamav-filter-administrator'
    const DENIED_USER = 'clamavDeniedUser'
    const ALLOWED_USER = 'clamavAllowedUser'
    const PASSWORD = 'ClamavPerm9Pwd'
    const ADMIN_PATH = '/jahia/administration/clamavFilter'

    // Read-only gated query — the safest operation to exercise the allow path.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql')

    // `cy.apollo` yields ApolloQueryResult | FetchResult, and a permission denial surfaces as
    // `graphQLErrors` (Apollo client error) or `errors` (raw GraphQL response) depending on the
    // path. Narrowing from `unknown` covers both without annotating the callbacks below as
    // `never`, which current Cypress/Apollo typings reject outright.
    const errorsOf = (result: unknown): ReadonlyArray<{ message: string }> => {
        const r = result as {
            graphQLErrors?: ReadonlyArray<{ message: string }>
            errors?: ReadonlyArray<{ message: string }>
        }
        return r.graphQLErrors ?? r.errors ?? []
    }

    const querySettingsAs = (username: string) => {
        cy.apolloClient({ username, password: PASSWORD })
        return cy.apollo({ query: getSettings })
    }

    before(() => {
        cy.login()
        createUser(DENIED_USER, PASSWORD)
        createUser(ALLOWED_USER, PASSWORD)
        // The annotation resolves the permission on the JCR root node, so grant the
        // module-shipped role on `/`.
        grantRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER')
    })

    after(() => {
        cy.apolloClient() // Reset the current Apollo client back to root
        cy.login()
        deleteUser(DENIED_USER)
        deleteUser(ALLOWED_USER)
    })

    describe('GraphQL API authorization', () => {
        it('denies the gated query for a user without the permission', () => {
            querySettingsAs(DENIED_USER).then((result) => {
                const errs = errorsOf(result)
                expect(errs, 'denial errors').to.have.length.greaterThan(0)
                expect(errs.map((e: { message: string }) => e.message).join(' ')).to.contain('Permission denied')
            })
        })

        it('allows the gated query for a user granted only the module permission', () => {
            querySettingsAs(ALLOWED_USER).then((result) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0)
                const settings = (
                    result as unknown as { data: { clamav: { settings: { host: string; port: number } } } }
                ).data.clamav.settings
                expect(settings).to.have.property('host')
                expect(settings).to.have.property('port')
            })
        })
    })

    describe('Admin UI authorization', () => {
        it('hides the admin panel from a user without the permission', () => {
            cy.login(DENIED_USER, PASSWORD)
            cy.visit(ADMIN_PATH, { failOnStatusCode: false })
            cy.get('#cf-host').should('not.exist')
        })

        it('shows the admin panel to a user granted only the module permission', () => {
            cy.login(ALLOWED_USER, PASSWORD)
            cy.visit(ADMIN_PATH)
            cy.get('#cf-host').should('be.visible')
        })
    })
})
