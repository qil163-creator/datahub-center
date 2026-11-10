import { addCollection } from '@vben/icons';

import { icons as antDesignIcons } from '@iconify-json/ant-design';
import { icons as carbonIcons } from '@iconify-json/carbon';
import { icons as epIcons } from '@iconify-json/ep';
import { icons as iconParkIcons } from '@iconify-json/icon-park-outline';
import { icons as lucideIcons } from '@iconify-json/lucide';

// MDI 子集来自 @iconify/json，版权归 Pictogrammers，遵循 Apache-2.0。
import mdiIcons from './local-mdi-icons.json';

// 项目常用图标随应用提供，其他图标仍使用原有加载方式。
for (const collection of [lucideIcons, antDesignIcons, carbonIcons, epIcons, iconParkIcons, mdiIcons]) {
  addCollection(collection);
}
