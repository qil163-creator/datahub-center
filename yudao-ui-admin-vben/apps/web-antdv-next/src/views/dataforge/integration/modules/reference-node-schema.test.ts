import { describe, expect, it } from 'vitest';

import {
  moveReferenceField,
  parseReferenceParameters,
  resolveReferenceFields,
  validateReferenceFields,
} from './reference-node-schema';

describe('qData专用字段配置', () => {
  it('获取新字段保留现有别名、未知属性和删除选择，不猜测未知类型', () => {
    const current = [{ name: 'A', alias: 'a', custom: { keep: true } }];
    const result = resolveReferenceFields(
      current,
      ['B'],
      [{ name: 'A' }, { name: 'B' }, { name: 'C' }],
      'addNewOnly',
    );
    expect(result).toEqual([
      { name: 'A', alias: 'a', custom: { keep: true } },
      { name: 'C' },
    ]);
    expect(current).toEqual([
      { name: 'A', alias: 'a', custom: { keep: true } },
    ]);
  });
  it.each(['addAll', 'clearAndAddAll'] as const)(
    '%s依照qData重新加入全部字段',
    (mode) => {
      expect(
        resolveReferenceFields(
          [{ name: 'OLD' }],
          ['B'],
          [{ name: 'B', dataType: 'BIGINT' }],
          mode,
        ),
      ).toEqual([{ name: 'B', type: 'BIGINT' }]);
    },
  );
  it('行拖拽排序返回新数组并忽略越界目标', () => {
    const rows = Object.freeze(['A', 'B', 'C']);
    expect(moveReferenceField(rows, 0, 2)).toEqual(['B', 'C', 'A']);
    expect(moveReferenceField(rows, 0, -1)).toEqual(rows);
    expect(rows).toEqual(['A', 'B', 'C']);
  });
  it('字段长度精度保留0和Long字符串，重复目标与未知类型明确校验', () => {
    expect(
      validateReferenceFields([
        { name: 'a', type: 'String', length: '9007199254740993', precision: 0 },
      ]),
    ).toEqual([]);
    expect(validateReferenceFields([{ name: 'a' }])).toContain(
      '第1行字段类型尚未确定，请手动确认',
    );
    expect(validateReferenceFields([{ name: 'a' }], false)).toEqual([]);
    expect(
      validateReferenceFields(
        [
          { name: 'a', alias: 'same' },
          { name: 'b', alias: 'same' },
        ],
        false,
      ),
    ).toContain('输出字段名称重复：same');
    expect(
      validateReferenceFields([
        { name: 'a', type: 'String', precision: '1.5' },
      ]),
    ).toContain('第1行字段精度应为非负整数');
  });
  it('kafka参数只能保存非敏感JSON对象，拒绝嵌套认证材料', () => {
    expect(parseReferenceParameters('{"fetch.min.bytes":1024}')).toEqual({
      'fetch.min.bytes': 1024,
    });
    expect(() => parseReferenceParameters('[]')).toThrow(/JSON对象/u);
    expect(() =>
      parseReferenceParameters('{"security":{"sasl.jaas.config":"redacted"}}'),
    ).toThrow(/认证参数/u);
    expect(() => parseReferenceParameters('{"password":"redacted"}')).toThrow(
      /认证参数/u,
    );
  });
});
