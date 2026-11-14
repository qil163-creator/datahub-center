import { describe, expect, it } from 'vitest';

import { describeImportResult, duplicateTargets, toMapping } from './import-mapping';

describe('Excel 导入列映射', () => {
  it('预检返回的表头对应关系转为可编辑映射，未匹配的表头为空', () => {
    expect(
      toMapping([
        { fieldCode: 'amount', header: '金额' },
        { header: '备注（旧）' },
      ]),
    ).toEqual({ '备注（旧）': '', 金额: 'amount' });
    expect(toMapping(undefined)).toEqual({});
  });

  it('找出被多个表头同时映射的字段', () => {
    expect(duplicateTargets({ 金额: 'amount', 金额2: 'amount', 名称: 'name', 其他: '' })).toEqual([
      'amount',
    ]);
    expect(duplicateTargets({ 甲: '', 乙: '' })).toEqual([]);
  });

  it('导入结果按新增、覆盖、跳过分别提示', () => {
    expect(
      describeImportResult({ addedCount: 3, draftRowCount: 10, skippedCount: 2, updatedCount: 1 }, false),
    ).toBe('导入成功：新增 3 行，覆盖 1 行，跳过重复 2 行（当前共 10 行）');
    expect(describeImportResult({ addedCount: 4, draftRowCount: 4 }, true)).toBe(
      '导入成功：已替换为 4 行（当前共 4 行）',
    );
  });
});
