import type { IconifyIconStructure } from '@vben/icons';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { DEV_TASK_PRODUCT_ICONS } from './task-product-icons';

export interface DevTaskIconInput {
  databaseIconPath?: string;
  dbType?: string;
  engine?: string;
  taskType?: string;
}

export interface DevTaskIcon {
  color: string;
  icon: IconifyIconStructure;
  imagePath?: string;
  label: string;
  mark?: string;
  product: string;
}

const ENGINE_BY_TASK_TYPE = {
  FLINK_JAR: 'FLINK',
  FLINK_SQL: 'FLINK',
  PROCEDURE: undefined,
  PYFLINK: 'FLINK',
  PYSPARK: 'SPARK',
  SHELL: undefined,
  SPARK_JAR: 'SPARK',
  SPARK_SQL: 'SPARK',
  SQL: undefined,
} satisfies Record<DataforgeDevApi.TaskType, 'FLINK' | 'SPARK' | undefined>;

const DATABASE_ALIASES: Record<string, string> = {
  APACHEDORIS: 'DORIS',
  APACHEHIVE: 'HIVE',
  DAMENG: 'DM',
  DM8: 'DM',
  MICROSOFTSQLSERVER: 'SQLSERVER',
  MSSQL: 'SQLSERVER',
  PG: 'POSTGRESQL',
  POSTGRES: 'POSTGRESQL',
  SQLITE: 'SQLITE3',
  SQLSERVER2000: 'SQLSERVER',
};

const DATABASE_PRODUCTS: Record<
  string,
  {
    color: string;
    icon?: keyof typeof DEV_TASK_PRODUCT_ICONS;
    label: string;
    mark?: string;
  }
> = {
  CLICKHOUSE: { color: '#f5c400', icon: 'clickhouse', label: 'ClickHouse' },
  DM: { color: '#2f6ec8', label: '达梦数据库', mark: 'DM' },
  DORIS: { color: '#2c7fe1', icon: 'doris', label: 'Apache Doris' },
  ELASTICSEARCH: {
    color: '#00bfb3',
    icon: 'elasticsearch',
    label: 'Elasticsearch',
  },
  HIVE: { color: '#d8ac00', icon: 'hive', label: 'Apache Hive' },
  MARIADB: { color: '#003545', icon: 'mariadb', label: 'MariaDB' },
  MONGODB: { color: '#47a248', icon: 'mongodb', label: 'MongoDB' },
  MYSQL: { color: '#00758f', icon: 'mysql', label: 'MySQL' },
  ORACLE: { color: '#e32124', icon: 'oracle', label: 'Oracle' },
  POSTGRESQL: { color: '#336791', icon: 'postgresql', label: 'PostgreSQL' },
  REDIS: { color: '#dc382d', icon: 'redis', label: 'Redis' },
  SQLITE3: { color: '#0f80cc', icon: 'sqlite', label: 'SQLite' },
  SQLSERVER: {
    color: '#cc2927',
    icon: 'sqlserver',
    label: 'Microsoft SQL Server',
  },
  TIDB: { color: '#d83b48', icon: 'tidb', label: 'TiDB' },
};

function normalizeCode(value?: string) {
  return (
    value
      ?.trim()
      .toUpperCase()
      .replaceAll(/[\s_-]+/g, '') ?? ''
  );
}

/** 数据库类型编码与旧名称共用同一产品键，供图标路径映射复用。 */
export function normalizeDevDatabaseType(dbType?: string) {
  const code = normalizeCode(dbType);
  return DATABASE_ALIASES[code] ?? code;
}

/** 任务引擎决定集群标志，数据库任务再按实际数据库产品取标志。 */
export function getDevTaskIcon(input: DevTaskIconInput): DevTaskIcon {
  const taskType = input.taskType
    ?.trim()
    .toUpperCase()
    .replaceAll(/[\s-]+/g, '_');
  const taskEngine =
    ENGINE_BY_TASK_TYPE[taskType as DataforgeDevApi.TaskType] ??
    (['FLINK', 'SPARK'].includes(normalizeCode(taskType))
      ? normalizeCode(taskType)
      : normalizeCode(input.engine));

  if (taskEngine === 'SPARK') {
    return {
      color: '#e25a1c',
      icon: DEV_TASK_PRODUCT_ICONS.spark,
      label: 'Apache Spark',
      product: 'SPARK',
    };
  }
  if (taskEngine === 'FLINK') {
    return {
      color: '#e6526f',
      icon: DEV_TASK_PRODUCT_ICONS.flink,
      label: 'Apache Flink',
      product: 'FLINK',
    };
  }
  if (taskType === 'SHELL') {
    return {
      color: '#64748b',
      icon: DEV_TASK_PRODUCT_ICONS.shell,
      label: 'Shell',
      product: 'SHELL',
    };
  }

  const product = normalizeDevDatabaseType(input.dbType);
  const database = DATABASE_PRODUCTS[product];
  if (product || taskType === 'SQL' || taskType === 'PROCEDURE') {
    return {
      color: database?.color ?? '#64748b',
      icon: database?.icon
        ? DEV_TASK_PRODUCT_ICONS[database.icon]
        : DEV_TASK_PRODUCT_ICONS.database,
      imagePath: input.databaseIconPath?.trim() || undefined,
      label: database?.label ?? (input.dbType?.trim() || '数据库任务'),
      mark: database?.mark,
      product: product || 'DATABASE',
    };
  }

  return {
    color: '#64748b',
    icon: DEV_TASK_PRODUCT_ICONS.unknown,
    label: input.taskType?.trim() || '开发任务',
    product: 'UNKNOWN',
  };
}
