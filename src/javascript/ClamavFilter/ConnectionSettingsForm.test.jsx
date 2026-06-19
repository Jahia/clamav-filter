import React from 'react';
import {render, screen, fireEvent} from '@testing-library/react';
import ConnectionSettingsForm from './ConnectionSettingsForm';

jest.mock('react-i18next', () => ({
    useTranslation: () => ({t: key => key})
}));

jest.mock('@jahia/moonstone', () => {
    const React = require('react');
    return {
        Button: ({label, isDisabled}) =>
            React.createElement('button', {type: 'button', disabled: isDisabled}, label)
    };
});

const baseProps = () => ({
    formState: {host: 'clamav.internal', port: 3310, connectionTimeout: 2000, readTimeout: 20000},
    isSaving: false,
    saveStatus: null,
    onFieldChange: jest.fn(),
    onFieldBlur: jest.fn(),
    onSubmit: jest.fn(e => e.preventDefault())
});

describe('ConnectionSettingsForm', () => {
    test('renders the loaded settings values in the inputs', () => {
        render(<ConnectionSettingsForm {...baseProps()}/>);
        expect(screen.getByLabelText('label.host')).toHaveValue('clamav.internal');
        expect(screen.getByLabelText('label.port')).toHaveValue(3310);
    });

    test('calls onFieldChange with the field name and new value on edit', () => {
        const props = baseProps();
        render(<ConnectionSettingsForm {...props}/>);
        fireEvent.change(screen.getByLabelText('label.host'), {target: {value: 'newhost'}});
        expect(props.onFieldChange).toHaveBeenCalledWith('host', 'newhost');
    });

    test('calls onFieldBlur with the field name on blur', () => {
        const props = baseProps();
        render(<ConnectionSettingsForm {...props}/>);
        fireEvent.blur(screen.getByLabelText('label.port'));
        expect(props.onFieldBlur).toHaveBeenCalledWith('port');
    });

    test('shows the success alert when saveStatus is success', () => {
        render(<ConnectionSettingsForm {...baseProps()} saveStatus="success"/>);
        expect(screen.getByText('label.saveSuccess')).toBeInTheDocument();
    });

    test('shows the error alert when saveStatus is error', () => {
        render(<ConnectionSettingsForm {...baseProps()} saveStatus="error"/>);
        expect(screen.getByText('label.saveError')).toBeInTheDocument();
    });

    test('submits the form via onSubmit', () => {
        const props = baseProps();
        const {container} = render(<ConnectionSettingsForm {...props}/>);
        fireEvent.submit(container.querySelector('form'));
        expect(props.onSubmit).toHaveBeenCalledTimes(1);
    });

    test('disables the save button while saving', () => {
        render(<ConnectionSettingsForm {...baseProps()} isSaving/>);
        expect(screen.getByText('label.save')).toBeDisabled();
    });
});
