import React, {useEffect, useState} from 'react';
import {useLazyQuery, useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './ClamavFilter.scss';
import {GET_SETTINGS, PING, SAVE_SETTINGS, SCAN_TEST} from './ClamavFilter.gql';

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

    const handleSave = async () => {
        setSaveStatus(null);
        setPingStatus(null);
        try {
            const result = await saveSettings({
                variables: {
                    host: formState.host,
                    port: formState.port,
                    connectionTimeout: formState.connectionTimeout,
                    readTimeout: formState.readTimeout
                }
            });
            setSaveStatus(result.data?.clamavSaveSettings ? 'success' : 'error');
            if (result.data?.clamavSaveSettings) {
                const pingResult = await runPing();
                setPingStatus(pingResult.data?.clamavPing ? 'success' : 'error');
            }
        } catch (err) {
            console.error('Failed to save settings:', err);
            setSaveStatus('error');
        }
    };

    const handlePing = async () => {
        setPingStatus(null);
        try {
            const result = await runPing();
            setPingStatus(result.data?.clamavPing ? 'success' : 'error');
        } catch (err) {
            console.error('Ping failed:', err);
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
            } catch (err) {
                console.error('Scan failed:', err);
                setScanResult({status: 'ERROR', signature: null});
            }
        };

        reader.readAsDataURL(selectedFile);
    };

    if (loading) {
        return (
            <div className={styles.cf_loading}>
                <Loader size="big"/>
            </div>
        );
    }

    return (
        <div className={styles.cf_container}>
            <div className={styles.cf_header}>
                <h2>{t('label.title')}</h2>
            </div>

            <div className={styles.cf_description}>
                <Typography>{t('label.description')}</Typography>
            </div>

            <div className={styles.cf_form}>
                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-host">
                        {t('label.host')}
                    </label>
                    <input
                        type="text"
                        id="cf-host"
                        className={styles.cf_inputWide}
                        value={formState.host}
                        onChange={e => setFormState(prev => ({...prev, host: e.target.value}))}
                    />
                </div>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-port">
                        {t('label.port')}
                    </label>
                    <input
                        type="number"
                        id="cf-port"
                        className={styles.cf_input}
                        min="1"
                        max="65535"
                        value={formState.port}
                        onChange={e => setFormState(prev => ({
                            ...prev,
                            port: Number.parseInt(e.target.value, 10) || 3310
                        }))}
                    />
                </div>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-conn-timeout">
                        {t('label.connectionTimeout')}
                        <span className={styles.cf_tooltip} title={t('label.connectionTimeoutTooltip')}>ⓘ</span>
                    </label>
                    <input
                        type="number"
                        id="cf-conn-timeout"
                        className={styles.cf_input}
                        min="100"
                        value={formState.connectionTimeout}
                        onChange={e => setFormState(prev => ({
                            ...prev,
                            connectionTimeout: Number.parseInt(e.target.value, 10) || 2000
                        }))}
                    />
                </div>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-read-timeout">
                        {t('label.readTimeout')}
                        <span className={styles.cf_tooltip} title={t('label.readTimeoutTooltip')}>ⓘ</span>
                    </label>
                    <input
                        type="number"
                        id="cf-read-timeout"
                        className={styles.cf_input}
                        min="1000"
                        value={formState.readTimeout}
                        onChange={e => setFormState(prev => ({
                            ...prev,
                            readTimeout: Number.parseInt(e.target.value, 10) || 20000
                        }))}
                    />
                </div>
            </div>

            <div className={styles.cf_actions}>
                {saveStatus === 'success' && (
                    <div className={`${styles.cf_alert} ${styles['cf_alert--success']}`}>
                        {t('label.saveSuccess')}
                    </div>
                )}
                {saveStatus === 'error' && (
                    <div className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                        {t('label.saveError')}
                    </div>
                )}
                <Button
                    label={t('label.save')}
                    variant="primary"
                    isDisabled={saving}
                    onClick={handleSave}
                />
            </div>

            <div className={styles.cf_pingSection}>
                <Typography>{t('label.pingDescription')}</Typography>
                {pingStatus === 'success' && (
                    <div className={`${styles.cf_alert} ${styles['cf_alert--success']}`}>
                        {t('label.pingSuccess')}
                    </div>
                )}
                {pingStatus === 'error' && (
                    <div className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                        {t('label.pingError')}
                    </div>
                )}
                <button
                    type="button"
                    className={styles.cf_pingBtn}
                    disabled={pinging}
                    onClick={handlePing}
                >
                    {pinging ? t('label.testing') : t('label.testConnection')}
                </button>
            </div>

            <div className={`${styles.cf_scanSection}${scanDisabled ? ` ${styles['cf_scanSection--disabled']}` : ''}`}>
                <h3 className={styles.cf_sectionTitle}>{t('label.scanTitle')}</h3>
                <Typography>{t('label.scanDescription')}</Typography>
                {!pinging && pingStatus === 'error' && (
                    <div className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                        {t('label.scanDaemonUnavailable')}
                    </div>
                )}
                <div className={styles.cf_scanRow}>
                    <label className={styles.cf_fileLabel} htmlFor="cf-scan-file">
                        {t('label.chooseFile')}
                    </label>
                    <input
                        type="file"
                        id="cf-scan-file"
                        className={styles.cf_fileInput}
                        onChange={handleFileChange}
                    />
                    {selectedFile && (
                        <span className={styles.cf_fileName}>{selectedFile.name}</span>
                    )}
                </div>
                {scanResult && (
                    <div className={`${styles.cf_alert} ${scanResult.status === 'PASSED' ? styles['cf_alert--success'] : styles['cf_alert--error']}`}>
                        {t(SCAN_LABELS[scanResult.status], {signature: scanResult.signature || 'unknown'})}
                    </div>
                )}
                <button
                    type="button"
                    className={styles.cf_pingBtn}
                    disabled={!selectedFile || scanning || scanDisabled}
                    onClick={handleScan}
                >
                    {scanning ? t('label.scanning') : t('label.scanFile')}
                </button>
            </div>
        </div>
    );
};

export default ClamavFilterAdmin;
