import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { describe, expect, it } from 'vitest';

import {
  activeRun,
  detachFile,
  fieldError,
  LIMITS,
  mergeTargets,
  moveFile,
  reconcileTargets,
  routeId,
  sameStructure,
  splitTargets,
  targetError,
  uniqueTargetName,
  uploadError,
} from './model';

function file(
  id: string,
  name = '导入.xlsx',
  fields: Api.Field[] = [{ sourceIndex: 2, name: '编码', type: 'string' }],
): Api.File {
  return {
    id,
    name,
    type: 'xlsx',
    source: 'LOCAL',
    size: 100,
    sha256: 'digest',
    parseStatus: 'PARSED',
    rowCount: 1,
    fields,
  };
}
describe('批量文件结构与目标编排', () => {
  it('未知运行仅在尚未人工核查时占用任务', () => {
    expect(activeRun('UNKNOWN')).toBe(true);
    expect(activeRun('UNKNOWN', 1_792_127_460_000)).toBe(false);
    expect(activeRun('RUNNING', 1_792_127_460_000)).toBe(true);
    expect(activeRun('SUCCESS')).toBe(false);
  });
  it('保留大整数编号和删除后的原始列位置', () => {
    expect(routeId('9007199254740993')).toBe('9007199254740993');
    expect(routeId('1e3')).toBeUndefined();
    const rows = [file('9007199254740993'), file('9007199254740994')];
    const targets = reconcileTargets(rows, []);
    expect(targets).toEqual([
      { name: 'import_001', fileIds: [rows[0]!.id] },
      { name: 'import_002', fileIds: [rows[1]!.id] },
    ]);
    expect(rows[0]!.fields[0]!.sourceIndex).toBe(2);
  });
  it('接受中文字段，拒绝空名称、控制字符及忽略大小写重复', () => {
    expect(fieldError(file('1').fields)).toBe('');
    expect(
      fieldError([{ sourceIndex: 0, name: ' ', type: 'string' }]),
    ).toContain('不能为空');
    expect(
      fieldError([
        { sourceIndex: 0, name: 'foo', type: 'string' },
        { sourceIndex: 1, name: 'FOO', type: 'int' },
      ]),
    ).toContain('重复');
    expect(
      fieldError([{ sourceIndex: 0, name: 'a\nb', type: 'string' }]),
    ).toContain('控制字符');
    expect(fieldError([])).toContain('至少保留');
  });
  it('合并只比较确认后的名称、类型和顺序，允许源列位置不同', () => {
    const a = file('11');
    const b = file('12', 'next.csv', [
      { sourceIndex: 0, name: '编码', type: 'string' },
    ]);
    expect(sameStructure(a, b)).toBe(true);
    const targets = [
      { name: 'one', fileIds: [a.id] },
      { name: 'two', fileIds: [b.id] },
    ];
    const result = mergeTargets(targets, [1, 0], [a, b]);
    expect(result.targets).toEqual([{ name: 'one', fileIds: ['11', '12'] }]);
    expect(targets).toHaveLength(2);
    b.fields[0]!.type = 'int';
    const conflict = mergeTargets(targets, [0, 1], [a, b]);
    expect(conflict.error).toContain('不一致');
    expect(conflict.targets).toBe(targets);
    expect(conflict.conflicts).toEqual([a, b]);
  });
  it('拆分、拆出和上移后每个文件仍只出现一次，并避免表名冲突', () => {
    const files = [
      file('1', 'records.csv'),
      file('2', 'RECORDS.xlsx'),
      file('3'),
    ];
    const targets = [{ name: 'records', fileIds: ['1', '2', '3'] }];
    const moved = moveFile(targets[0]!, 2, -1);
    expect(moved.fileIds).toEqual(['1', '3', '2']);
    expect(moveFile(moved, 0, -1)).toBe(moved);
    const split = splitTargets([moved], [0], files);
    expect(split.map((row) => row.name)).toEqual([
      'records',
      'import_001',
      'import_002',
    ]);
    expect(targetError(files, split)).toBe('');
    const detached = detachFile(targets, 0, '2', files);
    expect(detached.map((row) => row.fileIds)).toEqual([['1', '3'], ['2']]);
    expect(targetError(files, detached)).toBe('');
    expect(uniqueTargetName('recordS.csv', ['records', 'import_001'])).toBe(
      'import_002',
    );
  });
  it('拒绝重复分配、缺失分配、重复表名及非法表名', () => {
    const files = [file('1'), file('2')];
    expect(targetError(files, [{ name: 'a', fileIds: ['1', '1'] }])).toContain(
      '只能',
    );
    expect(targetError(files, [{ name: 'a', fileIds: ['1'] }])).toContain(
      '只能',
    );
    expect(
      targetError(files, [
        { name: 'A', fileIds: ['1'] },
        { name: 'a', fileIds: ['2'] },
      ]),
    ).toContain('重复');
    expect(
      targetError(files, [{ name: '表名', fileIds: ['1', '2'] }]),
    ).toContain('英文字母');
  });
  it('按配置限制文件数量、扩展名、单文件与任务总大小', () => {
    expect(uploadError([{ name: 'data.csv', size: 1 }], [])).toBe('');
    expect(uploadError([{ name: 'data.zip', size: 1 }], [])).toContain(
      '仅支持',
    );
    expect(uploadError([{ name: 'data.csv', size: 0 }], [])).toContain(
      '不能为空',
    );
    expect(
      uploadError([{ name: 'data.csv', size: LIMITS.maxFileBytes + 1 }], []),
    ).toContain('20MB');
    expect(
      uploadError(
        Array.from({ length: 21 }, () => ({ name: 'a.csv', size: 1 })),
        [],
      ),
    ).toContain('20个');
    expect(
      uploadError(
        [{ name: 'data.csv', size: 1 }],
        [{ ...file('1'), size: '9007199254740993' }],
      ),
    ).toContain('总大小');
  });
});
