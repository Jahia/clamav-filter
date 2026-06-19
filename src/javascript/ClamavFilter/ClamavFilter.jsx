import React, {useEffect, useRef, useState} from 'react';
import {useLazyQuery, useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Loader, Typography} from '@jahia/moonstone';
import styles from './ClamavFilter.scss';
import {GET_SETTINGS, PING, SAVE_SETTINGS, SCAN_TEST} from './ClamavFilter.gql';
import ConnectionSettingsForm from './ConnectionSettingsForm';
import PingSection from './PingSection';
import ScanSection from './ScanSection';
import {
    clampPort,
    coerceTimeout,
    CONN_TIMEOUT_DEFAULT,
    CONN_TIMEOUT_MIN,
    normalizeFormState,
    READ_TIMEOUT_DEFAULT,
    READ_TIMEOUT_MIN
} from './formHelpers';

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

const SCAN_LABELS = {
    PASSED: 'label.scanResultPassed',
    FAILED: 'label.scanResultFailed',
    ERROR: 'label.scanResultError',
    CONNECTION_FAILED: 'label.scanResultConnectionFailed',
    SIZE_ERROR: 'label.scanResultSizeError'
};

export const ClamavFilterAdmin = () => {
    const {t} = useTranslation('clamav-filter');
    const [saveStatus, setSaveStatus] = useState(null);
    const [pingStatus, setPingStatus] = useState(null);
    const [selectedFile, setSelectedFile] = useState(null);
    const [scanResult, setScanResult] = useState(null);
    const fileInputRef = useRef(null);

    useEffect(() => {
        document.title = `${t('label.title')} - Jahia Administration`;
    }, [t]);

    const [formState, setFormState] = useState({
        host: 'localhost',
        port: 3310,
        connectionTimeout: 2000,
        readTimeout: 20000
    });

    const {loading} = useQuery(GET_SETTINGS, {
        fetchPolicy: 'network-only',
        onCompleted: data => {
            const s = data?.clamavSettings;
            if (s) {
                setFormState({
                    host: s.host ?? 'localhost',
                    port: s.port ?? 3310,
                    connectionTimeout: s.connectionTimeout ?? 2000,
                    readTimeout: s.readTimeout ?? 20000
                });
            }
        }
    });

    const [saveSettings, {loading: saving}] = useMutation(SAVE_SETTINGS);
    const [runPing, {loading: pinging}] = useLazyQuery(PING, {fetchPolicy: 'network-only'});
    const [runScan, {loading: scanning}] = useLazyQuery(SCAN_TEST, {fetchPolicy: 'network-only'});

    useEffect(() => {
        runPing().then(result => {
            setPingStatus(result.data?.clamavPing ? 'success' : 'error');
        }).catch(() => setPingStatus('error'));
    }, [runPing]);

    const scanDisabled = pinging || pingStatus !== 'success';

    const handleFieldChange = (field, value) => {
        setFormState(prev => ({...prev, [field]: value}));
    };

    const handleFieldBlur = field => {
        setFormState(prev => {
            if (field === 'port') {
                return {...prev, port: clampPort(prev.port)};
            }

            if (field === 'connectionTimeout') {
                return {...prev, connectionTimeout: coerceTimeout(prev.connectionTimeout, CONN_TIMEOUT_DEFAULT, CONN_TIMEOUT_MIN)};
            }

            if (field === 'readTimeout') {
                return {...prev, readTimeout: coerceTimeout(prev.readTimeout, READ_TIMEOUT_DEFAULT, READ_TIMEOUT_MIN)};
            }

            return prev;
        });
    };

    const handleSave = async () => {
        setSaveStatus(null);
        setPingStatus(null);
        const normalized = normalizeFormState(formState);
        setFormState(normalized);
        try {
            const result = await saveSettings({
                variables: {
                    host: normalized.host,
                    port: normalized.port,
                    connectionTimeout: normalized.connectionTimeout,
                    readTimeout: normalized.readTimeout
                }
            });
            setSaveStatus(result.data?.clamavSaveSettings ? 'success' : 'error');
            if (result.data?.clamavSaveSettings) {
                const pingResult = await runPing();
                setPingStatus(pingResult.data?.clamavPing ? 'success' : 'error');
            }
        } catch {
            setSaveStatus('error');
        }
    };

    const handlePing = async () => {
        // Do not clear saveStatus here: testing the connection is a separate action and must not
        // wipe a still-relevant save-success/error banner.
        setPingStatus(null);
        try {
            const result = await runPing();
            setPingStatus(result.data?.clamavPing ? 'success' : 'error');
        } catch {
            setPingStatus('error');
        }
    };

    const handleFileChange = e => {
        setSelectedFile(e.target.files[0] || null);
        setScanResult(null);
    };

    const handleScan = () => {
        if (!selectedFile) {
            return;
        }

        if (selectedFile.size > MAX_FILE_SIZE) {
            setScanResult({status: 'SIZE_ERROR', signature: null});
            return;
        }

        const reader = new FileReader();
        reader.onload = async () => {
            const base64 = reader.result.split(',')[1];
            try {
                const result = await runScan({variables: {content: base64}});
                setScanResult(result.data?.clamavScanTest ?? {status: 'ERROR', signature: null});
            } catch {
                setScanResult({status: 'ERROR', signature: null});
            }
        };

        reader.readAsDataURL(selectedFile);
    };

    if (loading) {
        return (
            <output className={styles.cf_loading} aria-live="polite" aria-atomic="true">
                <span className={styles.cf_sr_only}>{t('label.loading')}</span>
                <Loader size="big"/>
            </output>
        );
    }

    return (
        <div className={styles.cf_container}>
            {/* Fixed alert + status live regions — always in DOM so AT subscriptions stay stable */}
            <div role="alert" aria-live="assertive" aria-atomic="true" className={styles.cf_sr_only}>
                {saveStatus === 'error' ? t('label.saveError') : ''}
            </div>
            <output aria-live="polite" aria-atomic="true" className={styles.cf_sr_only}>
                {saveStatus === 'success' ? t('label.saveSuccess') : ''}
            </output>
            <div role="alert" aria-live="assertive" aria-atomic="true" className={styles.cf_sr_only}>
                {pingStatus === 'error' ? t('label.pingError') : ''}
            </div>
            <output aria-live="polite" aria-atomic="true" className={styles.cf_sr_only}>
                {pingStatus === 'success' ? t('label.pingSuccess') : ''}
            </output>
            <div role="alert" aria-live="assertive" aria-atomic="true" className={styles.cf_sr_only}>
                {scanResult && scanResult.status !== 'PASSED' ?
                    t(SCAN_LABELS[scanResult.status], {signature: scanResult.signature || 'unknown'}) : ''}
            </div>
            <output aria-live="polite" aria-atomic="true" className={styles.cf_sr_only}>
                {scanResult?.status === 'PASSED' ?
                    t(SCAN_LABELS[scanResult.status], {signature: scanResult.signature || 'unknown'}) : ''}
            </output>

            <div className={styles.cf_header}>
                <h2>{t('label.title')}</h2>
            </div>

            <div className={styles.cf_description}>
                <Typography>{t('label.description')}</Typography>
            </div>

            <ConnectionSettingsForm
                formState={formState}
                isSaving={saving}
                saveStatus={saveStatus}
                onFieldChange={handleFieldChange}
                onFieldBlur={handleFieldBlur}
                onSubmit={e => {
                    e.preventDefault();
                    handleSave();
                }}
            />

            <PingSection
                pingStatus={pingStatus}
                isPinging={pinging}
                onPing={handlePing}
            />

            <ScanSection
                isScanDisabled={scanDisabled}
                isPinging={pinging}
                pingStatus={pingStatus}
                scanResult={scanResult}
                isScanning={scanning}
                selectedFile={selectedFile}
                fileInputRef={fileInputRef}
                onFileChange={handleFileChange}
                onScan={handleScan}
            />
        </div>
    );
};

export default ClamavFilterAdmin;
