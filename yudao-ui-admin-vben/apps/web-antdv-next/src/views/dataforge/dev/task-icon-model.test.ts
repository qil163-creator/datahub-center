import { describe, expect, it } from 'vitest';

import { getDevTaskIcon, normalizeDevDatabaseType } from './task-icon-model';
import { DEV_TASK_PRODUCT_ICONS } from './task-product-icons';

describe('数据开发任务产品图标', () => {
  it.each(['SPARK_SQL', 'SPARK_JAR', 'PYSPARK'])(
    '%s 优先显示 Spark',
    (taskType) => {
      const icon = getDevTaskIcon({
        databaseIconPath: '/database/doris.svg',
        dbType: 'DORIS',
        taskType,
      });
      expect(icon.product).toBe('SPARK');
      expect(icon.icon).toBe(DEV_TASK_PRODUCT_ICONS.spark);
      expect(icon.imagePath).toBeUndefined();
    },
  );

  it.each(['FLINK_SQL', 'FLINK_JAR', 'PYFLINK'])(
    '%s 优先显示 Flink',
    (taskType) => {
      const icon = getDevTaskIcon({
        databaseIconPath: '/database/postgresql.svg',
        dbType: 'POSTGRESQL',
        taskType,
      });
      expect(icon.product).toBe('FLINK');
      expect(icon.icon).toBe(DEV_TASK_PRODUCT_ICONS.flink);
      expect(icon.imagePath).toBeUndefined();
    },
  );

  it('旧数据缺少任务类型时使用服务端引擎信息', () => {
    expect(getDevTaskIcon({ dbType: 'MYSQL', engine: 'flink' }).product).toBe(
      'FLINK',
    );
    expect(getDevTaskIcon({ taskType: ' spark-sql ' }).product).toBe('SPARK');
  });

  it.each(['SQL', 'PROCEDURE'])('%s 按数据库产品分流', (taskType) => {
    expect(getDevTaskIcon({ dbType: 'MYSQL', taskType }).icon).toBe(
      DEV_TASK_PRODUCT_ICONS.mysql,
    );
    expect(getDevTaskIcon({ dbType: 'HIVE', taskType }).icon).toBe(
      DEV_TASK_PRODUCT_ICONS.hive,
    );
    expect(getDevTaskIcon({ dbType: 'DORIS', taskType }).icon).toBe(
      DEV_TASK_PRODUCT_ICONS.doris,
    );
  });

  it.each([
    ['Postgres', 'POSTGRESQL'],
    ['pg', 'POSTGRESQL'],
    ['SQL Server', 'SQLSERVER'],
    ['mssql', 'SQLSERVER'],
    ['sqlite', 'SQLITE3'],
    ['dameng', 'DM'],
    ['Apache Hive', 'HIVE'],
  ])('数据库别名 %s 对齐产品 %s', (alias, product) => {
    expect(normalizeDevDatabaseType(` ${alias} `)).toBe(product);
    expect(getDevTaskIcon({ dbType: alias, taskType: 'SQL' }).product).toBe(
      product,
    );
  });

  it('复用产品配置的图标路径，缺失时保留本地产品标志', () => {
    expect(
      getDevTaskIcon({
        databaseIconPath: '/database/dm.svg',
        dbType: 'DM',
        taskType: 'SQL',
      }),
    ).toMatchObject({
      imagePath: '/database/dm.svg',
      label: '达梦数据库',
      product: 'DM',
    });
    expect(getDevTaskIcon({ dbType: 'DM', taskType: 'SQL' }).mark).toBe('DM');
    expect(getDevTaskIcon({ dbType: 'mysql', taskType: 'SQL' }).icon).toBe(
      DEV_TASK_PRODUCT_ICONS.mysql,
    );
  });

  it('脚本任务不因连接数据库而显示数据库标志', () => {
    expect(
      getDevTaskIcon({
        databaseIconPath: '/database/mysql.svg',
        dbType: 'MYSQL',
        taskType: 'SHELL',
      }),
    ).toMatchObject({
      icon: DEV_TASK_PRODUCT_ICONS.shell,
      label: 'Shell',
      product: 'SHELL',
    });
  });

  it('未知类型提供可识别文案与离线兜底', () => {
    expect(getDevTaskIcon({ taskType: 'FUTURE_TASK' })).toMatchObject({
      icon: DEV_TASK_PRODUCT_ICONS.unknown,
      label: 'FUTURE_TASK',
      product: 'UNKNOWN',
    });
    expect(
      getDevTaskIcon({ dbType: 'CUSTOM_DB', taskType: 'SQL' }),
    ).toMatchObject({
      icon: DEV_TASK_PRODUCT_ICONS.database,
      label: 'CUSTOM_DB',
    });
    expect(getDevTaskIcon({ taskType: 'SQL' }).label).toBe('数据库任务');
  });
});
