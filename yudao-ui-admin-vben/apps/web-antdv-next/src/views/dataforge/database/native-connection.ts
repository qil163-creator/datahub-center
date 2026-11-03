import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { isDataNodeAuthorities } from './file-connection';

export const NATIVE_CONNECTION_CODES = [
  'OSS',
  'MINIO',
  'REDIS',
  'KAFKA',
  'RABBITMQ',
  'ROCKETMQ',
  'ODPS',
  'NEO4J',
  'MILVUS',
  'INFLUXDB2',
] as const;
export type NativeConnectionCode = (typeof NATIVE_CONNECTION_CODES)[number];

export interface NativeConnectionFields {
  connectionEndpoint?: string;
  connectionRegion?: string;
  connectionPathStyle?: boolean;
  connectionSecurityProtocol?: string;
  connectionSaslMechanism?: string;
  connectionVirtualHost?: string;
  connectionServers?: string;
  connectionTopic?: string;
  connectionTls?: boolean;
  connectionAuthenticationMode?: string;
  connectionTimeoutSeconds?: number;
}

const CONFIG_FIELDS = {
  connectionEndpoint: 'endpoint',
  connectionRegion: 'region',
  connectionPathStyle: 'pathStyle',
  connectionSecurityProtocol: 'securityProtocol',
  connectionSaslMechanism: 'saslMechanism',
  connectionVirtualHost: 'virtualHost',
  connectionServers: 'servers',
  connectionTopic: 'topic',
  connectionTls: 'tls',
  connectionAuthenticationMode: 'authenticationMode',
  connectionTimeoutSeconds: 'timeoutSeconds',
} as const;

export function nativeConnectionCode(
  type?: FoundationDatabaseTypeApi.DatabaseType,
) {
  const code = type?.typeCode?.toUpperCase();
  return NATIVE_CONNECTION_CODES.find((item) => item === code);
}

export function readNativeConnectionFields(
  config?: string,
): NativeConnectionFields {
  try {
    const data: unknown = JSON.parse(config || '{}');
    if (!data || typeof data !== 'object' || Array.isArray(data)) return {};
    const record = data as Record<string, unknown>;
    const fields: Record<string, unknown> = {};
    for (const [field, key] of Object.entries(CONFIG_FIELDS)) {
      const value = record[key];
      if (key === 'timeoutSeconds') {
        if (typeof value === 'number' && Number.isInteger(value))
          fields[field] = value;
      } else if (key === 'pathStyle' || key === 'tls') {
        if (typeof value === 'boolean') fields[field] = value;
      } else if (key === 'servers' && Array.isArray(value)) {
        if (value.every((item) => typeof item === 'string'))
          fields[field] = value.join('\n');
      } else if (typeof value === 'string') fields[field] = value;
    }
    return fields;
  } catch {
    return {};
  }
}

