// Pure coercion helpers for the connection-settings form. Kept in their own module so they can be
// unit-tested without pulling in the Apollo/Moonstone-dependent component graph.

export const PORT_DEFAULT = 3310;
export const PORT_MIN = 1;
export const PORT_MAX = 65535;
export const CONN_TIMEOUT_DEFAULT = 2000;
export const READ_TIMEOUT_DEFAULT = 20000;
export const CONN_TIMEOUT_MIN = 100;
export const READ_TIMEOUT_MIN = 1000;
// Mirrors ClamavConstants.MAX_TIMEOUT_MS on the server.
export const MAX_TIMEOUT = 300000;

export const clampPort = value => {
    const n = Number.parseInt(value, 10);
    if (Number.isNaN(n)) {
        return PORT_DEFAULT;
    }

    return Math.min(Math.max(n, PORT_MIN), PORT_MAX);
};

// Coerce to an integer within [min, MAX_TIMEOUT]; fall back when not a number. Clamping client-side
// keeps the value the user sees in sync with what is sent, and avoids a silent server rejection of
// a zero/negative/over-range timeout.
export const coerceTimeout = (value, fallback, min = 1) => {
    const n = Number.parseInt(value, 10);
    if (Number.isNaN(n)) {
        return fallback;
    }

    return Math.min(Math.max(n, min), MAX_TIMEOUT);
};

export const normalizeFormState = formState => ({
    host: formState.host,
    port: clampPort(formState.port),
    connectionTimeout: coerceTimeout(formState.connectionTimeout, CONN_TIMEOUT_DEFAULT, CONN_TIMEOUT_MIN),
    readTimeout: coerceTimeout(formState.readTimeout, READ_TIMEOUT_DEFAULT, READ_TIMEOUT_MIN)
});
