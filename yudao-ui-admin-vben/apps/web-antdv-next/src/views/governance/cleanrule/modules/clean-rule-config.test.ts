import { describe, expect, it } from 'vitest';

import {
  cleanRuleConfigFromExample,
  formatCleanRuleConfig,
  parseCleanRuleConfig,
} from './clean-rule-config';

describe('governance clean-rule parameter editor contract', () => {
  it('keeps the persisted parameter object separate from sample field bindings', () => {
    expect(
      cleanRuleConfigFromExample(
        '{"columns":["amount"],"scale":2}',
      ),
    ).toBe(`{
  "scale": 2
}`);
    expect(
      cleanRuleConfigFromExample(
        '{"columns":["age"],"min":"0","max":"150","handleType":1}',
      ),
    ).toBe(`{
  "min": "0",
  "max": "150",
  "handleType": 1
}`);
  });

  it('round-trips exact text values and rejects non-object config', () => {
    const config = parseCleanRuleConfig('{"min":"9007199254740993.123456789"}');
    expect(config.min).toBe('9007199254740993.123456789');
    expect(parseCleanRuleConfig(formatCleanRuleConfig(config))).toEqual(config);
    expect(() => parseCleanRuleConfig('[]')).toThrow('JSON 对象');
    expect(() => parseCleanRuleConfig('{"min":9007199254740993,"max":"2"}')).toThrow('JSON 字符串');
    expect(() => parseCleanRuleConfig('{"columns":["client-field"]}')).toThrow('字段绑定');
  });
});
