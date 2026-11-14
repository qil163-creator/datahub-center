import { describe, expect, it } from 'vitest';

import {
  compileExpression,
  Decimal,
  toFieldValue,
  validateExpression,
} from './fill-expression';

function evalRow(text: string, row: Record<string, unknown>) {
  const value = compileExpression(text, 'ROW').evaluate({
    field: (code) => row[code],
  });
  return value instanceof Decimal ? value.toString() : value;
}

describe('填报表达式（与后端 FillExpression 同语义）', () => {
  it('算术使用精确小数，不经过浮点', () => {
    expect(evalRow('a + b', { a: '0.1', b: '0.2' })).toBe('0.3');
    expect(evalRow('price * qty - discount', { discount: '0.97', price: '19.99', qty: 3 })).toBe('59.00');
    expect(evalRow('ROUND(10 / 3, 2)', {})).toBe('3.33');
    expect(evalRow('-a % 3', { a: 7 })).toBe('-1');
    expect(evalRow('ROUND(2.345 * 3, 2)', {})).toBe('7.04');
  });

  it('空值传播，除数为零结果为空', () => {
    expect(evalRow('a * b', { a: '2' })).toBeNull();
    expect(evalRow('a * b', { a: '2', b: '' })).toBeNull();
    expect(evalRow('a / b', { a: 1, b: 0 })).toBeNull();
    expect(evalRow('COALESCE(b, 0) + a', { a: 2 })).toBe('2');
    expect(evalRow('MAX(a, b, c)', { a: 1, c: 5 })).toBe('5');
  });

  it('比较、逻辑与函数', () => {
    expect(evalRow('end_date >= start_date', { end_date: '2026-01-10', start_date: '2026-01-02' })).toBe(true);
    expect(evalRow('total = a + b AND NOT (a < 0)', { a: 4, b: 6, total: '10.00' })).toBe(true);
    expect(evalRow('a > 1 || b > 1', { a: 0 })).toBeNull();
    expect(evalRow('a > 1 AND b > 1', { a: 0 })).toBe(false);
    expect(evalRow("IF(type = '收入', amount, -amount)", { amount: 5, type: '支出' })).toBe('-5');
    expect(evalRow('LEN(name) <= 4', { name: '张三' })).toBe(true);
    expect(evalRow("name == 'it''s'", { name: "it's" })).toBe(true);
  });

  it('范围限制函数与字段用法，并报告未知字段', () => {
    expect(validateExpression('SUM(a)', 'FORMULA')).toContain('表级');
    expect(validateExpression('a > 0', 'TABLE')).toContain('汇总函数');
    expect(validateExpression('PREV_SUM(a) > 0', 'TABLE')).toContain('跨期');
    expect(validateExpression("exec('x')", 'ROW')).toContain('不支持的函数');
    expect(validateExpression('a +', 'ROW')).toContain('不完整');
    expect(validateExpression('a * missing', 'FORMULA', ['a'])).toContain('missing');
    expect(validateExpression('SUM(a) <= PREV_SUM(a) * 1.3', 'PERIOD', ['a'])).toBeUndefined();
  });

  it('表级与跨期汇总', () => {
    const rule = compileExpression(
      'ABS(SUM(total) - PREV_SUM(total)) <= PREV_SUM(total) * 0.3',
      'PERIOD',
    );
    const column = () => [10, 9, null];
    expect(rule.evaluate({ column, previousColumn: () => [10] })).toBe(false);
    expect(rule.evaluate({ column, previousColumn: () => null })).toBeNull();
    expect(compileExpression('COUNT(total) = 2', 'TABLE').evaluate({ column })).toBe(true);
  });

  it('结果按字段小数位舍入为十进制字符串', () => {
    const value = compileExpression('price * qty', 'FORMULA').evaluate({
      field: (code) => ({ price: '2.345', qty: 3 })[code],
    });
    expect(toFieldValue(value, 1, 2)).toBe('7.04');
    expect(toFieldValue(value, 5)).toBe('7');
    expect(toFieldValue(null, 1, 2)).toBe('');
  });
});
