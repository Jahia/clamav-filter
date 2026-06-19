import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Typography} from '@jahia/moonstone';
import styles from './ClamavFilter.scss';

const SCAN_LABELS = {
    PASSED: 'label.scanResultPassed',
    FAILED: 'label.scanResultFailed',
    ERROR: 'label.scanResultError',
    CONNECTION_FAILED: 'label.scanResultConnectionFailed',
    SIZE_ERROR: 'label.scanResultSizeError'
};

const ScanSection = ({isScanDisabled, isPinging, pingStatus, scanResult, isScanning, selectedFile, fileInputRef, onFileChange, onScan}) => {
    const {t} = useTranslation('clamav-filter');
    const sectionClassName = [
        styles.cf_scanSection,
        isScanDisabled && styles['cf_scanSection--disabled']
    ].filter(Boolean).join(' ');

    return (
        <div
            className={sectionClassName}
            aria-describedby={isScanDisabled ? 'cf-scan-disabled-reason' : undefined}
        >
            {isScanDisabled && (
                <span id="cf-scan-disabled-reason" className={styles.cf_sr_only}>
                    {t('label.scanDisabledReason')}
                </span>
            )}
            <h3 className={styles.cf_sectionTitle}>{t('label.scanTitle')}</h3>
            <Typography>{t('label.scanDescription')}</Typography>
            {!isPinging && pingStatus === 'error' && (
                <div aria-hidden="true" className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                    <span aria-hidden="true" className={styles.cf_alertIcon}>✕</span> {t('label.scanDaemonUnavailable')}
                </div>
            )}
            <div className={styles.cf_scanRow}>
                {/* Button triggers the hidden file input; the input is aria-hidden since this button is the AT-facing control */}
                <button
                    type="button"
                    className={styles.cf_fileLabel}
                    disabled={isScanDisabled}
                    onClick={() => fileInputRef.current?.click()}
                >
                    {t('label.chooseFile')}
                </button>
                <input
                    ref={fileInputRef}
                    type="file"
                    id="cf-scan-file"
                    className={styles.cf_fileInput}
                    aria-hidden="true"
                    tabIndex={-1}
                    onChange={onFileChange}
                />
                <output aria-live="polite" aria-atomic="true" className={styles.cf_fileName}>
                    {selectedFile ? `${t('label.fileSelected')}: ${selectedFile.name}` : ''}
                </output>
            </div>
            {scanResult && (
                <div
                    aria-hidden="true"
                    className={`${styles.cf_alert} ${scanResult.status === 'PASSED' ? styles['cf_alert--success'] : styles['cf_alert--error']}`}
                >
                    <span aria-hidden="true" className={styles.cf_alertIcon}>{scanResult.status === 'PASSED' ? '✓' : '✕'}</span>{' '}
                    {t(SCAN_LABELS[scanResult.status], {signature: scanResult.signature || 'unknown'})}
                </div>
            )}
            <button
                type="button"
                className={styles.cf_pingBtn}
                disabled={!selectedFile || isScanning || isScanDisabled}
                aria-busy={isScanning}
                onClick={onScan}
            >
                {isScanning ? t('label.scanning') : t('label.scanFile')}
            </button>
        </div>
    );
};

ScanSection.propTypes = {
    isScanDisabled: PropTypes.bool,
    isPinging: PropTypes.bool,
    pingStatus: PropTypes.oneOf(['success', 'error']),
    scanResult: PropTypes.shape({
        status: PropTypes.string,
        signature: PropTypes.string
    }),
    isScanning: PropTypes.bool,
    selectedFile: PropTypes.shape({
        name: PropTypes.string,
        size: PropTypes.number
    }),
    fileInputRef: PropTypes.shape({current: PropTypes.instanceOf(Element)}).isRequired,
    onFileChange: PropTypes.func.isRequired,
    onScan: PropTypes.func.isRequired
};

export default ScanSection;
