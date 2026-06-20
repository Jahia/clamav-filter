import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query {
        clamav {
            settings {
                host
                port
                connectionTimeout
                readTimeout
            }
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation ClamavSaveSettings($host: String!, $port: Int!, $connectionTimeout: Int, $readTimeout: Int) {
        clamav {
            saveSettings(host: $host, port: $port, connectionTimeout: $connectionTimeout, readTimeout: $readTimeout)
        }
    }
`;

export const PING = gql`
    query ClamavPing {
        clamav {
            ping
        }
    }
`;

export const SCAN_TEST = gql`
    query ClamavScanTest($content: String!) {
        clamav {
            scanTest(content: $content) {
                status
                signature
            }
        }
    }
`;
