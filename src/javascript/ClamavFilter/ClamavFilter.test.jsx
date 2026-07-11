import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {useQuery, useMutation, useLazyQuery} from '@apollo/client';
import {ClamavFilterAdmin} from './ClamavFilter';
import {PING, SCAN_TEST} from './ClamavFilter.gql';

// First test file for ClamavFilter.jsx (ClamavFilterAdmin) — previously only its child
// presentational components (ScanSection, PingSection, ConnectionSettingsForm) had test coverage;
// the orchestration logic (handleScan's size gate, handleFieldBlur's coercion mapping) was untested.
jest.mock('react-i18next', () => ({
    // Echo the key, appending the signature interpolation when present, matching the other test files.
    useTranslation: () => ({t: (key, opts) => (opts && opts.signature ? `${key}:${opts.signature}` : key)})
}));

jest.mock('@jahia/moonstone', () => {
    const ReactLib = require('react');
    return {
        Loader: () => ReactLib.createElement('span', null, 'loading'),
        Typography: ({children}) => ReactLib.createElement('span', null, children),
        Button: ({label, isDisabled}) => ReactLib.createElement('button', {type: 'submit', disabled: isDisabled}, label)
    };
});

jest.mock('@apollo/client', () => ({
    ...jest.requireActual('@apollo/client'),
    useQuery: jest.fn(),
    useMutation: jest.fn(),
    useLazyQuery: jest.fn()
}));

const MAX_FILE_SIZE = 10 * 1024 * 1024; // Mirrors ClamavFilter.jsx's own MAX_FILE_SIZE

const oversizeFile = () => {
    const file = new File(['x'], 'oversize.bin', {type: 'application/octet-stream'});
    Object.defineProperty(file, 'size', {value: MAX_FILE_SIZE + 1});
    return file;
};

const smallFile = () => {
    const file = new File(['clean content'], 'clean.txt', {type: 'text/plain'});
    Object.defineProperty(file, 'size', {value: 1024});
    return file;
};

describe('ClamavFilterAdmin', () => {
    let runPingMock;
    let runScanMock;
    let saveSettingsMock;

    beforeEach(() => {
        jest.clearAllMocks();

        runPingMock = jest.fn().mockResolvedValue({data: {clamav: {ping: true}}});
        runScanMock = jest.fn().mockResolvedValue({data: {clamav: {scanTest: {status: 'PASSED', signature: null}}}});
        saveSettingsMock = jest.fn().mockResolvedValue({data: {clamav: {saveSettings: true}}});

        useQuery.mockReturnValue({loading: false});
        useMutation.mockReturnValue([saveSettingsMock, {loading: false}]);
        useLazyQuery.mockImplementation(query => {
            if (query === PING) {
                return [runPingMock, {loading: false}];
            }

            if (query === SCAN_TEST) {
                return [runScanMock, {loading: false}];
            }

            throw new Error('Unexpected useLazyQuery call in test — unmocked query document');
        });
    });

    const renderAndWaitForPingSuccess = async () => {
        render(<ClamavFilterAdmin/>);
        // The scan button stays disabled (isScanDisabled) until the mocked auto-ping effect
        // resolves — wait for that before interacting with the scan section.
        await waitFor(() => expect(runPingMock).toHaveBeenCalledTimes(1));
    };

    describe('handleScan: 10 MB client-side size gate (U5)', () => {
        test('an oversized file is rejected with SIZE_ERROR before SCAN_TEST is ever invoked', async () => {
            await renderAndWaitForPingSuccess();

            fireEvent.change(document.getElementById('cf-scan-file'), {target: {files: [oversizeFile()]}});

            await waitFor(() => expect(screen.getByText('label.scanFile')).not.toBeDisabled());
            fireEvent.click(screen.getByText('label.scanFile'));

            // The crucial U5 assertion: the gate rejects BEFORE the file is ever read/encoded and
            // sent to clamavScanTest — the GraphQL lazy query function must never be called.
            expect(runScanMock).not.toHaveBeenCalled();
            // Rendered twice (a visible alert plus a sr-only live-region announcement) by design.
            const sizeErrorNodes = await screen.findAllByText(/label.scanResultSizeError/);
            expect(sizeErrorNodes.length).toBeGreaterThan(0);
        });

        test('a file within the size limit is scanned (SCAN_TEST is invoked, proving the gate is not always-on)', async () => {
            await renderAndWaitForPingSuccess();

            fireEvent.change(document.getElementById('cf-scan-file'), {target: {files: [smallFile()]}});

            await waitFor(() => expect(screen.getByText('label.scanFile')).not.toBeDisabled());
            fireEvent.click(screen.getByText('label.scanFile'));

            await waitFor(() => expect(runScanMock).toHaveBeenCalledTimes(1));
        });
    });

    describe('handleFieldBlur: field-name -> coercion-function mapping (U6 residual)', () => {
        test('blurring the port field clamps an out-of-range value via clampPort', async () => {
            render(<ClamavFilterAdmin/>);
            const portInput = await screen.findByLabelText('label.port');

            fireEvent.change(portInput, {target: {value: '999999'}});
            fireEvent.blur(portInput);

            await waitFor(() => expect(portInput).toHaveValue(65535));
        });

        test('blurring the connectionTimeout field coerces via coerceTimeout with the connection min/default', async () => {
            render(<ClamavFilterAdmin/>);
            const connTimeoutInput = await screen.findByLabelText('label.connectionTimeout', {exact: false});

            fireEvent.change(connTimeoutInput, {target: {value: '0'}});
            fireEvent.blur(connTimeoutInput);

            // CONN_TIMEOUT_MIN is 100 (formHelpers.js) — a 0 value floors to the minimum, not the
            // READ_TIMEOUT_MIN (1000) or the default: proves this field maps to the connection-timeout
            // coercion call specifically, not a shared/miswired one.
            await waitFor(() => expect(connTimeoutInput).toHaveValue(100));
        });

        test('blurring the readTimeout field coerces via coerceTimeout with the read min/default', async () => {
            render(<ClamavFilterAdmin/>);
            const readTimeoutInput = await screen.findByLabelText('label.readTimeout', {exact: false});

            fireEvent.change(readTimeoutInput, {target: {value: '0'}});
            fireEvent.blur(readTimeoutInput);

            // READ_TIMEOUT_MIN is 1000 (formHelpers.js) — distinct from the connection-timeout
            // field's floor of 100, proving handleFieldBlur maps each field to its own min/default pair.
            await waitFor(() => expect(readTimeoutInput).toHaveValue(1000));
        });

        test('blurring the host field is a no-op (no coercion function mapped to it)', async () => {
            render(<ClamavFilterAdmin/>);
            const hostInput = await screen.findByLabelText('label.host');

            fireEvent.change(hostInput, {target: {value: 'clamav.internal'}});
            fireEvent.blur(hostInput);

            await waitFor(() => expect(hostInput).toHaveValue('clamav.internal'));
        });
    });
});
