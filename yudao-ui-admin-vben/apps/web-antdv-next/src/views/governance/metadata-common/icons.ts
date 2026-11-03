import { addIcon } from '@vben/icons';

import assets from './reference-assets/icon-data.json';

const aliases: Record<string, keyof typeof assets> = {
  'cancel-finalize': 'finalize',
  compare: 'detail',
  down: 'more',
  eye: 'detail',
  history: 'instances',
};

for (const [name, asset] of Object.entries(assets)) {
  addIcon(`qdata-meta:${name}`, {
    body: `<image width="${asset.width}" height="${asset.height}" href="data:image/png;base64,${asset.data}" />`,
    width: asset.width,
    height: asset.height,
  });
}

/** 只注册用户参考截图中的裁切图标，保留现有操作组件的权限与确认逻辑。 */
export function metadataIcon(name: string) {
  return `qdata-meta:${aliases[name] ?? name}`;
}

export function metadataConnectionIcon(product?: string) {
  const value = product?.toLowerCase() ?? '';
  if (value.includes('doris')) return 'doris';
  if (value.includes('hdfs')) return 'hdfs';
  if (value.includes('mysql')) return 'mysql';
  return 'database';
}

const connectionImages = {
  database: new URL('./reference-assets/icons/database.png', import.meta.url)
    .href,
  doris: new URL('./reference-assets/icons/doris.png', import.meta.url).href,
  hdfs: new URL('./reference-assets/icons/hdfs.png', import.meta.url).href,
  mysql: new URL('./reference-assets/icons/mysql.png', import.meta.url).href,
};

export function metadataConnectionIconUrl(product?: string) {
  return connectionImages[metadataConnectionIcon(product)];
}
