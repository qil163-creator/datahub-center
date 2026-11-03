import { describe, expect, it } from 'vitest';

import {
  createMappingColumns,
  moveMappingColumn,
  refreshMappingColumns,
  serializeMappingColumns,
  serializeMappingLayout,
  validateFieldMappings,
} from './field-mapping';

const fields = [{ name: 'id', dataType: 'BIGINT' }, { name: 'name', dataType: 'VARCHAR' }];

describe('双列字段映射与执行契约', () => {
  it('同名初始映射只读构建，显式空映射不重新勾选', () => {
    expect(serializeMappingColumns(createMappingColumns(fields, fields))).toEqual([
      { source: 'id', target: 'id', enabled: true }, { source: 'name', target: 'name', enabled: true },
    ]);
    expect(serializeMappingColumns(createMappingColumns(fields, fields, [])).every((item) => !item.enabled)).toBe(true);
  });

  it('配置先于目标元数据返回时保留启用意图，字段就绪后恢复真实映射', () => {
    const configured = [
      { source: 'id', target: 'id', enabled: true },
      { source: 'name', target: 'name', enabled: false },
    ];
    const pending = createMappingColumns(fields, [], configured);
    expect(pending.target.map((field) => field.checked)).toEqual([true, false]);
    expect(pending.target.every((field) => !field.exists)).toBe(true);
    expect(serializeMappingColumns(pending).every((item) => !item.enabled)).toBe(true);
    const loaded = refreshMappingColumns(pending, fields, fields);
    expect(loaded.target.map((field) => field.checked)).toEqual([true, false]);
    expect(serializeMappingColumns(loaded)).toEqual(configured);
  });

  it('目标元数据重新加载后保留用户手动取消与重新启用，不恢复成旧配置', () => {
    const configured = [
      { source: 'id', target: 'id', enabled: true },
      { source: 'name', target: 'name', enabled: false },
    ];
    const columns = createMappingColumns(fields, fields, configured);
    columns.target[0]!.checked = false;
    columns.source[1]!.checked = true;
    columns.target[1]!.checked = true;
    const pending = refreshMappingColumns(columns, fields, []);
    const loaded = refreshMappingColumns(pending, fields, fields);
    expect(loaded.target.map((field) => field.checked)).toEqual([false, true]);
    expect(serializeMappingColumns(loaded)).toEqual([
      { source: 'id', target: 'id', enabled: false },
      { source: 'name', target: 'name', enabled: true },
    ]);
  });

  it('两侧独立排序/勾选，启用映射按行位置配对', () => {
    const columns = createMappingColumns(fields, fields);
    const reordered = moveMappingColumn(columns, 'target', 0, 1);
    expect(reordered.source.map((field) => field.name)).toEqual(['id', 'name']);
    expect(serializeMappingColumns(reordered)).toEqual([
      { source: 'id', target: 'name', enabled: true }, { source: 'name', target: 'id', enabled: true },
    ]);
    reordered.target[0]!.checked = false;
    expect(reordered.source[0]!.checked).toBe(true);
    expect(serializeMappingColumns(reordered)[0]!.enabled).toBe(false);
    expect(moveMappingColumn(reordered, 'target', -1, 1)).toBe(reordered);
  });

  it('只用一致的布局恢复独立勾选，陈旧布局不能覆盖执行配置', () => {
    const columns = createMappingColumns(fields, fields);
    columns.target[0]!.checked = false;
    const mappings = serializeMappingColumns(columns);
    const layout = serializeMappingLayout(columns);
    const restored = createMappingColumns(fields, fields, mappings, layout);
    expect(restored.source[0]!.checked).toBe(true);
    expect(restored.target[0]!.checked).toBe(false);
    const modifiedMappings = [{ source: 'id', target: 'name', enabled: true }];
    expect(serializeMappingColumns(createMappingColumns(fields, fields, modifiedMappings, layout))[0]).toEqual(modifiedMappings[0]);
  });

  it('元数据刷新保留排序与勾选，缺失/未知类型不会伪造', () => {
    const columns = moveMappingColumn(createMappingColumns(fields, fields), 'source', 0, 1);
    columns.source[0]!.checked = false;
    const refreshed = refreshMappingColumns(columns, [{ name: 'name' }, { name: 'id', dataType: 'BIGINT' }], [{ name: 'id' }]);
    expect(refreshed.source[0]).toMatchObject({ name: 'name', checked: false });
    expect(refreshed.source[0]!.dataType).toBeUndefined();
    expect(refreshed.target[1]).toMatchObject({ name: 'name', exists: false });
  });

  it('校验缺失字段/重复目标，允许同源写入不同目标且不猜测类型兼容', () => {
    expect(validateFieldMappings([{ source: 'absent', target: 'missing', enabled: true }], fields, fields)).toEqual(['来源字段不存在：absent', '目标字段不存在：missing']);
    expect(validateFieldMappings([{ source: 'id', target: 'id', enabled: true }, { source: 'name', target: 'id', enabled: true }], fields, fields)).toEqual(['目标字段不能重复：id']);
    expect(validateFieldMappings([{ source: 'id', target: 'id', enabled: true }, { source: 'id', target: 'name', enabled: true }], fields, fields)).toEqual([]);
    expect(validateFieldMappings([], fields, fields)).toEqual(['请至少配置一个字段映射']);
  });
});
