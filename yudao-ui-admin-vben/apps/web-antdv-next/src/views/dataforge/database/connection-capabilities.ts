import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

type Capabilities = FoundationDatabaseTypeApi.DatabaseType['capabilities'];

const CONNECT_ONLY_TYPE_CODES = new Set([
  'GAUSSDB',
  'GBASE8C',
  'INFLUXDB2',
  'KAFKA',
  'MILVUS',
  'MINIO',
  'NEO4J',
  'ODPS',
  'OSS',
  'PHOENIX',
  'PRESTO',
  'RABBITMQ',
  'REDIS',
  'ROCKETMQ',
  'SAPHANA',
  'SFTP',
]);

export function isConnectOnlyType(type?: {
  capabilities?: Capabilities;
  typeCode?: string;
}) {
  const metadata = type?.capabilities?.METADATA;
  return (
    CONNECT_ONLY_TYPE_CODES.has(type?.typeCode?.toUpperCase() ?? '') &&
    (!metadata || !['DECLARED', 'VERIFIED'].includes(metadata.status))
  );
}

export function connectionCapability(type: { capabilities?: Capabilities }) {
  return type.capabilities?.CONNECT ?? type.capabilities?.METADATA;
}

export function isConnectionTypeSelectable(type: {
  capabilities?: Capabilities;
}) {
  const capability = connectionCapability(type);
  return !!capability && ['DECLARED', 'VERIFIED'].includes(capability.status);
}

/** 连接可用不代表元数据、查询或监控可用。旧目录仅为已知 JDBC 产品保留入口。 */
export function hasDatabaseMetadata(
  connection: Partial<FoundationDatabaseApi.Database>,
) {
  const capability = connection.capabilities?.METADATA;
  if (capability) return ['DECLARED', 'VERIFIED'].includes(capability.status);
  if (isConnectOnlyType(connection)) return false;
  return ![
    'FTP',
    'HDFS',
    'INFLUXDB2',
    'KAFKA',
    'MILVUS',
    'MINIO',
    'NEO4J',
    'ODPS',
    'OSS',
    'RABBITMQ',
    'REDIS',
    'ROCKETMQ',
    'SFTP',
  ].includes(connection.typeCode?.toUpperCase() ?? '');
}
