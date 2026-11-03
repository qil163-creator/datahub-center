import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { describe, expect, it } from 'vitest';

import {
  diagnosisStatusCount,
  diagnosisSummary,
  InvalidDiagnosisError,
  parseDiagnosis,
} from './diagnosis';

function result(): FoundationDatabaseApi.Diagnosis {
  const items: FoundationDatabaseApi.DiagnosisItem[] = [
    {
      checkCode: 'AUTH',
      checkName: '账号认证',
      checkContent: '验证当前连接',
      status: 'PASS',
      message: '',
      impact: '',
      durationMs: 12,
    },
    {
      checkCode: 'TEMP',
      checkName: '临时表写入',
      checkContent: '验证临时表写入能力',
      status: 'SKIPPED',
      message: '本次未启用写入验证',
      impact: '写入能力未验证',
      durationMs: 0,
    },
  ];
  return {
    diagnosisId: 'test-diagnosis',
    overallStatus: 'PASS',
    connectionSuccessful: true,
    checkedAt: 1_792_000_000_000,
    durationMs: 12,
    normalCount: 1,
    warningCount: 0,
    errorCount: 0,
    skippedCount: 1,
    totalCount: 2,
    groups: [
      {
        groupCode: 'BASIC',
        groupName: '基础连接',
        description: '验证账号认证',
        status: 'PASS',
        normalCount: 1,
        warningCount: 0,
        errorCount: 0,
        skippedCount: 0,
        totalCount: 1,
        items: [items[0]!],
      },
      {
        groupCode: 'WRITE',
        groupName: '写入能力',
        description: '验证写入能力',
        status: 'SKIPPED',
        normalCount: 0,
        warningCount: 0,
        errorCount: 0,
        skippedCount: 1,
        totalCount: 1,
        items: [items[1]!],
      },
    ],
  };
}

