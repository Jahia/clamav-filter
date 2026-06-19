import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Typography} from '@jahia/moonstone';
import styles from './ClamavFilter.scss';

const PingSection = ({pingStatus, isPinging, onPing}) => {
    const {t} = useTranslation('clamav-filter');
    return (
        <div className={styles.cf_pingSection}>
            <h3 className={styles.cf_sectionTitle}>{t('label.connectionTestTitle')}</h3>
            <Typography>{t('label.pingDescription')}</Typography>
            {pingStatus === 'success' && (
                <div aria-hidden="true" className={`${styles.cf_alert} ${styles['cf_alert--success']}`}>
                    <span aria-hidden="true" className={styles.cf_alertIcon}>✓</span> {t('label.pingSuccess')}
                </div>
            )}
            {pingStatus === 'error' && (
                <div aria-hidden="true" className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                    <span aria-hidden="true" className={styles.cf_alertIcon}>✕</span> {t('label.pingError')}
                </div>
            )}
            <button
                type="button"
                className={styles.cf_pingBtn}
                disabled={isPinging}
                aria-busy={isPinging}
                onClick={onPing}
            >
                {isPinging ? t('label.testing') : t('label.testConnection')}
            </button>
        </div>
    );
};

PingSection.propTypes = {
    pingStatus: PropTypes.oneOf(['success', 'error']),
    isPinging: PropTypes.bool,
    onPing: PropTypes.func.isRequired
};

export default PingSection;
