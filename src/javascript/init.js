import {registry} from '@jahia/ui-extender';
import register from './ClamavFilter/register';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'clamav-filter', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('clamav-filter', () => {
                console.debug('%c clamav-filter: i18n namespace loaded', 'color: #006633');
            });
            register();
            console.debug('%c clamav-filter: activation completed', 'color: #006633');
        }
    });
}
