import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { describe, expect, it, vi } from 'vitest';

import { resolveJdbcUrl, useFormSchema } from './data';

vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('#/api/system/dept', () => ({ getDeptList: vi.fn() }));
vi.mock('./modules/type-field.vue', () => ({ default: {} }));

/* oxlint-disable no-template-curly-in-string -- JDBC URL 模板使用字面占位符。 */

describe('连接参数表单', () => {
  it.each(['NEO4J', 'SFTP', 'INFLUXDB2', 'KAFKA'])(
    '原生 %s 的超时范围为整数 1 到 30 秒',
    (typeCode) => {
      const schema = useFormSchema({
        getSelectedType: () =>
          ({ typeCode }) as FoundationDatabaseTypeApi.DatabaseType,
      });
      const timeout = schema.find(
        (field) => field.fieldName === 'connectionTimeoutSeconds',
      )!;
      const rules = timeout.rules as {
        safeParse: (input: unknown) => { success: boolean };
      };
      expect(timeout.defaultValue).toBe(10);
      for (const valid of [1, 10, 30])
        expect(rules.safeParse(valid).success).toBe(true);
      for (const invalid of [0, 31, 1.5, '10', undefined])
        expect(rules.safeParse(invalid).success).toBe(false);
    },
  );
  it('消息服务 RocketMQ 仅接受 8 个 NameServer，Kafka 允许 32 个', () => {
    for (const [typeCode, max] of [
      ['ROCKETMQ', 8],
      ['KAFKA', 32],
    ] as const) {
      const schema = useFormSchema({
        getSelectedType: () =>
          ({ typeCode }) as FoundationDatabaseTypeApi.DatabaseType,
      });
      const rules = schema.find(
        (field) => field.fieldName === 'connectionServers',
      )!.rules as { safeParse: (input: unknown) => { success: boolean } };
      expect(
        rules.safeParse(
          Array.from({ length: max }, (_, i) => `host-${i}:9092`).join(','),
        ).success,
      ).toBe(true);
      expect(
        rules.safeParse(
          Array.from({ length: max + 1 }, (_, i) => `host-${i}:9092`).join(','),
        ).success,
      ).toBe(false);
    }
  });
  it('原生协议不暴露 JDBC、Schema 和写入用途字段，认证条件与服务端一致', () => {
    const type = {
      typeCode: 'KAFKA',
      protocolType: 'KAFKA',
    } as FoundationDatabaseTypeApi.DatabaseType;
    const schema = useFormSchema({ getSelectedType: () => type });
    expect(schema.map((field) => field.fieldName)).not.toEqual(
      expect.arrayContaining(['jdbcUrl', 'databaseSchema', 'usageType']),
    );
    expect(schema.map((field) => field.fieldName)).toEqual(
      expect.arrayContaining([
        'connectionServers',
        'connectionSecurityProtocol',
        'connectionSaslMechanism',
      ]),
    );
    const password = schema.find((field) => field.fieldName === 'password')!;
    const resolve = password.dependencies!.resolve!;
    expect(
      resolve({
        values: { connectionSecurityProtocol: 'PLAINTEXT' },
      } as unknown as Parameters<typeof resolve>[0]),
    ).toEqual({ rules: null });
    expect(
      resolve({
        values: { connectionSecurityProtocol: 'SASL_SSL' },
      } as unknown as Parameters<typeof resolve>[0]),
    ).toEqual({ rules: 'required' });
  });
  it.each([
    [
      'jdbc:oracle:thin:@${host}:${port}:${sid}',
      'jdbc:oracle:thin:@db.test:1521:ORCL',
    ],
    [
      'jdbc:oracle:thin:@//${host}:${port}/${serviceName}',
      'jdbc:oracle:thin:@//db.test:1521/ORCL',
    ],
  ])('替换 Oracle URL 中的库名占位符 %s', (template, expected) => {
    expect(
      resolveJdbcUrl(template, {
        databaseIp: 'db.test',
        databasePort: '1521',
        databaseName: 'ORCL',
      }),
    ).toBe(expected);
  });

  it('文件型 SQLite 连接不校验主机和端口，切换为网络数据库后恢复必填', () => {
    let type = {
      productId: 'SQLITE3',
    } as FoundationDatabaseTypeApi.DatabaseType;
    const schema = useFormSchema({ getSelectedType: () => type });
    const host = schema.find((item) => item.fieldName === 'databaseIp')!;
    const port = schema.find((item) => item.fieldName === 'databasePort')!;
    const resolveHost = host.dependencies!.resolve!;
    const resolvePort = port.dependencies!.resolve!;
    const context = {} as Parameters<typeof resolveHost>[0];

    expect(resolveHost(context)).toEqual({ show: false, rules: null });
    expect(resolvePort(context)).toEqual({ show: false, rules: null });
    type = { productId: 'MYSQL' } as FoundationDatabaseTypeApi.DatabaseType;
    expect(resolveHost(context)).toEqual({ show: true, rules: 'required' });
    expect(resolvePort(context)).toEqual({ show: true, rules: 'required' });
  });
});

describe('editing jdbc URL dependencies', () => {
  const type = {
    productId: 'POSTGRESQL',
    urlTemplates: 'jdbc:postgresql://${host}:${port}/${dbName}',
  } as FoundationDatabaseTypeApi.DatabaseType;
  const initial = {
    databaseIp: 'db.test',
    databasePort: '5432',
    databaseName: 'app',
  };
  function fieldTrigger(values: Partial<FoundationDatabaseApi.Database>) {
    const schema = useFormSchema({
      getSelectedType: () => type,
      preserveJdbcUrl: true,
      getInitialValues: () => values,
    });
    return schema.find((field) => field.fieldName === 'jdbcUrl')!.dependencies!
      .trigger as (
      current: Partial<FoundationDatabaseApi.Database>,
      form: { setFieldValue: (field: string, value: string) => void },
    ) => void;
  }
  it('keeps a custom saved URL on initial load and after address edits', () => {
    const values = {
      ...initial,
      jdbcUrl: 'jdbc:postgresql://db.test:5432/app?sslmode=require',
    };
    const trigger = fieldTrigger(values);
    const setFieldValue = vi.fn();
    trigger(values, { setFieldValue });
    trigger({ ...values, databaseIp: 'other.test' }, { setFieldValue });
    expect(setFieldValue).not.toHaveBeenCalled();
  });
  it('updates automatic URLs across successive address changes and stops after manual URL edits', () => {
    const values = {
      ...initial,
      jdbcUrl: 'jdbc:postgresql://db.test:5432/app',
    };
    const trigger = fieldTrigger(values);
    const setFieldValue = vi.fn();
    trigger({ ...values, databaseIp: 'other.test' }, { setFieldValue });
    expect(setFieldValue).toHaveBeenLastCalledWith(
      'jdbcUrl',
      'jdbc:postgresql://other.test:5432/app',
    );
    trigger(
      {
        ...values,
        databaseIp: 'other.test',
        databasePort: '15432',
        jdbcUrl: 'jdbc:postgresql://other.test:5432/app',
      },
      { setFieldValue },
    );
    expect(setFieldValue).toHaveBeenLastCalledWith(
      'jdbcUrl',
      'jdbc:postgresql://other.test:15432/app',
    );
    setFieldValue.mockClear();
    trigger(
      {
        ...values,
        databaseIp: 'custom.test',
        jdbcUrl: 'jdbc:postgresql://manual.test:1234/app',
      },
      { setFieldValue },
    );
    expect(setFieldValue).not.toHaveBeenCalled();
  });
});
