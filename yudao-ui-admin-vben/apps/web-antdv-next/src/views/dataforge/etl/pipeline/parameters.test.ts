import { describe, expect, it } from 'vitest';

import {
  compareParameterNumbers,
  initialParameterValues,
  parameterConstraintText,
  parameterDefinitionIssues,
  resolveParameterValues,
} from './parameters';

describe('etl类型化参数无损校验', () => {
  it('历史服务端null可选项视为未设置，实际参数仍必须为字符串', () => {
    const definitions = JSON.parse(
      '[{"name":"legacy","type":null,"required":null,"defaultValue":null,"min":null,"max":null,"minLength":null,"maxLength":null},{"name":"integer","type":"INTEGER","defaultValue":null,"min":null,"max":null}]',
    );
    expect(parameterDefinitionIssues(definitions)).toEqual([]);
    expect(initialParameterValues(definitions)).toEqual({});
    expect(parameterConstraintText(definitions[0])).toBe('STRING · 可选');
    expect(resolveParameterValues(definitions, {})).toEqual({
      parameters: {},
      issues: [],
    });
    expect(
      resolveParameterValues(definitions, {
        legacy: '',
        integer: '9007199254740993',
      }),
    ).toEqual({
      parameters: { legacy: '', integer: '9007199254740993' },
      issues: [],
    });
    expect(
      resolveParameterValues(definitions, JSON.parse('{"integer":null}'))
        .issues,
    ).toEqual(['integer：参数值必须为字符串']);
    expect(
      resolveParameterValues([{ ...definitions[0], required: true }], {})
        .issues,
    ).toEqual(['legacy：必填参数不能为空']);
  });
  it('合法参数名称不会读取或修改JavaScript对象原型', () => {
    expect(resolveParameterValues([{ name: 'constructor' }], {})).toEqual({
      parameters: {},
      issues: [],
    });
    const values = Object.fromEntries([
      ['__proto__', 'literal'],
      ['constructor', 'text'],
    ]);
    expect(
      resolveParameterValues(
        [{ name: '__proto__' }, { name: 'constructor' }],
        values,
      ),
    ).toEqual({ parameters: values, issues: [] });
    expect(Object.prototype).not.toHaveProperty('literal');
  });
  it('比较超安全整数与精确小数，不舍入相邻值', () => {
    expect(
      compareParameterNumbers('9007199254740993', '9007199254740992'),
    ).toBe(1);
    expect(
      compareParameterNumbers(
        '-9007199254740993.0001',
        '-9007199254740993.0000',
      ),
    ).toBe(-1);
    expect(compareParameterNumbers('+000.00', '-0')).toBe(0);
    expect(compareParameterNumbers('0.00000000000000000001', '0')).toBe(1);
  });
  it('原样传输数值参数及边界，布尔规范化，保留显式空字符串', () => {
    const result = resolveParameterValues(
      [
        {
          name: 'id',
          type: 'INTEGER',
          min: '9007199254740993',
          required: true,
        },
        {
          name: 'amount',
          type: 'DECIMAL',
          defaultValue: '1000000000000000000.123456789',
        },
        { name: 'enabled', type: 'BOOLEAN' },
        { name: 'empty' },
        { name: 'optional' },
      ],
      { id: '9007199254740993', enabled: 'TRUE', empty: '' },
    );
    expect(result).toEqual({
      parameters: {
        id: '9007199254740993',
        amount: '1000000000000000000.123456789',
        enabled: 'true',
        empty: '',
      },
      issues: [],
    });
  });
  it('缺失、显式空串、范围、未知参数与指数输入不能绕过校验', () => {
    const result = resolveParameterValues(
      [
        { name: 'required', required: true },
        { name: 'integer', type: 'INTEGER' },
        { name: 'decimal', type: 'DECIMAL', min: '0.100000000000000000001' },
        { name: 'exponent', type: 'DECIMAL' },
        { name: 'text', minLength: 1 },
      ],
      { integer: '', decimal: '0.1', exponent: '1e3', text: '', unknown: 'x' },
    );
    expect(result.issues).toHaveLength(6);
  });
  it('不接受重复名称、错用约束、无界数字或倒置范围', () => {
    const issues = parameterDefinitionIssues([
      { name: 'x', type: 'BOOLEAN', min: '0' },
      { name: 'x', maxLength: 4097 },
      { name: 'numeric', type: 'DECIMAL', min: '2', max: '1' },
      { name: 'bound', type: 'INTEGER', max: '1'.repeat(129) },
    ]);
    expect(issues).toHaveLength(5);
  });
});
