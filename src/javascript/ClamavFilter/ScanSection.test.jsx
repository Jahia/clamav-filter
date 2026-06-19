import React from 'react';
import {render, screen, fireEvent} from '@testing-library/react';
import ScanSection from './ScanSection';

jest.mock('react-i18next', () => ({
    // Echo the key, appending the signature interpolation when present, so tests can assert on it.
    useTranslation: () => ({t: (key, opts) => (opts && opts.signature ? `${key}:${opts.signature}` : key)})
}));

jest.mock('@jahia/moonstone', () => {
    const React = require('react');
    return {Typography: ({children}) => React.createElement('span', null, children)};
});

const baseProps = () => ({
    isScanDisabled: false,
    isPinging: false,
    pingStatus: 'success',
    scanResult: null,
    isScanning: false,
    selectedFile: null,
    fileInputRef: {current: null},
    onFileChange: jest.fn(),
    onScan: jest.fn()
});

describe('ScanSection', () => {
    test('renders the choose-file and scan buttons', () => {
        render(<ScanSection {...baseProps()}/>);
        expect(screen.getByText('label.chooseFile')).toBeInTheDocument();
        expect(screen.getByText('label.scanFile')).toBeInTheDocument();
    });

    test('disables the buttons and marks the section disabled when scanDisabled is true', () => {
        render(<ScanSection {...baseProps()} isScanDisabled pingStatus="error"/>);
        expect(screen.getByText('label.chooseFile')).toBeDisabled();
        expect(screen.getByText('label.scanFile')).toBeDisabled();
        // The visually-hidden reason is exposed for assistive tech.
        expect(screen.getByText('label.scanDisabledReason')).toBeInTheDocument();
    });

    test('shows the daemon-unavailable alert when ping failed and not pinging', () => {
        render(<ScanSection {...baseProps()} isScanDisabled pingStatus="error"/>);
        expect(screen.getByText('label.scanDaemonUnavailable')).toBeInTheDocument();
    });

    test('announces the selected file name with context', () => {
        render(<ScanSection {...baseProps()} selectedFile={{name: 'eicar.txt'}}/>);
        expect(screen.getByText('label.fileSelected: eicar.txt')).toBeInTheDocument();
    });

    test('renders a success result for a PASSED scan', () => {
        render(<ScanSection {...baseProps()} scanResult={{status: 'PASSED', signature: null}}/>);
        expect(screen.getByText(/label.scanResultPassed/)).toBeInTheDocument();
    });

    test('renders a failure result with the signature for a FAILED scan', () => {
        render(<ScanSection {...baseProps()} scanResult={{status: 'FAILED', signature: 'Win.Test.EICAR'}}/>);
        expect(screen.getByText('label.scanResultFailed:Win.Test.EICAR')).toBeInTheDocument();
    });

    test('renders the SIZE_ERROR result message', () => {
        render(<ScanSection {...baseProps()} scanResult={{status: 'SIZE_ERROR', signature: null}}/>);
        expect(screen.getByText(/label.scanResultSizeError/)).toBeInTheDocument();
    });

    test('calls onScan when the scan button is clicked', () => {
        const props = {...baseProps(), selectedFile: {name: 'clean.txt'}};
        render(<ScanSection {...props}/>);
        fireEvent.click(screen.getByText('label.scanFile'));
        expect(props.onScan).toHaveBeenCalledTimes(1);
    });
});
