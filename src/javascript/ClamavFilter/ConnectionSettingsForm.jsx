import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Button} from '@jahia/moonstone';
import styles from './ClamavFilter.scss';

const ConnectionSettingsForm = ({formState, onFieldChange, onFieldBlur, onSubmit, isSaving, saveStatus}) => {
    const {t} = useTranslation('clamav-filter');
    return (
        <form
        className={styles.cf_form}
        onSubmit={onSubmit}
        >
            <fieldset className={styles.cf_fieldset}>
                <legend className={styles.cf_fieldsetLegend}>{t('label.connectionSettings')}</legend>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-host">
                        {t('label.host')}
                    </label>
                    <input
                    type="text"
                    id="cf-host"
                    className={styles.cf_inputWide}
                    value={formState.host}
                    autoComplete="off"
                    aria-describedby="cf-host-hint"
                    onChange={e => onFieldChange('host', e.target.value)}
                    onBlur={() => onFieldBlur('host')}
                />
                    <span id="cf-host-hint" className={styles.cf_fieldHint}>
                        {t('label.hostHint')}
                    </span>
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
                    aria-describedby="cf-port-hint"
                    value={formState.port}
                    onChange={e => onFieldChange('port', e.target.value)}
                    onBlur={() => onFieldBlur('port')}
                />
                    <span id="cf-port-hint" className={styles.cf_fieldHint}>
                        {t('label.portHint')}
                    </span>
                </div>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-conn-timeout">
                        {t('label.connectionTimeout')}
                        {/* aria-hidden — description provided via aria-describedby on the input */}
                        <span aria-hidden="true" className={styles.cf_tooltip}>ⓘ</span>
                    </label>
                    <input
                    type="number"
                    id="cf-conn-timeout"
                    className={styles.cf_input}
                    min="100"
                    aria-describedby="cf-conn-timeout-hint"
                    value={formState.connectionTimeout}
                    onChange={e => onFieldChange('connectionTimeout', e.target.value)}
                    onBlur={() => onFieldBlur('connectionTimeout')}
                />
                    <span id="cf-conn-timeout-hint" className={styles.cf_fieldHint}>
                        {t('label.connectionTimeoutTooltip')}
                    </span>
                </div>

                <div className={styles.cf_fieldGroup}>
                    <label className={styles.cf_label} htmlFor="cf-read-timeout">
                        {t('label.readTimeout')}
                        {/* aria-hidden — description provided via aria-describedby on the input */}
                        <span aria-hidden="true" className={styles.cf_tooltip}>ⓘ</span>
                    </label>
                    <input
                    type="number"
                    id="cf-read-timeout"
                    className={styles.cf_input}
                    min="1000"
                    aria-describedby="cf-read-timeout-hint"
                    value={formState.readTimeout}
                    onChange={e => onFieldChange('readTimeout', e.target.value)}
                    onBlur={() => onFieldBlur('readTimeout')}
                />
                    <span id="cf-read-timeout-hint" className={styles.cf_fieldHint}>
                        {t('label.readTimeoutTooltip')}
                    </span>
                </div>
            </fieldset>

            <div className={styles.cf_actions}>
                {saveStatus === 'success' && (
                <div aria-hidden="true" className={`${styles.cf_alert} ${styles['cf_alert--success']}`}>
                    <span aria-hidden="true" className={styles.cf_alertIcon}>✓</span> {t('label.saveSuccess')}
                </div>
            )}
                {saveStatus === 'error' && (
                <div aria-hidden="true" className={`${styles.cf_alert} ${styles['cf_alert--error']}`}>
                    <span aria-hidden="true" className={styles.cf_alertIcon}>✕</span> {t('label.saveError')}
                </div>
            )}
                <Button
                type="submit"
                label={t('label.save')}
                variant="primary"
                isDisabled={isSaving}
            />
            </div>
        </form>
    );
};

ConnectionSettingsForm.propTypes = {
    formState: PropTypes.shape({
        host: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
        port: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
        connectionTimeout: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
        readTimeout: PropTypes.oneOfType([PropTypes.string, PropTypes.number])
    }).isRequired,
    onFieldChange: PropTypes.func.isRequired,
    onFieldBlur: PropTypes.func.isRequired,
    onSubmit: PropTypes.func.isRequired,
    isSaving: PropTypes.bool,
    saveStatus: PropTypes.oneOf(['success', 'error'])
};

export default ConnectionSettingsForm;
