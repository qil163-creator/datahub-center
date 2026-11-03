import { describe, expect, it } from 'vitest';

import {
  hasDatabaseMetadata,
  isConnectionTypeSelectable,
} from './connection-capabilities';

describe('独立连接能力门禁', () => {
  it('连接能力 CONNECT 可用时可创建连接，不要求元数据能力', () => {
    expect(
      isConnectionTypeSelectable({
        capabilities: {
          CONNECT: { status: 'DECLARED', reason: '' },
          METADATA: { status: 'UNSUPPORTED', reason: '' },
        },
      }),
    ).toBe(true);
  });
  it('连接能力 CONNECT 明确不可用时不回退到 METADATA，旧目录缺 CONNECT 时兼容', () => {
    expect(
      isConnectionTypeSelectable({
        capabilities: {
          CONNECT: { status: 'UNSUPPORTED', reason: '' },
          METADATA: { status: 'VERIFIED', reason: '' },
        },
      }),
    ).toBe(false);
    expect(
      isConnectionTypeSelectable({
        capabilities: { METADATA: { status: 'DECLARED', reason: '' } },
      }),
    ).toBe(true);
    expect(isConnectionTypeSelectable({})).toBe(false);
  });
  it('连接通过不开放预览和监控，原生类型缺能力字段时安全关闭', () => {
    expect(
      hasDatabaseMetadata({
        typeCode: 'NEO4J',
        capabilities: { CONNECT: { status: 'VERIFIED', reason: '' } },
      }),
    ).toBe(false);
    expect(
      hasDatabaseMetadata({
        typeCode: 'MYSQL',
        capabilities: { METADATA: { status: 'UNSUPPORTED', reason: '' } },
      }),
    ).toBe(false);
    expect(hasDatabaseMetadata({ typeCode: 'MYSQL' })).toBe(true);
    expect(hasDatabaseMetadata({ typeCode: 'GBASE8C' })).toBe(false);
  });
});
