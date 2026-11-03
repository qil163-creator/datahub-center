import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { describe, expect, it } from 'vitest';

import {
  isConnectionEndpoint,
  nativeConnectionCode,
  readNativeConnectionFields,
  writeNativeConnectionFields,
} from './native-connection';

describe('原生连接的公开配置与凭据边界', () => {
  it('测连超时按数字独立保存，编辑恢复，不混入密码或 JDBC 配置', () => {
    const result = writeNativeConnectionFields(
      {
        id: '42',
        userName: 'fixture-user',
        connectionEndpoint: 'bolt://graph.test:7687',
        connectionTimeoutSeconds: 25,
      },
      'NEO4J',
    );
    expect(JSON.parse(result.extendConfig!)).toEqual({
      schemaVersion: 1,
      endpoint: 'bolt://graph.test:7687',
      timeoutSeconds: 25,
    });
    expect(result).not.toHaveProperty('connectionTimeoutSeconds');
    expect(
      readNativeConnectionFields(result.extendConfig).connectionTimeoutSeconds,
    ).toBe(25);
    expect(
      readNativeConnectionFields('{"timeoutSeconds":"25"}')
        .connectionTimeoutSeconds,
    ).toBeUndefined();
  });
  it.each([
    'bolt://graph.test/app',
    'neo4j+s://graph.test:0',
    'bolt://graph.test:65536',
    'bolt://graph.test:',
    'bolt://graph.test/..',
    'bolt://graph.test/%2e%2e',
    'bolt://graph.test:bad',
    'bolt://@graph.test:7687',
    'bolt://graph^.test:7687',
    'bolt://a.test,b.test:7687',
    String.raw`bolt://graph.test\path`,
    'https://graph.test:7474',
  ])('图数据库拒绝错误端口、HTTP 及把数据库名拼入地址 %s', (endpoint) => {
    expect(isConnectionEndpoint(endpoint, 'NEO4J')).toBe(false);
  });
  it.each([
    'bolt://graph.test',
    'bolt+s://graph.test:65535/',
    'neo4j://[::1]:7687',
    'neo4j+s://graph.test:7687',
  ])('图数据库支持明确的 Bolt / 路由及可信 TLS 地址 %s', (endpoint) => {
    expect(isConnectionEndpoint(endpoint, 'NEO4J')).toBe(true);
  });
  it('切换到显式匿名模式清除账号和凭据，令牌模式只提交密码字段', () => {
    const values = { id: '1', userName: 'old-user', password: 'old-password' };
    expect(
      writeNativeConnectionFields(
        { ...values, connectionAuthenticationMode: 'NONE' },
        'MILVUS',
      ),
    ).toMatchObject({ userName: '', password: '' });
    expect(
      writeNativeConnectionFields(
        { ...values, connectionAuthenticationMode: 'NONE' },
        'ROCKETMQ',
      ),
    ).toMatchObject({ userName: '', password: '' });
    expect(
      writeNativeConnectionFields(
        { ...values, connectionAuthenticationMode: 'TOKEN' },
        'MILVUS',
      ),
    ).toMatchObject({ userName: '', password: 'old-password' });
  });
  it('仅识别原生类型，不把 ClickHouse HTTP JDBC 类型误判为原生连接', () => {
    expect(
      nativeConnectionCode({
        typeCode: 'ClickHouse',
        protocolType: 'HTTP',
      } as FoundationDatabaseTypeApi.DatabaseType),
    ).toBeUndefined();
    expect(
      nativeConnectionCode({
        typeCode: 'influxdb2',
      } as FoundationDatabaseTypeApi.DatabaseType),
    ).toBe('INFLUXDB2');
  });

  it('对象存储 S3 仅提交公开字段，清除旧 JDBC 目标并保持密钥独立', () => {
    const connection = writeNativeConnectionFields(
      {
        id: '9223372036854775806',
        userName: 'fixture-access',
        password: 'fixture-secret',
        databaseName: 'fixture-bucket',
        databaseIp: 'old',
        databasePort: '3306',
        jdbcUrl: 'jdbc:old',
        databaseSchema: 'old',
        connectionEndpoint: 'https://storage.test:9000',
        connectionPathStyle: true,
        connectionRegion: 'region-test',
        connectionTopic: 'stale-topic',
      },
      'MINIO',
    );
    expect(connection).toMatchObject({
      id: '9223372036854775806',
      databaseIp: '',
      databasePort: '',
      jdbcUrl: '',
      databaseSchema: '',
      password: 'fixture-secret',
    });
    expect(connection).not.toHaveProperty('connectionEndpoint');
    const config = JSON.parse(connection.extendConfig!);
    expect(config).toEqual({
      schemaVersion: 1,
      endpoint: 'https://storage.test:9000',
      region: 'region-test',
      pathStyle: true,
    });
    expect(connection.extendConfig).not.toContain('fixture-secret');
  });

  it('消息连接 Kafka 地址去重并使用数组协议，认证密码不会放入 JSON', () => {
    const connection = writeNativeConnectionFields(
      {
        id: '1',
        userName: 'fixture-user',
        password: 'fixture-password',
        databaseName: 'stale',
        connectionServers: 'node-a:9092,node-b:9092\nnode-a:9092',
        connectionSecurityProtocol: 'SASL_SSL',
        connectionSaslMechanism: 'SCRAM-SHA-256',
      },
      'KAFKA',
    );
    expect(JSON.parse(connection.extendConfig!)).toEqual({
      schemaVersion: 1,
      servers: ['node-a:9092', 'node-b:9092'],
      securityProtocol: 'SASL_SSL',
      saslMechanism: 'SCRAM-SHA-256',
    });
    expect(connection.databaseName).toBe('');
    expect(connection.extendConfig).not.toContain('fixture-password');
  });

  it('编辑公开配置不变时保留原始字节，秘密及未知配置不参与回显和提交', () => {
    const original =
      '{ "endpoint": "https://influx.test:8086", "schemaVersion": 1 }';
    const fields = readNativeConnectionFields(original);
    const connection = writeNativeConnectionFields(
      { id: '1', userName: 'org-test', password: '', ...fields },
      'INFLUXDB2',
      original,
    );
    expect(connection.extendConfig).toBe(original);
    const untrusted = readNativeConnectionFields(
      '{"endpoint":"https://host.test","password":"secret","token":"secret"}',
    );
    expect(untrusted).toEqual({ connectionEndpoint: 'https://host.test' });
    expect(readNativeConnectionFields('bad-json')).toEqual({});
    expect(
      readNativeConnectionFields(
        '{"endpoint":true,"pathStyle":"false","tls":"true","servers":[{}]}',
      ),
    ).toEqual({});
  });

  it.each([
    'http://name:password@host',
    'https://host?token=secret',
    'https://host#x',
    'host:9000',
    'https://host/?',
    'https://host/\n',
  ])('服务地址拒绝凭据、查询和无协议输入 %s', (value) => {
    expect(isConnectionEndpoint(value, 'MINIO')).toBe(false);
  });

  it('图数据库 Neo4j 保留加密 Bolt 协议，其他服务使用 HTTP(S)', () => {
    expect(isConnectionEndpoint('neo4j+ssc://graph.test:7687', 'NEO4J')).toBe(
      false,
    );
    expect(isConnectionEndpoint('neo4j+s://graph.test:7687', 'NEO4J')).toBe(
      true,
    );
    expect(isConnectionEndpoint('bolt://graph.test:7687', 'MINIO')).toBe(false);
    expect(isConnectionEndpoint('https://maxcompute.test/api', 'ODPS')).toBe(
      true,
    );
  });
});