describe('数据库诊断契约校验', () => {
  it('允许空的跳过分组，同时徽标展示对应状态计数', () => {
    const value = result();
    value.groups.push({
      groupCode: 'ADVANCED',
      groupName: '高级能力',
      description: '没有适用项',
      status: 'SKIPPED',
      normalCount: 0,
      warningCount: 0,
      errorCount: 0,
      skippedCount: 0,
      totalCount: 0,
      items: [],
    });
    expect(parseDiagnosis(value).groups).toHaveLength(3);
    expect(
      diagnosisStatusCount(
        {
          normalCount: 2,
          warningCount: 1,
          errorCount: 3,
          skippedCount: 4,
          totalCount: 10,
        },
        'FAIL',
      ),
    ).toBe(3);
    const empty = {
      ...value,
      normalCount: 0,
      skippedCount: 0,
      totalCount: 0,
      overallStatus: 'SKIPPED',
      connectionSuccessful: false,
      groups: [value.groups[2]],
    };
    expect(() => parseDiagnosis(empty)).toThrow(InvalidDiagnosisError);
  });
  it('允许只读检查通过且整个写入组被跳过，保留真实计数和原因', () => {
    expect(parseDiagnosis(result())).toEqual(result());
  });
  it('兼容空缺影响说明，同时保留通过、失败和跳过的实际状态', () => {
    for (const failed of [false, true]) {
      const value = result();
      const read = value.groups[0]!;
      if (failed) {
        value.overallStatus = 'FAIL';
        value.connectionSuccessful = false;
        value.normalCount = 0;
        value.errorCount = 1;
        read.status = 'FAIL';
        read.normalCount = 0;
        read.errorCount = 1;
        read.items[0]!.status = 'FAIL';
      }
      const wire = value as unknown as {
        groups: { items: Record<string, unknown>[] }[];
      };
      wire.groups[0]!.items[0]!.impact = null;
      delete wire.groups[1]!.items[0]!.impact;

      const parsed = parseDiagnosis(value);
      expect(parsed.overallStatus).toBe(failed ? 'FAIL' : 'PASS');
      expect(parsed.connectionSuccessful).toBe(!failed);
      expect(parsed.groups[0]!.items[0]).toMatchObject({
        impact: '',
        status: failed ? 'FAIL' : 'PASS',
      });
      expect(parsed.groups[1]!.items[0]).toMatchObject({
        impact: '',
        status: 'SKIPPED',
      });
      expect(parsed.skippedCount).toBe(1);
    }
  });
  it('拒绝非字符串影响说明及其余不一致的诊断数据', () => {
    for (const impact of [0, false, {}, []]) {
      const value = result() as unknown as {
        groups: { items: Record<string, unknown>[] }[];
      };
      value.groups[0]!.items[0]!.impact = impact;
      expect(() => parseDiagnosis(value)).toThrow(InvalidDiagnosisError);
    }
    const inconsistent = result();
    inconsistent.normalCount = 2;
    const wire = inconsistent as unknown as {
      groups: { items: Record<string, unknown>[] }[];
    };
    wire.groups[0]!.items[0]!.impact = null;
    expect(() => parseDiagnosis(inconsistent)).toThrow(InvalidDiagnosisError);
  });
  it('拒绝空响应、旧布尔响应、缺失分组与没有检查项的响应', () => {
    for (const value of [
      null,
      true,
      {},
      { ...result(), groups: [] },
      { ...result(), groups: [{ ...result().groups[0], items: [] }] },
    ]) {
      expect(() => parseDiagnosis(value)).toThrow(InvalidDiagnosisError);
    }
  });
  it('拒绝未知状态、汇总与分项不一致、计数遗漏及重复诊断项', () => {
    const invalid: unknown[] = [
      { ...result(), overallStatus: 'SUCCESS' },
      { ...result(), overallStatus: 'SKIPPED' },
      { ...result(), normalCount: 2 },
      { ...result(), warningCount: -1 },
      { ...result(), durationMs: Number.NaN },
      { ...result(), connectionSuccessful: 'true' },
      {
        ...result(),
        groups: result().groups.map((group) => ({ ...group, status: 'PASS' })),
      },
      { ...result(), groups: [result().groups[0], result().groups[0]] },
    ];
    const duplicate = result();
    duplicate.groups[0]!.items.push(duplicate.groups[0]!.items[0]!);
    invalid.push(duplicate);
    const missing = result() as unknown as {
      groups: { items: Record<string, unknown>[] }[];
    };
    delete missing.groups[0]!.items[0]!.checkContent;
    invalid.push(missing);
    for (const value of invalid)
      expect(() => parseDiagnosis(value)).toThrow(InvalidDiagnosisError);
  });
  it('接受整个诊断均跳过，不把跳过状态升级成通过', () => {
    const skipped = result();
    skipped.overallStatus = 'SKIPPED';
    skipped.connectionSuccessful = false;
    skipped.normalCount = 0;
    skipped.skippedCount = 2;
    skipped.groups[0]!.status = 'SKIPPED';
    skipped.groups[0]!.normalCount = 0;
    skipped.groups[0]!.skippedCount = 1;
    skipped.groups[0]!.items[0]!.status = 'SKIPPED';
    expect(parseDiagnosis(skipped).overallStatus).toBe('SKIPPED');
  });
  it('展示摘要和结果不携带连接凭据、原始 SQL 或后端错误堆栈', () => {
    const summary = diagnosisSummary({
      id: '9007199254740993',
      name: '测试连接',
      databaseIp: 'db.test',
      userName: 'reader',
      password: 'fixture-only',
      jdbcUrl: 'jdbc:fixture',
      extendConfig: 'private-options',
    });
    expect(summary).toEqual({
      name: '测试连接',
      typeName: undefined,
      iconPath: undefined,
      host: 'db.test',
      port: undefined,
      userName: 'reader',
    });
    const safe = parseDiagnosis({
      ...result(),
      rawError: 'private-error',
      diagnosisLogs: ['private-sql'],
      password: 'fixture-only',
    });
    expect(safe).not.toHaveProperty('rawError');
    expect(safe).not.toHaveProperty('diagnosisLogs');
    expect(safe).not.toHaveProperty('password');
  });
});
