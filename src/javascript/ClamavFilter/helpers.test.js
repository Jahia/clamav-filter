import {clampPort, coerceTimeout, normalizeFormState} from './formHelpers';

describe('clampPort', () => {
    test('keeps an in-range port', () => {
        expect(clampPort('8080')).toBe(8080);
    });

    test('clamps above the maximum to 65535', () => {
        expect(clampPort('70000')).toBe(65535);
    });

    test('clamps below the minimum to 1', () => {
        expect(clampPort('0')).toBe(1);
    });

    test('falls back to the default 3310 for a non-numeric / empty value', () => {
        expect(clampPort('')).toBe(3310);
        expect(clampPort('abc')).toBe(3310);
    });
});

describe('coerceTimeout', () => {
    test('returns the parsed integer when valid', () => {
        expect(coerceTimeout('5000', 2000)).toBe(5000);
    });

    test('returns the fallback for a non-numeric / empty value', () => {
        expect(coerceTimeout('', 2000)).toBe(2000);
        expect(coerceTimeout('x', 20000)).toBe(20000);
    });

    test('floors a below-minimum value to the provided minimum', () => {
        expect(coerceTimeout('-5', 2000, 100)).toBe(100);
        expect(coerceTimeout('0', 2000, 100)).toBe(100);
    });

    test('caps an over-range value at the server maximum (300000)', () => {
        expect(coerceTimeout('999999', 2000, 100)).toBe(300000);
    });
});

describe('normalizeFormState', () => {
    test('coerces every field, clamping the port and defaulting timeouts', () => {
        const result = normalizeFormState({
            host: 'clamav',
            port: '99999',
            connectionTimeout: '',
            readTimeout: '15000'
        });
        expect(result).toEqual({
            host: 'clamav',
            port: 65535,
            connectionTimeout: 2000,
            readTimeout: 15000
        });
    });

    test('preserves a fully valid form unchanged', () => {
        const result = normalizeFormState({
            host: 'localhost',
            port: '3310',
            connectionTimeout: '2000',
            readTimeout: '20000'
        });
        expect(result).toEqual({
            host: 'localhost',
            port: 3310,
            connectionTimeout: 2000,
            readTimeout: 20000
        });
    });
});
