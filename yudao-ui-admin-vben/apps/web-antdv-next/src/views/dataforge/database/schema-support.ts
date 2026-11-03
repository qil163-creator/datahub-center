/** 具备独立 schema/模式 概念的数据源编码 */
const SCHEMA_TYPE_CODES = new Set([
  'DAMENG',
  'DB2',
  'DM',
  'GAUSSDB',
  'GBASE8C',
  'GBASE8S',
  'GREENPLUM',
  'HIGHGO',
  'KINGBASE',
  'KINGBASE8',
  'KINGBASEES',
  'MSSQL',
  'OB',
  'OCEANBASE',
  'OPENGAUSS',
  'ORACLE',
  'ORACLESERVICE',
  'ORACLESID',
  'OSCAR',
  'PG',
  'PGSQL',
  'POSTGRES',
  'POSTGRESQL',
  'PRESTO',
  'SQLSERVER',
  'SQLSERVER2000',
  'SYBASE',
  'UXDB',
]);

function normalizeTypeCode(typeCode?: string) {
  // 与 DB Switch 产品注册表保持一致，同时保留平台历史类型编码。
  return typeCode?.toUpperCase().replaceAll(/[-_\s]/gu, '') ?? '';
}

export function hasSchemaConcept(typeCode?: string) {
  return SCHEMA_TYPE_CODES.has(normalizeTypeCode(typeCode));
}

export function schemaPlaceholder(typeCode?: string) {
  const code = normalizeTypeCode(typeCode);
  if (['MSSQL', 'SQLSERVER', 'SQLSERVER2000', 'SYBASE'].includes(code)) {
    return 'dbo';
  }
  if (
    ['DAMENG', 'DM', 'ORACLE', 'ORACLESERVICE', 'ORACLESID', 'OSCAR'].includes(
      code,
    )
  ) {
    return '请输入实际 Schema/Owner，如 SYSTEM';
  }
  if (code === 'DB2') {
    return '请输入实际 Schema，如 DB2INST1';
  }
  if (code === 'OB' || code === 'OCEANBASE') {
    return 'MySQL 模式填数据库名，Oracle 模式填实际 Owner/Schema';
  }
  if (
    [
      'GAUSSDB',
      'GBASE8C',
      'GREENPLUM',
      'HIGHGO',
      'KINGBASE',
      'KINGBASE8',
      'KINGBASEES',
      'OPENGAUSS',
      'PG',
      'PGSQL',
      'POSTGRES',
      'POSTGRESQL',
      'UXDB',
    ].includes(code)
  ) {
    return 'public';
  }
  return '请输入 Schema';
}
