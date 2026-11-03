/* eslint-disable vue/one-component-per-file -- 新增、编辑与缺失类型挂载共享同一依赖桩 */
import type { App, Component, SetupContext } from 'vue';

import type { VbenFormSchema } from '#/adapter/form';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { createApp, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CreateConnectionDetails from './create-connection-details.vue';

const state = vi.hoisted(() => ({
  options: undefined as
    | undefined
    | { compact?: boolean; schema: VbenFormSchema[] },
  values: {} as Record<string, unknown>,
  valid: true,
  changeType: vi.fn(),
}));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/system/dept', () => ({ getDeptList: vi.fn() }));
vi.mock('#/adapter/form', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  return {
    z: actual.z,
    useVbenForm: (options: NonNullable<typeof state.options>) => {
      state.options = options;
      return [
        (_: unknown, { attrs }: SetupContext) => {
          const type = options.schema.find(
            (field) => field.fieldName === 'databaseType',
          )!;
          const props = type.componentProps as () => Record<string, unknown>;
          return h('div', attrs, h(type.component as Component, props()));
        },
        {
          setValues: async (values: Record<string, unknown>) => {
            state.values = { ...values };
          },
          getValues: async () => ({ ...state.values }),
          validate: async () => ({ valid: state.valid }),
        },
      ];
    },
  };
});

/* oxlint-disable no-template-curly-in-string -- 使用实际 JDBC 字面模板验证切换连接产品时的默认值。 */

