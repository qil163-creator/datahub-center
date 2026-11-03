import { describe, expect, it } from 'vitest';

import {
  ensureStableFieldUids,
  generateFieldUid,
  isDecimalField,
  needsDecimalConfirmation,
} from './field-state';

describe('当前设计稳定字段标识', () => {
  it('删除、重排和重复规范化后仍保留已有字段标识', () => {
    let sequence = 0;
    const createUid = () => `generated-${++sequence}`;
    const rows = [
      { fieldName: 'customer_id', fieldUid: 'server-uid' },
      { fieldName: 'customer_name' },
      { fieldName: 'created_at' },
    ];
    ensureStableFieldUids(rows, createUid);
    const originalKeys = rows.map((row) => row.fieldUid);
    rows.reverse();
    rows.splice(1, 1);
    ensureStableFieldUids(rows, createUid);
    expect(rows.map((row) => row.fieldUid)).toEqual([
      originalKeys[2],
      originalKeys[0],
    ]);
    expect(sequence).toBe(2);
  });

  it('保留首个服务端标识，修复空白、重复和生成碰撞的标识', () => {
    const rows = [
      { fieldName: 'id', fieldUid: ' stable ' },
      { fieldName: 'name', fieldUid: 'stable' },
      { fieldName: 'created_at', fieldUid: ' ' },
    ];
    const candidates = ['stable', 'name-uid', 'name-uid', 'time-uid'];
    ensureStableFieldUids(rows, () => candidates.shift()!);
    expect(rows.map((row) => row.fieldUid)).toEqual([
      'stable',
      'name-uid',
      'time-uid',
    ]);
    expect(candidates).toEqual([]);
  });

  it('新增字段得到互不相同的非空标识', () => {
    const first = generateFieldUid();
    const second = generateFieldUid();
    expect(first).toBeTruthy();
    expect(second).toBeTruthy();
    expect(first).not.toBe(second);
  });
});

describe('定点数字段的明确精度', () => {
  it.each(['decimal', 'NUMERIC', 'number(18,2)', 'decimalv3(20,4)'])(
    '识别 %s 并要求明确总精度和小数位数',
    (dataType) => {
      expect(isDecimalField({ dataType })).toBe(true);
      expect(needsDecimalConfirmation({ dataType, dataPrecision: 18 })).toBe(
        true,
      );
      expect(
        needsDecimalConfirmation({ dataType, dataPrecision: 18, dataScale: 0 }),
      ).toBe(false);
      expect(
        needsDecimalConfirmation({
          dataType,
          dataPrecision: 18,
          dataScale: 19,
        }),
      ).toBe(true);
    },
  );

  it('不把字符长度或已有值猜作小数位，也不改写字段', () => {
    const field = { dataType: 'decimal', dataLength: 18, dataPrecision: 2 };
    expect(needsDecimalConfirmation(field)).toBe(true);
    expect(field).toEqual({
      dataType: 'decimal',
      dataLength: 18,
      dataPrecision: 2,
    });
    expect(needsDecimalConfirmation({ dataType: 'varchar' })).toBe(false);
    expect(isDecimalField({ dataType: 'decimal_value' })).toBe(false);
  });
});
