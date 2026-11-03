import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { describe, expect, it } from 'vitest';

import {
  connectionTestNeedsPassword,
  databaseEditValues,
} from './credential-policy';

const database: FoundationDatabaseApi.Database & { secretKey: string } = {
  id: '9007199254740993',
  userName: 'review_user',
  password: 'test-only-secret',
  secretKey: 'test-only-key',
  databaseType: 2,
  databaseIp: 'database.example',
  databasePort: '5432',
  databaseName: 'review',
  databaseSchema: 'public',
  jdbcUrl: 'jdbc:postgresql://database.example:5432/review',
};

describe('数据源编辑凭据边界', () => {
  it.each([
    'NEO4J',
    'MINIO',
    'OSS',
    'ODPS',
    'SFTP',
    'INFLUXDB2',
    'REDIS',
    'RABBITMQ',
    'MILVUS',
    'KAFKA',
    'ROCKETMQ',
  ])('%s 仅改超时或 JSON 排序不要求重输同一目标的凭据', (typeCode) => {
    const original = {
      ...database,
      extendConfig: '{"schemaVersion":1,"endpoint":"bolt://graph.test:7687"}',
    };
    const current = {
      ...databaseEditValues(original),
      extendConfig:
        '{"timeoutSeconds":25,"endpoint":"bolt://graph.test:7687","schemaVersion":1}',
    };
    expect(connectionTestNeedsPassword(original, current, typeCode)).toBe(
      false,
    );
    expect(
      connectionTestNeedsPassword(
        original,
        {
          ...current,
          extendConfig:
            '{"schemaVersion":1,"endpoint":"bolt://other.test:7687","timeoutSeconds":25}',
        },
        typeCode,
      ),
    ).toBe(true);
  });
  it.each([
    ['MINIO', { pathStyle: true }],
    ['OSS', { pathStyle: false }],
    ['SFTP', { basePath: '/' }],
    ['REDIS', { tls: false }],
    ['RABBITMQ', { tls: false, virtualHost: '/' }],
    [
      'KAFKA',
      { securityProtocol: 'PLAINTEXT', saslMechanism: 'PLAIN', tls: false },
    ],
    ['MILVUS', { authenticationMode: 'PASSWORD' }],
  ])('编辑 %s 补写等价默认值不触发凭据重输', (typeCode, defaults) => {
    const original = { ...database, extendConfig: '{"schemaVersion":1}' };
    expect(
      connectionTestNeedsPassword(
        original,
        {
          ...databaseEditValues(original),
          extendConfig: JSON.stringify({
            schemaVersion: 1,
            ...defaults,
            timeoutSeconds: 10,
          }),
        },
        String(typeCode),
      ),
    ).toBe(false);
  });
  it('消息服务地址分隔格式与键顺序等价，TLS 或认证变化仍要求凭据', () => {
    const original = {
      ...database,
      extendConfig: '{"schemaVersion":1,"servers":"a:9092; b:9092"}',
    };
    const current = {
      ...databaseEditValues(original),
      extendConfig:
        '{"servers":["a:9092","b:9092"],"schemaVersion":1,"securityProtocol":"PLAINTEXT","saslMechanism":"PLAIN"}',
    };
    expect(connectionTestNeedsPassword(original, current, 'KAFKA')).toBe(false);
    expect(
      connectionTestNeedsPassword(
        original,
        {
          ...current,
          extendConfig:
            '{"schemaVersion":1,"servers":["a:9092","b:9092"],"securityProtocol":"SASL_SSL"}',
        },
        'KAFKA',
      ),
    ).toBe(true);
  });
  it('显式切换到匿名模式允许清除旧凭据，其他模式仍受目标变更保护', () => {
    const current = {
      ...databaseEditValues(database),
      databaseIp: 'other.test',
      extendConfig: '{"schemaVersion":1,"authenticationMode":"NONE"}',
    };
    expect(
      connectionTestNeedsPassword(
        { ...database, hasPassword: true },
        current,
        'MILVUS',
      ),
    ).toBe(false);
    expect(
      connectionTestNeedsPassword(
        { ...database, hasPassword: true },
        current,
        'ROCKETMQ',
      ),
    ).toBe(false);
    expect(
      connectionTestNeedsPassword(
        { ...database, hasPassword: true },
        current,
        'REDIS',
      ),
    ).toBe(true);
    expect(
      connectionTestNeedsPassword(
        { ...database, hasPassword: false },
        current,
        'REDIS',
      ),
    ).toBe(false);
  });
  it('编辑值不回显原密码或密钥，也不改变详情对象', () => {
    const values = databaseEditValues(database);
    expect(values.password).toBe('');
    expect(values).not.toHaveProperty('secretKey');
    expect(JSON.stringify(values)).not.toContain('test-only-secret');
    expect(database.password).toBe('test-only-secret');
    expect(values.id).toBe('9007199254740993');
  });

  it('原连接空密码可以测连，空值与空串连接属性保持等价', () => {
    const values = databaseEditValues(database);
    expect(connectionTestNeedsPassword(database, values)).toBe(false);
    expect(
      connectionTestNeedsPassword(database, {
        ...values,
        databaseSchema: 'public',
        extendConfig: '',
      }),
    ).toBe(false);
  });

  it.each([
    ['databaseIp', 'receiver.example'],
    ['databasePort', '5433'],
    ['databaseName', 'other'],
    ['databaseSchema', 'other'],
    ['jdbcUrl', 'jdbc:postgresql://receiver.example:5432/review'],
    ['userName', 'other_user'],
    ['databaseType', 1],
    ['extendConfig', 'sslmode=disable'],
  ] as const)('改变 %s 时空密码测连需要新密码', (field, value) => {
    const values = { ...databaseEditValues(database), [field]: value };
    expect(connectionTestNeedsPassword(database, values)).toBe(true);
    expect(
      connectionTestNeedsPassword(database, {
        ...values,
        password: 'new-test-password',
      }),
    ).toBe(false);
  });

  it('新建空密码连接不借用已有凭据', () => {
    expect(
      connectionTestNeedsPassword(undefined, databaseEditValues(database)),
    ).toBe(false);
  });
});
