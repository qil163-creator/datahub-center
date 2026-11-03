import { addIcon } from '@vben/icons';

import { QD_ICON_DEFINITIONS } from '../../logictable/icons';

/** 复用已核对的 qData 字形，使通用 TableAction 保持原权限与操作行为。 */
for (const [name, icon] of Object.entries(QD_ICON_DEFINITIONS)) {
  const dimensions = icon.viewBox.split(' ').map(Number);
  addIcon(`qdata:${name.toLowerCase()}`, {
    width: dimensions[2],
    height: dimensions[3],
    body: icon.paths
      .map((path) => `<path d="${path}" fill="currentColor"/>`)
      .join(''),
  });
}

export const METRIC_ACTION_ICON = {
  ADD: 'qdata:plus',
  DELETE: 'qdata:delete',
  EDIT: 'qdata:edit',
  VIEW: 'qdata:view',
};
