import {registry} from '@jahia/ui-extender';
import {ClamavFilterAdmin} from './ClamavFilter';
import React from 'react';

export default function registerAdminRoute() {
    registry.add('adminRoute', 'clamavFilter', {
        targets: ['administration-server-systemHealth:10'],
        requiredPermission: 'clamavAdmin',
        label: 'clamav-filter:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(ClamavFilterAdmin)
    });
}
