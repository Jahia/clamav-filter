import {registry} from '@jahia/ui-extender';
import register from './ClamavFilter/register';
import i18next from 'i18next';

export default function registerClamavFilter() {
    registry.add('callback', 'clamav-filter', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('clamav-filter', () => {});
            register();
        }
    });
}