const postgres = {
  id: 6,
  typeCode: 'POSTGRESQL',
  productId: 'POSTGRESQL',
  typeName: 'PostgreSQL',
  description: '不应重复展示为大横幅的类型描述',
  iconPath: '/connection-icons/database.png',
  portTemplates: '5432',
  urlTemplates: 'jdbc:postgresql://${host}:${port}/${dbName}',
} as FoundationDatabaseTypeApi.DatabaseType;
let app: App | undefined;
let host: HTMLDivElement;
let details: {
  getValues: () => Promise<FoundationDatabaseApi.Database>;
  readValues: () => Promise<FoundationDatabaseApi.Database | undefined>;
};
async function mountDetails(
  selectedType = postgres,
  draft?: Partial<FoundationDatabaseApi.Database>,
  mode: 'create' | 'edit' = 'create',
) {
  host = document.createElement('div');
  app = createApp(CreateConnectionDetails, {
    selectedType,
    draft,
    mode,
    onChangeType: state.changeType,
  });
  details = app.mount(host) as unknown as typeof details;
  await nextTick();
  await nextTick();
}
function field(name: string) {
  return state.options!.schema.find((item) => item.fieldName === name)!;
}
beforeEach(() => {
  state.values = {};
  state.valid = true;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('新增连接第二步', () => {
  it.each([
    ['NEO4J', 'neo4j'],
    ['MILVUS', 'default'],
  ])(
    '原生数据库 %s 提供默认目标且数据库字段必填',
    async (typeCode, databaseName) => {
      await mountDetails({
        ...postgres,
        id: 42,
        typeCode,
        protocolType: typeCode === 'NEO4J' ? 'BOLT' : 'MILVUS',
      });
      expect(state.values.databaseName).toBe(databaseName);
      expect(field('databaseName').rules).toBe('required');
      const values = await details.readValues();
      expect(values?.databaseName).toBe(databaseName);
    },
  );
  it('文件连接展示服务配置并提交扩展契约，不提交表单专用字段', async () => {
    const hdfs = {
      ...postgres,
      id: 30,
      typeCode: 'HDFS',
      productId: undefined,
      typeName: 'HDFS',
      portTemplates: '8020',
      urlTemplates: '',
    };
    await mountDetails(hdfs);
    expect(state.options?.schema.map((item) => item.fieldName)).toContain(
      'fileWebHdfsUrl',
    );
    expect(state.options?.schema.map((item) => item.fieldName)).not.toContain(
      'jdbcUrl',
    );
    expect(state.options?.schema.map((item) => item.fieldName)).not.toContain(
      'password',
    );
    state.values.fileRootPath = '/data';
    state.values.fileWebHdfsUrl = 'http://namenode.test:9870';
    state.values.fileDataNodes = 'worker.test:9864';
    const values = await details.readValues();
    expect(values).not.toHaveProperty('fileWebHdfsUrl');
    expect(JSON.parse(values!.extendConfig!)).toEqual({
      basePath: '/data',
      webHdfsUrl: 'http://namenode.test:9870',
      allowedDataNodeAuthorities: ['worker.test:9864'],
    });
  });
  it('类型与名称放在首行，端口有标签，主要字段成对排列且保留末尾完整 URL 配置', async () => {
    await mountDetails();

    expect(state.options?.schema.map((item) => item.fieldName)).toEqual([
      'id',
      'databaseType',
      'name',
      'databaseIp',
      'databasePort',
      'userName',
      'password',
      'databaseName',
      'databaseSchema',
      'usageType',
      'orgId',
      'remark',
      'jdbcUrl',
      'extendConfig',
    ]);
    for (const name of [
      'databaseType',
      'name',
      'databaseIp',
      'databasePort',
      'userName',
      'password',
      'databaseName',
      'databaseSchema',
      'usageType',
      'orgId',
    ]) {
      expect(field(name).formItemClass).toContain('col-span-12 sm:col-span-6');
    }
    expect(field('usageType').formItemClass).toContain('sm:col-start-1');
    expect(field('databasePort')).toMatchObject({
      label: '端口',
      hideLabel: false,
    });
    expect(field('remark').formItemClass).toBe('col-span-12');
    expect(field('jdbcUrl').formItemClass).toBe('col-span-12');
    expect(field('extendConfig').formItemClass).toBe('col-span-12');
    expect(state.options?.compact).toBe(true);
    expect(host.querySelector('.type-field--readonly')?.textContent).toBe(
      'PostgreSQL',
    );
    expect(host.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/postgresql.svg',
    );
    expect(host.querySelector('button')).toBeNull();
    expect(host.textContent).not.toContain(postgres.description);
  });

  it('切换到 MySQL 时使用产品图标且不沿用旧类型的自定义图标', async () => {
    await mountDetails(
      {
        ...postgres,
        id: 1,
        typeCode: 'MYSQL',
        productId: 'MYSQL',
        typeName: 'MySQL数据库',
        iconPath: '',
      },
      {
        databaseType: postgres.id,
        typeCode: 'POSTGRESQL',
        iconPath: 'https://files.test/old-custom.svg',
      },
    );
    expect(host.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/mysql.svg',
    );
  });

  it('类型目录不可用时按原连接编码与图标回显', async () => {
    host = document.createElement('div');
    app = createApp(CreateConnectionDetails, {
      mode: 'edit',
      draft: {
        databaseType: 6,
        typeCode: 'POSTGRESQL',
        iconPath: '/connection-icons/database.png',
      },
    });
    app.mount(host);
    await nextTick();
    expect(host.querySelector('img')?.getAttribute('src')).toBe(
      '/connection-icons/postgresql.svg',
    );
  });

  it('保留所选类型的自定义图标', async () => {
    await mountDetails({
      ...postgres,
      iconPath: 'https://files.test/custom.svg',
    });
    expect(host.querySelector('img')?.getAttribute('src')).toBe(
      'https://files.test/custom.svg',
    );
  });

  it('编辑保留原id、自定义URL和空密码提示，同时保留紧凑类型更换按钮', async () => {
    const draft = {
      id: '9223372036854775806',
      databaseType: postgres.id,
      databaseIp: 'db.test',
      databasePort: '15432',
      databaseName: 'warehouse',
      jdbcUrl: 'jdbc:postgresql://db.test:15432/warehouse?sslmode=require',
      password: '',
      extendConfig: 'applicationName=fixture',
      usageType: 2,
      orgId: '9223372036854775805',
      remark: '业务备注',
    };
    await mountDetails(postgres, draft, 'edit');
    expect(await details.getValues()).toMatchObject(draft);
    expect(field('password').componentProps).toMatchObject({
      placeholder: '编辑时留空保留原密码',
    });
    expect(host.querySelector('.type-field--badge')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('button')!.click();
    expect(state.changeType).toHaveBeenCalled();
  });
  it('编辑缺失类型定义时保留原类型编号和URL并允许更换', async () => {
    const draft = {
      id: '9223372036854775806',
      databaseType: 99,
      jdbcUrl: 'jdbc:custom://db.test/warehouse',
      userName: 'reader',
    };
    host = document.createElement('div');
    app = createApp(CreateConnectionDetails, {
      draft,
      mode: 'edit',
      onChangeType: state.changeType,
    });
    details = app.mount(host) as unknown as typeof details;
    await nextTick();
    await nextTick();
    expect(await details.getValues()).toMatchObject(draft);
    expect(host.textContent).toContain('连接类型 99');
    expect(host.querySelector('button')).not.toBeNull();
  });
  it('换产品清除旧目标与凭据，使用新默认端口，保留业务字段并排除已有连接标识', async () => {
    await mountDetails(postgres, {
      id: '9223372036854775806',
      databaseType: 1,
      name: '保留的名称',
      databaseIp: 'db.test',
      databasePort: '3306',
      databaseName: 'warehouse',
      jdbcUrl: 'jdbc:mysql://db.test:3306/warehouse',
      usageType: 2,
      orgId: '9223372036854775805',
      ...{ secretKey: 'fixture-key' },
    });

    state.valid = false;
    expect(await details.readValues()).toBeUndefined();
    state.valid = true;
    expect(state.values.databaseIp).toBeUndefined();
    expect(state.values.databaseName).toBeUndefined();
    expect(state.values.password).toBe('');
    state.values.databaseIp = 'new-db.test';
    state.values.databaseName = 'new-warehouse';
    const values = await details.readValues();
    expect(values).toMatchObject({
      databaseType: postgres.id,
      databasePort: '5432',
      jdbcUrl: 'jdbc:postgresql://new-db.test:5432/new-warehouse',
      name: '保留的名称',
      usageType: 2,
      orgId: '9223372036854775805',
    });
    expect(values).not.toHaveProperty('id');
    expect(values).not.toHaveProperty('secretKey');
  });

  it('文件连接 SFTP 复用根目录表单并提交受验证的主机指纹，不提交 JDBC 配置', async () => {
    await mountDetails({
      ...postgres,
      id: 40,
      typeCode: 'SFTP',
      protocolType: 'SFTP',
      portTemplates: '22',
    });
    expect(field('fileHostKeyFingerprint')).toBeDefined();
    expect(state.options?.schema.map((item) => item.fieldName)).not.toContain(
      'jdbcUrl',
    );
    Object.assign(state.values, {
      fileRootPath: '/incoming',
      fileHostKeyFingerprint: `SHA256:${'a'.repeat(43)}`,
      userName: 'fixture-user',
      password: 'fixture-password',
    });
    const values = await details.readValues();
    expect(JSON.parse(values!.extendConfig!)).toEqual({
      schemaVersion: 1,
      basePath: '/incoming',
      timeoutSeconds: 10,
      hostKeyFingerprint: `SHA256:${'a'.repeat(43)}`,
    });
    expect(values?.password).toBe('fixture-password');
    expect(values).not.toHaveProperty('fileHostKeyFingerprint');
  });

  it('时序连接 InfluxDB 2.x 使用 org/token/bucket，编辑只回显公开配置并保留空凭据', async () => {
    const type = {
      ...postgres,
      id: 41,
      typeCode: 'INFLUXDB2',
      protocolType: 'HTTP',
    };
    await mountDetails(
      type,
      {
        id: '9223372036854775806',
        databaseType: type.id,
        userName: 'org-test',
        databaseName: 'bucket-test',
        password: '',
        extendConfig:
          '{"endpoint":"https://influx.test:8086","schemaVersion":1}',
      },
      'edit',
    );
    expect(field('userName').label).toBe('Organization');
    expect(field('password').label).toBe('Token');
    expect(field('databaseName').label).toBe('Bucket');
    expect(state.options?.schema.map((item) => item.fieldName)).not.toContain(
      'databaseIp',
    );
    const values = await details.readValues();
    expect(values).toMatchObject({
      id: '9223372036854775806',
      userName: 'org-test',
      databaseName: 'bucket-test',
      password: '',
    });
    expect(values?.extendConfig).toBe(
      '{"endpoint":"https://influx.test:8086","schemaVersion":1}',
    );
    expect(values).not.toHaveProperty('connectionEndpoint');
  });

  it('同类型返回保留自定义端口，SQLite 文件连接仍跳过主机与端口校验', async () => {
    const sqlite = {
      ...postgres,
      id: 21,
      typeName: 'SQLite',
      typeCode: 'SQLITE3',
      productId: 'SQLITE3',
      portTemplates: '',
      urlTemplates: 'jdbc:sqlite:${dbName}',
    };
    await mountDetails(sqlite, {
      databaseType: sqlite.id,
      databaseName: '/data/fixture.db',
    });
    for (const name of ['databaseIp', 'databasePort']) {
      const resolve = field(name).dependencies!.resolve!;
      expect(resolve({} as Parameters<typeof resolve>[0])).toEqual({
        show: false,
        rules: null,
      });
    }
    expect(await details.getValues()).toMatchObject({
      jdbcUrl: 'jdbc:sqlite:/data/fixture.db',
    });
    app?.unmount();
    app = undefined;
    await mountDetails(postgres, {
      databaseType: postgres.id,
      databasePort: '15432',
    });
    const values = await details.getValues();
    expect(values.databasePort).toBe('15432');
  });
});
