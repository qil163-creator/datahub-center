import type { FoundationDatabaseApi } from '#/api/dataforge/database';

export interface DiagnosisSummary {
  name?: string;
  typeName?: string;
  iconPath?: string;
  host?: string;
  port?: string;
  userName?: string;
}

export interface DiagnosisModalData {
  summary?: DiagnosisSummary;
  execute?: (
    includeWriteChecks: boolean,
    signal: AbortSignal,
  ) => Promise<unknown>;
}

export const DIAGNOSIS_STATUS = {
  PASS: { label: '通过', icon: 'lucide:circle-check' },
  WARN: { label: '警告', icon: 'lucide:triangle-alert' },
  FAIL: { label: '错误', icon: 'lucide:circle-x' },
  SKIPPED: { label: '跳过', icon: 'lucide:circle-minus' },
} as const;

export function diagnosisStatusCount(
  counts: FoundationDatabaseApi.DiagnosisCounts,
  state: FoundationDatabaseApi.DiagnosisStatus,
) {
  return {
    PASS: counts.normalCount,
    WARN: counts.warningCount,
    FAIL: counts.errorCount,
    SKIPPED: counts.skippedCount,
  }[state];
}

/** 摘要只提取可展示字段，不传递密码、JDBC URL 或扩展连接属性。 */
export function diagnosisSummary(
  database: Partial<FoundationDatabaseApi.Database>,
): DiagnosisSummary {
  return {
    name: database.name,
    typeName: database.databaseTypeName || database.typeCode,
    iconPath: database.iconPath,
    host: database.databaseIp,
    port: database.databasePort,
    userName: database.userName,
  };
}

export class InvalidDiagnosisError extends Error {
  constructor() {
    super('诊断返回数据不完整或不一致，请重新诊断。');
  }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new InvalidDiagnosisError();
  return value as Record<string, unknown>;
}
function text(value: unknown, required = false): string {
  if (typeof value !== 'string' || (required && !value.trim()))
    throw new InvalidDiagnosisError();
  return value;
}
function integer(value: unknown) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)
    throw new InvalidDiagnosisError();
  return value;
}
function status(value: unknown): FoundationDatabaseApi.DiagnosisStatus {
  if (
    value !== 'PASS' &&
    value !== 'WARN' &&
    value !== 'FAIL' &&
    value !== 'SKIPPED'
  )
    throw new InvalidDiagnosisError();
  return value;
}
function counts(
  source: Record<string, unknown>,
  statuses: FoundationDatabaseApi.DiagnosisStatus[],
): FoundationDatabaseApi.DiagnosisCounts {
  const result = {
    normalCount: integer(source.normalCount),
    warningCount: integer(source.warningCount),
    errorCount: integer(source.errorCount),
    skippedCount: integer(source.skippedCount),
    totalCount: integer(source.totalCount),
  };
  const expected = {
    normalCount: 'PASS',
    warningCount: 'WARN',
    errorCount: 'FAIL',
    skippedCount: 'SKIPPED',
  } as const;
  if (
    result.totalCount !== statuses.length ||
    Object.entries(expected).some(
      ([key, state]) =>
        result[key as keyof typeof expected] !==
        statuses.filter((item) => item === state).length,
    )
  )
    throw new InvalidDiagnosisError();
  return result;
}
function aggregate(statuses: FoundationDatabaseApi.DiagnosisStatus[]) {
  if (statuses.includes('FAIL')) return 'FAIL';
  if (statuses.includes('WARN')) return 'WARN';
  if (statuses.includes('PASS')) return 'PASS';
  return 'SKIPPED';
}

/** 空响应、旧布尔响应或不一致计数均拒绝展示为成功。 */
export function parseDiagnosis(
  value: unknown,
): FoundationDatabaseApi.Diagnosis {
  const source = object(value);
  if (!Array.isArray(source.groups) || source.groups.length === 0)
    throw new InvalidDiagnosisError();
  const groups = source.groups.map(
    (entry): FoundationDatabaseApi.DiagnosisGroup => {
      const group = object(entry);
      if (!Array.isArray(group.items)) throw new InvalidDiagnosisError();
      const items = group.items.map(
        (entry): FoundationDatabaseApi.DiagnosisItem => {
          const item = object(entry);
          return {
            checkCode: text(item.checkCode, true),
            checkName: text(item.checkName, true),
            checkContent: text(item.checkContent),
            status: status(item.status),
            message: text(item.message),
            impact: item.impact == null ? '' : text(item.impact),
            durationMs: integer(item.durationMs),
          };
        },
      );
      const itemStatuses = items.map((item) => item.status);
      const groupStatus = status(group.status);
      if (
        groupStatus !== aggregate(itemStatuses) ||
        new Set(items.map((item) => item.checkCode)).size !== items.length
      )
        throw new InvalidDiagnosisError();
      return {
        groupCode: text(group.groupCode, true),
        groupName: text(group.groupName, true),
        description: text(group.description),
        status: groupStatus,
        ...counts(group, itemStatuses),
        items,
      };
    },
  );
  const itemStatuses = groups.flatMap((group) =>
    group.items.map((item) => item.status),
  );
  const overallStatus = status(source.overallStatus);
  if (
    itemStatuses.length === 0 ||
    overallStatus !== aggregate(itemStatuses) ||
    typeof source.connectionSuccessful !== 'boolean' ||
    new Set(groups.map((group) => group.groupCode)).size !== groups.length
  )
    throw new InvalidDiagnosisError();
  return {
    diagnosisId: text(source.diagnosisId, true),
    overallStatus,
    connectionSuccessful: source.connectionSuccessful,
    checkedAt: integer(source.checkedAt),
    durationMs: integer(source.durationMs),
    ...counts(source, itemStatuses),
    groups,
  };
}
