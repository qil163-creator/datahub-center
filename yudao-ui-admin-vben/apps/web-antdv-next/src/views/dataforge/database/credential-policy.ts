import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import {
  NATIVE_CONNECTION_CODES,
  readNativeConnectionFields,
} from './native-connection';

/** 与服务端保持同一凭据身份：超时不改变目标，缺省配置与等价默认值保持一致。 */
function credentialConfig(config: string | undefined, typeCode: string) {
  if (![...NATIVE_CONNECTION_CODES, 'SFTP'].includes(typeCode))
    return config ?? '';
  try {
    const data: unknown = JSON.parse(config || '{}');
    if (!data || typeof data !== 'object' || Array.isArray(data))
      return config ?? '';
    const record = data as Record<string, unknown>;
    if (record.schemaVersion !== 1) return config ?? '';
    const normalized: Record<string, string> = {};
    for (const [key, value] of Object.entries(record)) {
      if (key === 'schemaVersion' || key === 'timeoutSeconds') continue;
      let text: string;
      if (
        key === 'servers' &&
        Array.isArray(value) &&
        value.every((item) => typeof item === 'string')
      ) {
        text = value.map((item) => item.trim()).join(',');
      } else if (typeof value === 'string' || typeof value === 'boolean') {
        text = String(value).trim();
      } else return config ?? '';
      if (text) normalized[key] = text;
    }
    const defaults: Record<string, Record<string, string>> = {
      OSS: { pathStyle: 'false' },
      MINIO: { pathStyle: 'true' },
      SFTP: { basePath: '/' },
      REDIS: { tls: 'false' },
      RABBITMQ: { tls: 'false', virtualHost: '/' },
      KAFKA: {
        securityProtocol: 'PLAINTEXT',
        saslMechanism: 'PLAIN',
        tls: 'false',
      },
      MILVUS: { authenticationMode: 'PASSWORD' },
      ROCKETMQ: { authenticationMode: 'NONE' },
    };
    const effective = { ...defaults[typeCode], ...normalized };
    if (typeCode === 'KAFKA' || typeCode === 'ROCKETMQ')
      effective.servers = (effective.servers ?? '')
        .split(/[,;]/u)
        .map((item) => item.trim())
        .join(',');
    return JSON.stringify(effective, Object.keys(effective).toSorted());
  } catch {
    return config ?? '';
  }
}

/** 编辑只展示连接配置，密码始终由用户重新输入，密钥不会进入表单。 */
export function databaseEditValues(database: FoundationDatabaseApi.Database) {
  const values: FoundationDatabaseApi.Database & { secretKey?: unknown } = {
    ...database,
    password: '',
  };
  delete values.secretKey;
  return values;
}

/** 与后端旧凭据测连门禁保持一致，目标或连接属性变化时要求新密码。 */
export function connectionTestNeedsPassword(
  original: FoundationDatabaseApi.Database | undefined,
  current: FoundationDatabaseApi.Database,
  typeCode = current.typeCode,
) {
  if (
    ['MILVUS', 'ROCKETMQ'].includes(typeCode?.toUpperCase() ?? '') &&
    readNativeConnectionFields(current.extendConfig)
      .connectionAuthenticationMode === 'NONE'
  )
    return false;
  if (
    !original?.id ||
    original.hasPassword === false ||
    current.password?.trim()
  )
    return false;
  const fields = [
    'databaseType',
    'databaseIp',
    'databasePort',
    'databaseName',
    'databaseSchema',
    'userName',
    'jdbcUrl',
    'extendConfig',
  ] as const;
  return fields.some((field) =>
    field === 'extendConfig'
      ? credentialConfig(original[field], typeCode?.toUpperCase() ?? '') !==
        credentialConfig(current[field], typeCode?.toUpperCase() ?? '')
      : String(original[field] ?? '') !== String(current[field] ?? ''),
  );
}
