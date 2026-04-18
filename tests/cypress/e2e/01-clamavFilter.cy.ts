import {DocumentNode} from 'graphql';

describe('ClamAV Filter', () => {
    const adminPath = '/jahia/administration/clamavFilter';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const ping: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/ping.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    before(() => {
        cy.login();
        // Point clamav-filter at the Docker ClamAV service for the test run
        cy.apollo({
            mutation: saveSettings,
            variables: {
                host: 'clamav',
                port: 3310,
                connectionTimeout: 5000,
                readTimeout: 30000
            }
        });
    });

    // --- GraphQL API ---

    it('returns all settings fields via GraphQL', () => {
        cy.apollo({query: getSettings})
            .its('data.clamavSettings')
            .should(settings => {
                expect(settings).to.have.property('host');
                expect(settings).to.have.property('port');
                expect(settings).to.have.property('connectionTimeout');
                expect(settings).to.have.property('readTimeout');
            });
    });

    it('saves settings via GraphQL and returns true', () => {
        cy.apollo({
            mutation: saveSettings,
            variables: {
                host: 'clamav',
                port: 3310,
                connectionTimeout: 5000,
                readTimeout: 30000
            }
        })
            .its('data.clamavSaveSettings')
            .should('eq', true);
    });

    it('saves settings via GraphQL and reads them back consistently', () => {
        const timeout = 8000;
        cy.apollo({
            mutation: saveSettings,
            variables: {
                host: 'clamav',
                port: 3310,
                connectionTimeout: timeout,
                readTimeout: 30000
            }
        });
        cy.apollo({query: getSettings})
            .its('data.clamavSettings')
            .should(settings => {
                expect(settings.host).to.eq('clamav');
                expect(settings.port).to.eq(3310);
                expect(settings.connectionTimeout).to.eq(timeout);
            });
    });

    it('pings the ClamAV daemon via GraphQL and returns true', () => {
        cy.apollo({query: ping})
            .its('data.clamavPing')
            .should('eq', true);
    });

    // --- Admin UI ---

    it('shows the admin panel with all settings fields', () => {
        cy.login();
        cy.visit(adminPath);

        cy.get('#cf-host').should('be.visible');
        cy.get('#cf-port').should('be.visible');
        cy.get('#cf-conn-timeout').should('be.visible');
        cy.get('#cf-read-timeout').should('be.visible');
    });

    it('shows a ping success alert automatically on page open', () => {
        cy.login();
        cy.visit(adminPath);

        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 30000}).should('be.visible');
    });

    it('shows the file scan section enabled when daemon is reachable', () => {
        cy.login();
        cy.visit(adminPath);

        // Wait for auto-ping to succeed
        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 30000}).should('be.visible');

        // Scan section must not carry the disabled modifier
        cy.get('[class*="cf_scanSection"]')
            .invoke('attr', 'class')
            .should('not.include', 'disabled');
    });

    it('updates settings via the UI and shows a success alert with auto-ping result', () => {
        cy.login();
        cy.visit(adminPath);

        cy.get('#cf-host').clear();
        cy.get('#cf-host').type('clamav');
        cy.get('#cf-port').clear();
        cy.get('#cf-port').invoke('val', '3310');

        cy.contains('button', 'Save settings').click();

        // Save success alert
        cy.get('[class*="cf_actions"] [class*="cf_alert--success"]').should('be.visible');
        // Auto-ping success alert appears immediately after save
        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 15000}).should('be.visible');
    });

    it('shows a ping success alert when clicking the Test Connection button', () => {
        cy.login();
        cy.visit(adminPath);

        // Wait for initial auto-ping to finish, then trigger a manual ping
        cy.get('[class*="cf_pingSection"] [class*="cf_alert"]', {timeout: 30000}).should('be.visible');

        cy.contains('button', 'Test connection').click();

        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 15000}).should('be.visible');
    });

    // --- File Scan ---

    it('scans a clean file and gets a PASSED result', () => {
        cy.login();
        cy.visit(adminPath);

        // Wait for auto-ping success so the scan section is enabled
        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 30000}).should('be.visible');

        cy.get('#cf-scan-file').selectFile('cypress/fixtures/files/clean.txt', {force: true});
        cy.contains('button', 'Scan file').should('not.be.disabled').click();

        cy.get('[class*="cf_scanSection"] [class*="cf_alert--success"]', {timeout: 30000})
            .should('be.visible')
            .and('contain', 'no threats detected');
    });

    it('scans an EICAR test file and gets a FAILED result with signature', () => {
        cy.login();
        cy.visit(adminPath);

        // Wait for auto-ping success so the scan section is enabled
        cy.get('[class*="cf_pingSection"] [class*="cf_alert--success"]', {timeout: 30000}).should('be.visible');

        cy.get('#cf-scan-file').selectFile('cypress/fixtures/files/eicar.txt', {force: true});
        cy.contains('button', 'Scan file').should('not.be.disabled').click();

        cy.get('[class*="cf_scanSection"] [class*="cf_alert--error"]', {timeout: 30000})
            .should('be.visible')
            .and('contain', 'threat detected');
    });
});
