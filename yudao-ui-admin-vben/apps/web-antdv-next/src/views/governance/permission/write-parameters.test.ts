import { describe, expect, it } from 'vitest';

import { parseWriteParameters } from './data';

describe('写预检精确参数输入', () => {
  it('保留超大编号、小数、布尔文本和空值', () => {
    expect(
      parseWriteParameters(
        '["9007199254740993","999999999999999999.000001","false",null]',
      ),
    ).toEqual(['9007199254740993', '999999999999999999.000001', 'false', null]);
  });
  it.each([
    '[9007199254740993]',
    '[1.0000000000000001]',
    '[false]',
    '[{}]',
    '{"id":"1"}',
    '[',
  ])('拒绝有损或无效输入 %s', (value) => {
    expect(() => parseWriteParameters(value)).toThrow(/JSON/);
  });
  it('阻止超过服务端数量和长度上限的输入', () => {
    expect(() =>
      parseWriteParameters(
        JSON.stringify(Array.from({ length: 5001 }, () => '1')),
      ),
    ).toThrow(/JSON/);
    expect(() =>
      parseWriteParameters(JSON.stringify(['x'.repeat(20_001)])),
    ).toThrow(/JSON/);
  });
});
