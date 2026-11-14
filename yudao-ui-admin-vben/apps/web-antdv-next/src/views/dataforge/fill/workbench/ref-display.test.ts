import { describe, expect, it } from 'vitest';

import { formatRefDisplay, parseRefDisplay } from './ref-display';
import { mapFieldSchema } from './schema-mapper';

const labels = new Map([
  ['12', '张三'],
  ['13', '李四'],
  ['14', '张三'],
]);

describe('编号类字段的显示与保存值', () => {
  it('保存编号，显示“名称（编号）”，同名也能区分', () => {
    expect(formatRefDisplay('12,14', labels)).toBe('张三（12），张三（14）');
    expect(formatRefDisplay('99', labels)).toBe('未知（99）');
    expect(formatRefDisplay('', labels)).toBe('');
  });

  it('显示文本解析回编号；同名时不按名称猜测', () => {
    expect(parseRefDisplay('张三（12），李四（13）', labels)).toBe('12,13');
    expect(parseRefDisplay('李四', labels)).toBe('13');
    expect(parseRefDisplay('张三', labels)).toBe('张三');
    expect(parseRefDisplay('13', labels)).toBe('13');
    expect(parseRefDisplay(' ', labels)).toBe('');
  });

  it('模板字段带上快照中的编号选项与级联配置', () => {
    const fields = mapFieldSchema(
      JSON.stringify([
        { fieldCode: 'owner', fieldType: 2, name: '负责人', refKind: 'USER' },
        {
          cascadeOptions: { 浙江: ['杭州'] },
          cascadeParent: 'province',
          dictOptions: ['杭州', '南京'],
          fieldCode: 'city',
          fieldType: 2,
          fillType: 2,
          name: '城市',
        },
      ]),
      { owner: [{ label: '张三', value: '12' }] },
    );
    expect(fields[0]?.refOptions).toEqual([{ label: '张三', value: '12' }]);
    expect(fields[1]?.cascadeParent).toBe('province');
    expect(fields[1]?.cascadeOptions).toEqual({ 浙江: ['杭州'] });
    expect(fields[1]?.fieldType).toBe('dict');
  });
});
