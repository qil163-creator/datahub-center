import { isVNode } from 'vue';

import { describe, expect, it } from 'vitest';

import {
  indexMaskedColumns,
  maskedColumnHint,
  renderMaskedColumnTitle,
} from './masked-column';

describe('masked column markers', () => {
  it('indexes masked columns by result key and skips empty entries', () => {
    const index = indexMaskedColumns([
      { name: 'phone', reason: 'RULE', categoryNames: ['手机号'] },
      { name: '' },
    ]);
    expect([...index.keys()]).toEqual(['phone']);
    expect(indexMaskedColumns(undefined).size).toBe(0);
  });

  it('explains the masking reason with category names', () => {
    expect(
      maskedColumnHint({
        name: 'phone',
        reason: 'RULE',
        categoryNames: ['手机号'],
      }),
    ).toBe('已脱敏（手机号）：命中分类脱敏规则');
    expect(maskedColumnHint({ name: 'x', reason: 'UNTRACEABLE' })).toContain(
      '无法追溯来源字段',
    );
    expect(maskedColumnHint({ name: 'x', reason: 'SOMETHING_NEW' })).toBe(
      '已脱敏：已按数据安全策略脱敏',
    );
  });

  it('only decorates masked columns', () => {
    expect(renderMaskedColumnTitle('id')).toBe('id');
    expect(
      isVNode(
        renderMaskedColumnTitle('phone', { name: 'phone', reason: 'RULE' }),
      ),
    ).toBe(true);
  });
});
