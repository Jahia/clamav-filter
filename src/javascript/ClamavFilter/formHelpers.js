// Pure coercion helpers for the connection-settings form. Kept in their own module so they can be
// unit-tested without pulling in the Apollo/Moonstone-dependent component graph.

export const PORT_DEFAULT = 3310;
export const PORT_MIN = 1;
export const PORT_MAX = 65535;
export const CONN_TIMEOUT_DEFAULT = 2000;
export const READ_TIMEOUT_DEFAULT = 20000;

export const clampPort = value => {
    const n = Number.parseInt(value, 10);
    if (Number.isNaN(n)) {
        return PORT_DEFAULT;
    }

    return Math.min(Math.max(n, PORT_MIN), PORT_MAX);
};

export const coerceTimeout = (value, fallback) => {
    const n = Number.parseInt(value, 10);
    return Number.isNaN(n) ? fallback : n;
};

export const normalizeFormState = formState => ({
    host: formState.host,
    port: clampPort(formState.port),
    connectionTimeout: coerceTimeout(formState.connectionTimeout, CONN_TIMEOUT_DEFAULT),
    readTimeout: coerceTimeout(formState.readTimeout, READ_TIMEOUT_DEFAULT)
});
