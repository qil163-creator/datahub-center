import { describe, expect, it } from 'vitest';

import { etlBatchConfigError, etlBatchCursor } from './etl-batch';

describe('受控 ETL 批次服务契约', () => {
  it('游标和批次 ID 不经过有损数字转换', () => {
    expect(etlBatchCursor('9007199254740993')).toBe('9007199254740993');
    expect(etlBatchCursor(undefined)).toBe('0');
    expect(() => etlBatchCursor(Number.MAX_SAFE_INTEGER + 1)).toThrow(/游标/);
    for (const value of ['-1', '1.2', '1e3', '9223372036854775808', '01']) expect(() => etlBatchCursor(value)).toThrow(/游标/);
    expect(etlBatchConfigError({ configType: 'ETL_BATCH', etlBatchId: '9007199254740993', apiType: 'QUERY', resultFormat: 'LIST', authType: 'SIGN', cacheEnabled: 0 })).toBeUndefined();
  });
  it('拒绝匿名、缓存和写入等不支持的批次模式', () => {
    const api = { configType: 'ETL_BATCH', etlBatchId: '77', apiType: 'QUERY', resultFormat: 'LIST', authType: 'APPKEY', cacheEnabled: 0 };
    for (const change of [{ authType: 'NONE' }, { apiType: 'INSERT' }, { cacheEnabled: 1 }, { resultFormat: 'PAGE' }]) expect(etlBatchConfigError({ ...api, ...change })).toBeTruthy();
  });
});
