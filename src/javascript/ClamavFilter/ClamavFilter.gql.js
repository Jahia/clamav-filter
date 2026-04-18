import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query {
        clamavSettings {
            host
            port
            connectionTimeout
            readTimeout
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation ClamavSaveSettings($host: String!, $port: Int!, $connectionTimeout: Int, $readTimeout: Int) {
        clamavSaveSettings(host: $host, port: $port, connectionTimeout: $connectionTimeout, readTimeout: $readTimeout)
    }
`;

export const PING = gql`
    query ClamavPing {
        clamavPing
    }
`;

export const SCAN_TEST = gql`
    query ClamavScanTest($content: String!) {
        clamavScanTest(content: $content) {
            status
            signature
        }
    }
`;
