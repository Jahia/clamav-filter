import React from 'react';
import {render, screen, fireEvent} from '@testing-library/react';
import PingSection from './PingSection';

jest.mock('react-i18next', () => ({
    useTranslation: () => ({t: key => key})
}));

jest.mock('@jahia/moonstone', () => {
    const React = require('react');
    return {Typography: ({children}) => React.createElement('span', null, children)};
});

describe('PingSection', () => {
    test('renders the test-connection button when idle', () => {
        render(<PingSection pingStatus={null} isPinging={false} onPing={jest.fn()}/>);
        expect(screen.getByText('label.testConnection')).toBeInTheDocument();
    });

    test('shows the testing label and disables the button while pinging', () => {
        render(<PingSection isPinging pingStatus={null} onPing={jest.fn()}/>);
        const btn = screen.getByText('label.testing');
        expect(btn).toBeDisabled();
        expect(btn).toHaveAttribute('aria-busy', 'true');
    });

    test('shows the success alert when ping succeeded', () => {
        render(<PingSection pingStatus="success" isPinging={false} onPing={jest.fn()}/>);
        expect(screen.getByText('label.pingSuccess')).toBeInTheDocument();
    });

    test('shows the error alert when ping failed', () => {
        render(<PingSection pingStatus="error" isPinging={false} onPing={jest.fn()}/>);
        expect(screen.getByText('label.pingError')).toBeInTheDocument();
    });

    test('calls onPing when the button is clicked', () => {
        const onPing = jest.fn();
        render(<PingSection pingStatus={null} isPinging={false} onPing={onPing}/>);
        fireEvent.click(screen.getByText('label.testConnection'));
        expect(onPing).toHaveBeenCalledTimes(1);
    });
});
