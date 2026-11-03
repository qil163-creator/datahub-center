const CONNECTION_ICON_DIRECTORY = '/connection-icons/';
const LEGACY_SEED_ICON_NAMES = new Set([
  'clickhouse',
  'dm',
  'doris',
  'gbase',
  'greenplum',
  'highgo',
  'hive',
  'kingbase',
  'mariadb',
  'mysql',
  'oceanbase',
  'opengauss',
  'oracle',
  'oscar',
  'postgres',
  'sqlserver',
  'starrocks',
  'uxdb',
]);

const PRODUCT_ICON_FILES: Record<string, string> = {
  CLICKHOUSE: 'clickhouse.svg',
  DB2: 'db2.png',
  DM: 'dm.svg',
  DORIS: 'doris.svg',
  ELASTICSEARCH: 'elasticsearch.png',
  ES: 'elasticsearch.png',
  FTP: 'ftp.png',
  GAUSSDB: 'gaussdb.png',
  GBASE: 'gbase8c.png',
  GBASE8A: 'gbase8c.png',
  GBASE8C: 'gbase8c.png',
  GBASE_8A: 'gbase8c.png',
  GBASE_8C: 'gbase8c.png',
  GREENPLUM: 'greenplum.svg',
  HDFS: 'hdfs.png',
  HIGHGO: 'highgo.svg',
  HIVE: 'hive.svg',
  INFLUXDB: 'influxdb3.png',
  INFLUXDB2: 'influxdb2.png',
  INFLUXDB3: 'influxdb3.png',
  KAFKA: 'kafka.png',
  KINGBASE: 'kingbase.ico',
  KINGBASE8: 'kingbase.ico',
  KINGBASEES: 'kingbase.ico',
  MARIADB: 'mariadb.svg',
  MILVUS: 'milvus.png',
  MINIO: 'minio.png',
  MONGODB: 'mongodb.png',
  MYSQL: 'mysql.svg',
  NEO4J: 'neo4j.png',
  OCEANBASE: 'oceanbase.svg',
  ODPS: 'odps.png',
  OPENGAUSS: 'opengauss.svg',
  ORACLE: 'oracle.svg',
  ORACLESERVICE: 'oracle.svg',
  ORACLESID: 'oracle.svg',
  ORACLE_SERVICE: 'oracle.svg',
  ORACLE_SID: 'oracle.svg',
  OSCAR: 'oscar.png',
  OSS: 'oss.png',
  PHOENIX: 'phoenix.png',
  POSTGRES: 'postgresql.svg',
  POSTGRESQL: 'postgresql.svg',
  PRESTO: 'presto.png',
  RABBITMQ: 'rabbitmq.png',
  REDIS: 'redis.png',
  ROCKETMQ: 'rocketmq.png',
  SAPHANA: 'saphana.png',
  SFTP: 'sftp.png',
  SQLITE: 'sqlite3.png',
  SQLITE3: 'sqlite3.png',
  'SQL SERVER': 'sqlserver.svg',
  SQLSERVER: 'sqlserver.svg',
  SQLSERVER2000: 'sqlserver2000.png',
  STARROCKS: 'starrocks.png',
  SYBASE: 'sybase.png',
  TDENGINE: 'tdengine.png',
  TIDB: 'tidb.png',
  UXDB: 'uxdb.png',
};

function isLegacySeedIconPath(path: string) {
  try {
    const url = new URL(path);
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      !['127.0.0.1', 'localhost'].includes(url.hostname) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      return false;
    const match =
      /^\/admin-api\/infra\/file\/29\/get\/20260806\/([a-z]+)\.(?:png|svg)$/u.exec(
        url.pathname,
      );
    return Boolean(match?.[1] && LEGACY_SEED_ICON_NAMES.has(match[1]));
  } catch {
    return false;
  }
}

/** 内置连接图标随应用部署前缀加载，自定义资源地址保持原值。 */
export function resolveConnectionIconPath(
  iconPath?: string,
  baseUrl = import.meta.env.BASE_URL,
) {
  const path = iconPath?.trim() || `${CONNECTION_ICON_DIRECTORY}database.png`;
  if (!path.startsWith(CONNECTION_ICON_DIRECTORY)) return path;
  return `${baseUrl.replace(/\/$/u, '')}${path}`;
}

/** 自定义图标优先，内置产品统一使用本地图标；不根据展示名称猜测产品。 */
export function resolveConnectionIconCandidates(
  iconPath?: string,
  typeCode?: string,
  baseUrl = import.meta.env.BASE_URL,
) {
  const code = typeCode?.trim().toUpperCase() ?? '';
  const productFile = Object.hasOwn(PRODUCT_ICON_FILES, code)
    ? PRODUCT_ICON_FILES[code]
    : undefined;
  const configuredPath = iconPath?.trim();
  const genericPath = resolveConnectionIconPath(undefined, baseUrl);
  const genericPaths = new Set([
    `${CONNECTION_ICON_DIRECTORY}database.png`,
    `${CONNECTION_ICON_DIRECTORY}database.svg`,
    genericPath,
    resolveConnectionIconPath(
      `${CONNECTION_ICON_DIRECTORY}database.svg`,
      baseUrl,
    ),
  ]);
  const candidates: string[] = [];
  const useProductDefault =
    productFile &&
    configuredPath &&
    (configuredPath.startsWith(CONNECTION_ICON_DIRECTORY) ||
      configuredPath.startsWith(
        resolveConnectionIconPath(CONNECTION_ICON_DIRECTORY, baseUrl),
      ) ||
      isLegacySeedIconPath(configuredPath));
  if (
    configuredPath &&
    !genericPaths.has(configuredPath) &&
    !useProductDefault
  ) {
    candidates.push(resolveConnectionIconPath(configuredPath, baseUrl));
  }
  if (productFile) {
    candidates.push(
      resolveConnectionIconPath(
        `${CONNECTION_ICON_DIRECTORY}${productFile}`,
        baseUrl,
      ),
    );
  }
  candidates.push(genericPath);
  return [...new Set(candidates)];
}
