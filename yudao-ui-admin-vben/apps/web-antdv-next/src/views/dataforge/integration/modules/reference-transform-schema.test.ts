import { describe, expect, it } from 'vitest';

import {
  compareReferenceDecimal,
  referenceCalculations,
  validateReferenceDerive,
  validateReferenceRanges,
  validateReferenceSequence,
  validateReferenceTransformRows,
} from './reference-transform-schema';

describe('转换表格规则校验', () => {
  it('计算类型逐个按真实参数个数校验，单参数操作不要求B/C，重复结果字段拒绝', () => {
    expect(referenceCalculations).toHaveLength(24);
    for (const operation of referenceCalculations) {
      const rule = {
        targetField: 'result',
        calcType: operation.value,
        valueType: 'DOUBLE',
        fieldA: 'A',
        fieldB: 'B',
        fieldC: 'C',
      };
      expect(validateReferenceTransformRows('calculator', [rule])).toEqual([]);
      expect(
        validateReferenceTransformRows('calculator', [
          {
            ...rule,
            [['fieldA', 'fieldB', 'fieldC'][operation.arity - 1]!]: '',
          },
        ]),
      ).toContain(
        `第1行请填写${['fieldA', 'fieldB', 'fieldC'][operation.arity - 1]}`,
      );
    }
    expect(
      validateReferenceTransformRows('calculator', [
        {
          targetField: 'result',
          calcType: 'ABS',
          fieldA: 'A',
          valueType: 'DOUBLE',
        },
      ]),
    ).toEqual([]);
    expect(
      validateReferenceTransformRows('calculator', [
        {
          targetField: 'result',
          calcType: 'ADD',
          fieldA: 'A',
          valueType: 'DOUBLE',
        },
      ]),
    ).toContain('第1行请填写fieldB');
    expect(
      validateReferenceTransformRows('calculator', [
        {
          targetField: 'result',
          calcType: 'eval',
          fieldA: 'A',
          valueType: 'STRING',
        },
      ]),
    ).toContain('第1行请选择计算类型');
    const rule = {
      targetField: 'result',
      calcType: 'STR_LEN',
      fieldA: 'A',
      valueType: 'INTEGER',
    };
    expect(
      validateReferenceTransformRows('calculator', [rule, rule]),
    ).toContain('存在重复字段或原值：result');
  });
  it('字段派生保留七种旧操作契约，常量允许空串，来源和位置参数明确校验', () => {
    expect(
      validateReferenceDerive({
        operateType: 'CONCAT',
        targetField: 'new',
        sourceFields: ['A', 'B'],
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'CONSTANT',
        targetField: 'new',
        constantValue: '',
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'EXPRESSION',
        targetField: 'new',
        expression: 'A-B',
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'HASH',
        targetField: 'new',
        sourceFields: ['A'],
        algorithm: 'SHA256',
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'SUBSTRING',
        targetField: 'new',
        sourceFields: ['A'],
        beginIndex: 1,
        length: 0,
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'REPLACE',
        targetField: 'new',
        sourceFields: ['A'],
        search: 'x',
        replacement: '',
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'REGEX_EXTRACT',
        targetField: 'new',
        sourceFields: ['A'],
        pattern: '(\\w+)',
        groupIndex: 0,
      }),
    ).toEqual([]);
    expect(
      validateReferenceDerive({
        operateType: 'SUBSTRING',
        targetField: 'new',
        sourceFields: ['A'],
        beginIndex: 0,
        length: 1,
      }),
    ).toContain('截取起始位置从1开始，长度须为非负整数');
    expect(
      validateReferenceDerive({
        operateType: 'REPLACE',
        targetField: 'new',
        sourceFields: ['A', 'B'],
        search: 'x',
      }),
    ).toContain('当前操作只能选择一个来源字段');
    expect(
      validateReferenceDerive({
        operateType: 'CONCAT',
        targetField: 'new',
        sourceFields: ['A', 'A'],
      }),
    ).toContain('来源字段不能重复');
    expect(
      validateReferenceDerive({
        operateType: 'HASH',
        targetField: 'new',
        sourceFields: ['A'],
        algorithm: 'unknown',
      }),
    ).toContain('请选择哈希算法');
    expect(
      validateReferenceDerive({
        operateType: 'EXPRESSION',
        targetField: 'new',
        expression: '',
      }),
    ).toContain('请填写表达式');
  });
  it('十进制边界保留Long与小数精度，支持负数和科学计数法', () => {
    expect(
      compareReferenceDecimal(
        '9007199254740993.0000000001',
        '9007199254740993.0000000000',
      ),
    ).toBe(1);
    expect(compareReferenceDecimal('-1.5', '-1.50e0')).toBe(0);
    expect(compareReferenceDecimal('1e-10', '0.0000000002')).toBe(-1);
    expect(() => compareReferenceDecimal('NaN', '1')).toThrow(/数值格式/u);
    expect(() => compareReferenceDecimal('1e1001', '1')).toThrow(/数值指数/u);
  });
  it('范围含下限不含上界，相邻边界可用；拒绝反向、重叠和非数值边界', () => {
    expect(
      validateReferenceRanges([
        { min: '', max: '9007199254740993.01', value: '低' },
        { min: '9007199254740993.01', max: '', value: 0 },
      ]),
    ).toEqual([]);
    expect(
      validateReferenceRanges([{ min: '5', max: '5', value: 'x' }]),
    ).toContain('第1行下限必须小于上界');
    expect(
      validateReferenceRanges([
        { min: '0', max: '10', value: 'x' },
        { min: '9', max: '20', value: 'y' },
      ]),
    ).toContain('第1行与第2行范围重叠');
    expect(
      validateReferenceRanges([{ min: 'bad', max: '', value: 'x' }]),
    ).toContain('第1行范围边界不是有效数值');
  });
  it('序列参数按Long准确校验，拒绝有损number、越界、零步长与过小上限', () => {
    expect(
      validateReferenceSequence({
        targetField: 'ID',
        start: '9007199254740993',
        step: '1',
        max: '9223372036854775807',
      }),
    ).toEqual([]);
    expect(
      validateReferenceSequence({
        targetField: 'ID',
        start: '9223372036854775808',
        step: '1',
        max: '0',
      }),
    ).toEqual(['序列参数超出Long范围']);
    expect(
      validateReferenceSequence({
        targetField: 'ID',
        start: Number.MAX_SAFE_INTEGER + 1,
        step: 1,
        max: 0,
      }),
    ).toEqual(['起始值、递增幅度、最大值必须为无损整数']);
    expect(
      validateReferenceSequence({
        targetField: 'ID',
        start: '1',
        step: '0',
        max: '0',
      }),
    ).toEqual(['递增幅度必须大于0']);
    expect(
      validateReferenceSequence({
        targetField: 'ID',
        start: '5',
        step: '1',
        max: '4',
      }),
    ).toEqual(['最大值应为0（不限）或不小于起始值']);
    expect(
      validateReferenceSequence({ targetField: 'UUID', mode: 'UUID' }),
    ).toEqual([]);
  });
  it('新增之前反馈重复目标和空行，校验替换来源、搜索内容与填充配置', () => {
    expect(
      validateReferenceTransformRows('value-map', [
        { from: ' A ', to: '' },
        { from: 'A', to: '' },
      ]),
    ).toEqual([]);
    expect(
      validateReferenceTransformRows('sort-records', [
        { field: 'ID', order: 'ASC' },
        { field: 'ID', order: 'DESC' },
      ]),
    ).toContain('存在重复字段或原值：ID');
    expect(
      validateReferenceTransformRows('set-field-value', [
        { targetField: 'A', sourceField: '' },
      ]),
    ).toContain('第1行替换字段不能为空');
    expect(
      validateReferenceTransformRows('string-replace', [
        { inputField: 'A', search: '' },
      ]),
    ).toContain('第1行搜索内容不能为空');
    expect(
      validateReferenceTransformRows('string-operation', [
        {
          inputField: 'A',
          padDirection: 'LEFT',
          padCharacter: '',
          padLength: 3,
        },
      ]),
    ).toContain('第1行请填写填充字符和有效长度');
    expect(
      validateReferenceTransformRows('string-operation', [
        { inputField: 'A', outputField: 'B' },
        { inputField: 'A', outputField: 'C' },
      ]),
    ).toEqual([]);
  });
});
