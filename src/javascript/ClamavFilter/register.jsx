import {registry} from '@jahia/ui-extender';
import {ClamavFilterAdmin} from './ClamavFilter';
import React from 'react';

export default () => {
    console.debug('%c clamav-filter: activation in progress', 'color: #006633');
    registry.add('adminRoute', 'clamavFilter', {
        targets: ['administration-server-systemHealth:10'],
        requiredPermission: 'admin',
        label: 'clamav-filter:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(ClamavFilterAdmin)
    });
};
