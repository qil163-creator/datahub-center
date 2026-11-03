import type { SourceTableSelection } from './source-state';

import { describe, expect, it, vi } from 'vitest';

import {
  persistSourceSelections,
  sourceTableKey,
  sourceTargetName,
  validateSourceSelections,
} from './source-state';

function table(tableName: string): SourceTableSelection {
  return {
    tableName,
    comment: '来源表',
    targetName: sourceTargetName(tableName),
    targetComment: '目标表',
    tableCase: 1,
  };
}

describe('贴源逻辑表保存与选择', () => {
  it('命名大小写与 schema 唯一键保留真实源表字符', () => {
    expect(sourceTargetName('Rain_Station')).toBe('ODS_RAIN_STATION');
    expect(sourceTargetName('Rain_Station', 2, 'ODS_')).toBe(
      'ods_rain_station',
    );
    expect(
      sourceTableKey({ tableName: 'Rain_Station', schemaName: 'public' }),
    ).not.toBe(
      sourceTableKey({ tableName: 'Rain_Station', schemaName: 'archive' }),
    );
  });

  it('阻止空选择、无效名称、空中文名与大小写重复目标名', () => {
    expect(validateSourceSelections([])).toContain('至少选择');
    expect(
      validateSourceSelections([{ ...table('rain'), targetName: '_rain' }]),
    ).toContain('以字母开头');
    expect(
      validateSourceSelections([{ ...table('rain'), targetComment: ' ' }]),
    ).toContain('中文表名');
    expect(
      validateSourceSelections([
        table('rain'),
        { ...table('rain'), targetName: 'ods_rain' },
      ]),
    ).toContain('重复');
    expect(validateSourceSelections([table('rain'), table('river')])).toBe('');
  });

  it('批量部分失败后保留成功的完整 ID，重试只提交未成功行', async () => {
    const rows = [table('rain'), table('river')];
    const create = vi
      .fn()
      .mockResolvedValueOnce('9007199254740993')
      .mockRejectedValueOnce(new Error('conflict'))
      .mockResolvedValueOnce('9007199254740995');

    await persistSourceSelections(rows, create, () => true);
    expect(rows[0]?.savedId).toBe('9007199254740993');
    expect(rows[1]?.savedId).toBeUndefined();
    expect(rows[1]?.error).toContain('保存失败');

    await persistSourceSelections(rows, create, () => true);
    expect(create.mock.calls.map(([row]) => row.tableName)).toEqual([
      'rain',
      'river',
      'river',
    ]);
    expect(rows.map((row) => row.savedId)).toEqual([
      '9007199254740993',
      '9007199254740995',
    ]);
    expect(rows[1]?.error).toBeUndefined();
  });

  it('失效会话不回填保存结果，也不继续创建后续行', async () => {
    const rows = [table('rain'), table('river')];
    let current = true;
    const create = vi.fn(async () => {
      current = false;
      return '9007199254740993';
    });
    await persistSourceSelections(rows, create, () => current);
    expect(create).toHaveBeenCalledTimes(1);
    expect(rows.every((row) => row.savedId === undefined)).toBe(true);
  });

  it('创建响应丢失后核实真实记录，不重新创建已落库的表', async () => {
    const rows = [table('rain')];
    const create = vi.fn().mockRejectedValue(new Error('timeout'));
    const reconcile = vi.fn().mockResolvedValue('9007199254740993');
    await persistSourceSelections(rows, create, () => true, reconcile);
    await persistSourceSelections(rows, create, () => true, reconcile);
    expect(create).toHaveBeenCalledTimes(1);
    expect(rows[0]?.savedId).toBe('9007199254740993');
    expect(rows[0]?.uncertain).toBe(false);
  });

  it('未知保存结果的核实请求失败时保留条目并停止再次写入', async () => {
    const rows = [{ ...table('rain'), uncertain: true }];
    const create = vi.fn();
    await persistSourceSelections(
      rows,
      create,
      () => true,
      vi.fn().mockRejectedValue(new Error('offline')),
    );
    expect(create).not.toHaveBeenCalled();
    expect(rows[0]?.error).toContain('核实失败');
    expect(rows[0]?.uncertain).toBe(true);
  });
});