export function serverAuthorities(value?: string) {
  return [
    ...new Set(
      (value ?? '')
        .split(/[\n,;，]/u)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ];
}

/** 服务地址不接受凭据、查询或片段，认证信息统一通过受保护的密码字段传递。 */
export function isConnectionEndpoint(
  value: string,
  code: NativeConnectionCode,
) {
  try {
    if (!value || /\s/u.test(value) || !/^[a-z][a-z\d+.-]*:\/\//iu.test(value))
      return false;
    const url = new URL(value);
    if (code === 'NEO4J') {
      const parts = /^[a-z][a-z\d+.-]*:\/\/([^/?#]+)(\/?)$/iu.exec(value);
      if (
        !parts?.[1] ||
        /[,，]/u.test(parts[1]) ||
        !isDataNodeAuthorities(parts[1])
      )
        return false;
    }
    const protocols =
      code === 'NEO4J'
        ? ['bolt:', 'bolt+s:', 'neo4j:', 'neo4j+s:']
        : ['http:', 'https:'];
    return (
      protocols.includes(url.protocol) &&
      !!url.hostname &&
      !url.username &&
      !url.password &&
      !/[?#]/u.test(value)
    );
  } catch {
    return false;
  }
}

export function writeNativeConnectionFields(
  values: FoundationDatabaseApi.Database & NativeConnectionFields,
  code: NativeConnectionCode,
  originalConfig?: string,
): FoundationDatabaseApi.Database {
  const connection = { ...values };
  delete connection.connectionEndpoint;
  delete connection.connectionRegion;
  delete connection.connectionPathStyle;
  delete connection.connectionSecurityProtocol;
  delete connection.connectionSaslMechanism;
  delete connection.connectionVirtualHost;
  delete connection.connectionServers;
  delete connection.connectionTopic;
  delete connection.connectionTls;
  delete connection.connectionAuthenticationMode;
  delete connection.connectionTimeoutSeconds;
  const config: Record<string, unknown> = { schemaVersion: 1 };
  const allowed: Record<
    NativeConnectionCode,
    readonly (keyof NativeConnectionFields)[]
  > = {
    OSS: ['connectionEndpoint', 'connectionRegion', 'connectionPathStyle'],
    MINIO: ['connectionEndpoint', 'connectionRegion', 'connectionPathStyle'],
    REDIS: ['connectionTls'],
    KAFKA: [
      'connectionServers',
      'connectionSecurityProtocol',
      'connectionSaslMechanism',
    ],
    RABBITMQ: ['connectionVirtualHost', 'connectionTls'],
    ROCKETMQ: [
      'connectionServers',
      'connectionTopic',
      'connectionAuthenticationMode',
    ],
    ODPS: ['connectionEndpoint'],
    NEO4J: ['connectionEndpoint'],
    MILVUS: ['connectionEndpoint', 'connectionAuthenticationMode'],
    INFLUXDB2: ['connectionEndpoint'],
  };
  for (const field of Object.keys(
    CONFIG_FIELDS,
  ) as (keyof NativeConnectionFields)[]) {
    const value = values[field];
    if (
      (field !== 'connectionTimeoutSeconds' &&
        !allowed[code].includes(field)) ||
      value === undefined
    )
      continue;
    let normalizedValue = value;
    if (typeof value === 'string') normalizedValue = value.trim();
    config[CONFIG_FIELDS[field]] =
      field === 'connectionServers'
        ? serverAuthorities(String(value))
        : normalizedValue;
  }
  const normalized = JSON.stringify(config);
  let unchanged = false;
  try {
    const original: unknown = JSON.parse(originalConfig || 'null');
    if (
      original &&
      typeof original === 'object' &&
      !Array.isArray(original) &&
      !Object.hasOwn(original, 'timeoutSeconds') &&
      config.timeoutSeconds === 10
    )
      Object.assign(original, { timeoutSeconds: 10 });
    unchanged =
      !!original &&
      typeof original === 'object' &&
      !Array.isArray(original) &&
      JSON.stringify(original, Object.keys(original).toSorted()) ===
        JSON.stringify(config, Object.keys(config).toSorted());
  } catch {
    /* 损坏的历史配置由本次明确填写的字段替换。 */
  }
  if (!['RABBITMQ', 'REDIS'].includes(code)) {
    connection.databaseIp = '';
    connection.databasePort = '';
  }
  if (['KAFKA', 'RABBITMQ', 'ROCKETMQ'].includes(code))
    connection.databaseName = '';
  if (
    ['MILVUS', 'ROCKETMQ'].includes(code) &&
    config.authenticationMode === 'NONE'
  ) {
    connection.userName = '';
    connection.password = '';
  }
  if (code === 'MILVUS' && config.authenticationMode === 'TOKEN')
    connection.userName = '';
  return {
    ...connection,
    usageType: 1,
    jdbcUrl: '',
    databaseSchema: '',
    extendConfig: unchanged ? originalConfig : normalized,
  };
}
