import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ConnectionIcon from './connection-icon.vue';
import {
  resolveConnectionIconCandidates,
  resolveConnectionIconPath,
} from './path';

let app: ReturnType<typeof createApp> | undefined;
let target: HTMLDivElement;
function mountIcon(props: {
  alt?: string;
  iconPath?: string;
  typeCode?: string;
}) {
  target = document.createElement('div');
  app = createApp({
    render: () => h(ConnectionIcon, { ...props, class: 'size-10' }),
  });
  app.mount(target);
}
afterEach(() => {
  app?.unmount();
  vi.unstubAllEnvs();
});

describe('连接图标的部署路径与离线兜底', () => {
  it('内置图标遵守部署前缀，自定义绝对地址与其他历史路径保持原值', () => {
    expect(
      resolveConnectionIconPath('/connection-icons/db2.png', '/console/'),
    ).toBe('/console/connection-icons/db2.png');
    expect(resolveConnectionIconPath('/connection-icons/db2.png', './')).toBe(
      './connection-icons/db2.png',
    );
    expect(
      resolveConnectionIconPath('https://files.test/custom.svg', '/console/'),
    ).toBe('https://files.test/custom.svg');
    expect(resolveConnectionIconPath('/legacy/custom.svg', '/console/')).toBe(
      '/legacy/custom.svg',
    );
  });

  it.each([
    ['MySQL', 'mysql.svg'],
    [' postgres ', 'postgresql.svg'],
    ['PostgreSQL', 'postgresql.svg'],
    ['Doris', 'doris.svg'],
    ['SQL Server', 'sqlserver.svg'],
    ['ORACLE_SERVICE', 'oracle.svg'],
    ['GBASE_8A', 'gbase8c.png'],
    ['SQLite', 'sqlite3.png'],
    ['DM', 'dm.svg'],
    ['StarRocks', 'starrocks.png'],
    ['Greenplum', 'greenplum.svg'],
    ['HighGo', 'highgo.svg'],
    ['KingbaseES', 'kingbase.ico'],
    ['Kingbase8', 'kingbase.ico'],
    ['OSCAR', 'oscar.png'],
    ['openGauss', 'opengauss.svg'],
    ['UXDB', 'uxdb.png'],
    ['OceanBase', 'oceanbase.svg'],
  ])('已知类型 %s 使用本地产品图标，不请求旧上传地址', (typeCode, file) => {
    const legacyIcon =
      'http://127.0.0.1:48080/admin-api/infra/file/29/get/20260806/mysql.svg';
    mountIcon({ iconPath: legacyIcon, typeCode });
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      `/connection-icons/${file}`,
    );
    expect(
      resolveConnectionIconCandidates(legacyIcon, typeCode, '/console/'),
    ).toEqual([
      `/console/connection-icons/${file}`,
      '/console/connection-icons/database.png',
    ]);
  });

  it.each([
    undefined,
    '',
    '/connection-icons/database.png',
    '/connection-icons/database.svg',
    '/connection-icons/mysql.svg',
  ])('配置图标为 %s 时仍按已知类型显示产品图标', (iconPath) => {
    mountIcon({ iconPath, typeCode: 'MYSQL' });
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/mysql.svg',
    );
  });

  it('已知产品允许自定义图标覆盖，失效后回退到本地产品图标', async () => {
    mountIcon({ iconPath: 'https://files.test/custom.svg', typeCode: 'MYSQL' });
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      'https://files.test/custom.svg',
    );
    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/mysql.svg',
    );
    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/database.png',
    );
  });

  it.each([
    'https://files.test/admin-api/infra/file/29/get/20260806/mysql.svg',
    'http://127.0.0.1:48080/admin-api/infra/file/30/get/20260806/mysql.svg',
    'http://localhost:48080/custom/mysql.svg',
    'http://localhost:48080/admin-api/infra/file/29/get/20260806/custom.svg',
    'http://localhost:48080/admin-api/infra/file/29/get/20260806/mysql.svg?version=custom',
    '/admin-api/infra/file/29/get/20260806/mysql.svg',
  ])('不会将自定义地址 %s 误识别为旧内置图标', (iconPath) => {
    expect(resolveConnectionIconCandidates(iconPath, 'MYSQL')[0]).toBe(
      iconPath,
    );
  });

  it.each(['MY-SQL', 'MY_SQL', 'MY SQL', 'MySQL-Custom', 'SQL_SERVER_CUSTOM'])(
    '新增类型 %s 不会因去除分隔符而误匹配已有产品',
    (typeCode) => {
      expect(resolveConnectionIconCandidates('/custom.svg', typeCode)).toEqual([
        '/custom.svg',
        '/connection-icons/database.png',
      ]);
    },
  );

  it('未知类型保留自定义地址，未配置时不根据展示名称猜产品', () => {
    expect(
      resolveConnectionIconCandidates(
        'https://files.test/custom.svg',
        'CUSTOM',
        '/console/',
      ),
    ).toEqual([
      'https://files.test/custom.svg',
      '/console/connection-icons/database.png',
    ]);
    mountIcon({ alt: 'MySQL', typeCode: 'CUSTOM' });
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/database.png',
    );
  });

  it('产品图标失效后依次使用通用 PNG 和 SVG，并保留调用方尺寸', async () => {
    mountIcon({ alt: 'MySQL', typeCode: 'MYSQL' });
    const productImage = target.querySelector('img')!;
    productImage.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/database.png',
    );
    expect(target.querySelector('img')?.classList.contains('size-10')).toBe(
      true,
    );

    productImage.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/database.png',
    );

    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')).toBeNull();
    expect(target.querySelector('svg')?.getAttribute('aria-label')).toBe(
      'MySQL',
    );
    expect(target.querySelector('svg')?.classList.contains('size-10')).toBe(
      true,
    );
  });

  it('自定义图片失效后显示本地通用图标，本地图标也失效时使用内联 SVG', async () => {
    vi.stubEnv('BASE_URL', '/console/');
    mountIcon({ alt: 'DB2', iconPath: 'https://files.test/missing.svg' });
    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/console/connection-icons/database.png',
    );
    expect(target.querySelector('img')?.classList.contains('size-10')).toBe(
      true,
    );
    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')).toBeNull();
    expect(target.querySelector('svg')?.getAttribute('aria-label')).toBe('DB2');
    expect(target.querySelector('svg')?.classList.contains('size-10')).toBe(
      true,
    );
  });

  it('未知连接直接使用本地兜底，路径变化后重新加载并忽略旧图片错误', async () => {
    const props = reactive({ iconPath: '' });
    mountIcon(props);
    const old = target.querySelector('img')!;
    expect(old.getAttribute('src')).toBe('/connection-icons/database.png');
    old.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('svg')).not.toBeNull();

    props.iconPath = '/connection-icons/mongodb.png';
    await nextTick();
    old.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/mongodb.png',
    );
  });

  it('类型变化后恢复产品图标，忽略旧请求和旧兜底图片的错误', async () => {
    const props = reactive({ iconPath: '', typeCode: 'MYSQL' });
    mountIcon(props);
    const oldProduct = target.querySelector('img')!;
    oldProduct.dispatchEvent(new Event('error'));
    await nextTick();
    const oldFallback = target.querySelector('img')!;

    props.typeCode = 'POSTGRESQL';
    await nextTick();
    oldFallback.dispatchEvent(new Event('error'));
    oldProduct.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/postgresql.svg',
    );

    props.typeCode = 'MYSQL';
    await nextTick();
    expect(target.querySelector('img')).not.toBe(oldProduct);
    oldProduct.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/mysql.svg',
    );
  });

  it('同一资源地址下类型或配置变化，也不会接受旧元素迟到的错误', async () => {
    const props = reactive({ iconPath: '/custom.svg', typeCode: 'CUSTOM_A' });
    mountIcon(props);
    const oldImage = target.querySelector('img')!;
    props.typeCode = 'CUSTOM_B';
    await nextTick();
    expect(target.querySelector('img')).not.toBe(oldImage);
    oldImage.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/custom.svg',
    );

    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    const oldFallback = target.querySelector('img')!;
    props.iconPath = '/replacement.svg';
    await nextTick();
    oldFallback.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/replacement.svg',
    );
  });
});
