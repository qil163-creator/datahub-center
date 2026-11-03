import { describe, expect, it } from 'vitest';

import { hasSchemaConcept, schemaPlaceholder } from './schema-support';

describe('hasSchemaConcept', () => {
  it.each([
    'DB2',
    'DM',
    'GREENPLUM',
    'HIGHGO',
    'KINGBASE',
    'OCEANBASE',
    'OPENGAUSS',
    'ORACLE',
    'OSCAR',
    'POSTGRESQL',
    'SQLSERVER',
    'SQLSERVER2000',
    'SYBASE',
    'UXDB',
  ])('shows the required independent Schema for %s', (code) => {
    expect(hasSchemaConcept(code)).toBe(true);
  });

  it.each([
    'CLICKHOUSE',
    'DORIS',
    'ELASTICSEARCH',
    'GBASE8A',
    'HIVE',
    'INFLUXDB3',
    'MARIADB',
    'MONGODB',
    'MYSQL',
    'SQLITE3',
    'STARROCKS',
    'TDENGINE',
    'TIDB',
  ])(
    'uses the component database/default namespace for %s without a required Schema field',
    (code) => {
      expect(hasSchemaConcept(code)).toBe(false);
    },
  );

  it.each([
    'DAMENG',
    'KINGBASE8',
    'KINGBASEES',
    'MSSQL',
    'OB',
    'PG',
    'PGSQL',
    'POSTGRES',
    '  db-2 ',
    'sql_server',
    'SQL Server 2000',
    'Oracle SID',
    'Oracle_Service',
    'GBASE_8S',
  ])('recognizes component aliases and existing platform code %s', (code) => {
    expect(hasSchemaConcept(code)).toBe(true);
  });

  it.each([
    'ES',
    'GBASE',
    'HIVE2',
    'INFLUXDB',
    'MARIA',
    'SQLITE',
    '',
    'UNKNOWN',
    undefined,
  ])('does not require Schema for %s', (code) => {
    expect(hasSchemaConcept(code)).toBe(false);
  });
});

describe('schemaPlaceholder', () => {
  it.each(['SQL_SERVER', 'SQL Server 2000', 'MSSQL', 'SYBASE'])(
    'uses dbo for %s',
    (code) => {
      expect(schemaPlaceholder(code)).toBe('dbo');
    },
  );

  it('requests an actual DB2 schema instead of implying public', () => {
    expect(schemaPlaceholder('DB2')).toBe('请输入实际 Schema，如 DB2INST1');
  });

  it.each(['OceanBase', 'OB'])(
    'requires the actual compatibility mode namespace for %s',
    (code) => {
      expect(schemaPlaceholder(code)).toBe(
        'MySQL 模式填数据库名，Oracle 模式填实际 Owner/Schema',
      );
    },
  );

  it.each([
    'POSTGRESQL',
    'PG',
    'PGSQL',
    'POSTGRES',
    'GREENPLUM',
    'HIGHGO',
    'KINGBASE8',
    'KINGBASEES',
    'OPENGAUSS',
    'UXDB',
  ])('suggests public for %s', (code) => {
    expect(schemaPlaceholder(code)).toBe('public');
  });

  it.each(['DM', 'DAMENG', 'ORACLE', 'Oracle SID', 'Oracle_Service', 'OSCAR'])(
    'suggests the owner for %s',
    (code) => {
      expect(schemaPlaceholder(code)).toBe('请输入实际 Schema/Owner，如 SYSTEM');
    },
  );

  it.each(['GBASE_8S', undefined])(
    'does not invent a default for %s',
    (code) => {
      expect(schemaPlaceholder(code)).toBe('请输入 Schema');
    },
  );
});
