import { describe, expect, it } from 'vitest';

import {
  kafkaInputFieldTypes,
  validateKafkaInputPaths,
} from './kafka-input-fields';

describe('kafka输入字段契约', () => {
  it('提供参考站六种原始类型值，编辑必填解析值，历史省略路径仍按原协议保留', () => {
    expect(kafkaInputFieldTypes.map((item) => item.value)).toEqual([
      'STRING',
      'BOOL',
      'BYTES',
      'DATE',
      'DOUBLE',
      'LONG',
    ]);
    expect(validateKafkaInputPaths([{ name: 'ID', type: 'LONG' }])).toEqual([]);
    expect(
      validateKafkaInputPaths([{ name: 'ID', type: 'LONG' }], true),
    ).toEqual(['第1行JSON解析值不能为空']);
    expect(
      validateKafkaInputPaths([{ name: 'ID', key: 'info.aga' }], true),
    ).toEqual([]);
    expect(
      validateKafkaInputPaths([{ name: 'ID', key: ' '.repeat(256) }], true),
    ).toEqual(['第1行JSON解析值不能为空']);
    expect(
      validateKafkaInputPaths([{ name: 'ID', key: 'a'.repeat(257) }]),
    ).toEqual(['第1行JSON解析值不能超过256个字符']);
  });
});
