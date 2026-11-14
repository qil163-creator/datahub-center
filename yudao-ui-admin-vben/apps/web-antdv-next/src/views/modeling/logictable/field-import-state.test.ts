import { describe, expect, it } from 'vitest';

import { newLogicField } from './editor-state';
import {
  codeTableField,
  indicatorField,
  logicTableField,
  mergeImportedFields,
  parsedField,
  sanitizeFieldName,
} from './field-import-state';

describe('field import rules', () => {
  it('sanitizes names into identifiers that start with a letter', () => {
    expect(sanitizeFieldName('Paid GMV-Month', 'ind')).toBe('paid_gmv_month');
    expect(sanitizeFieldName('123abc', 'ind')).toBe('ind_123abc');
    expect(sanitizeFieldName('', 'code_9')).toBe('code_9');
  });

  it('detaches identities and primary keys when copying from another logic table', () => {
    const copied = logicTableField(
      {
        fieldUid: 'other-model-uid',
        sourceAttrUid: 'src',
        fieldName: 'customer_id',
        pkFlag: true,
        fieldCategory: 'PRIMARY_KEY',
        sort: 3,
      },
      'dimension',
    );
    expect(copied.fieldUid).toBeUndefined();
    expect(copied.sourceAttrUid).toBeUndefined();
    expect(copied.sort).toBeUndefined();
    expect(copied.pkFlag).toBe(false);
    expect(copied.fieldCategory).toBe('ATTRIBUTE');
    expect(logicTableField({ fieldName: 'id', pkFlag: true }, 'application').pkFlag).toBe(true);
  });

  it('maps derived indicators to measure fields bound to the indicator', () => {
    const field = indicatorField({
      id: '9007199254740993',
      code: 'paid_gmv_m',
      name: '月支付金额',
      indicatorType: 'DERIVED',
      dataDomainId: 1,
      unit: '元',
      timePeriod: 'MONTH',
    });
    expect(field).toMatchObject({
      fieldName: 'paid_gmv_m',
      cnName: '月支付金额',
      indicatorId: '9007199254740993',
      dimensionName: '月支付金额',
      dataType: 'DECIMAL',
      dataPrecision: 20,
      dataScale: 4,
      statisticsPeriod: 'MONTH',
    });
  });

  it('maps code tables to fields bound to the code table', () => {
    expect(
      codeTableField({ id: 12, name: '性别', columnType: 'char', dataLength: 1 }),
    ).toMatchObject({
      fieldName: 'code_12',
      cnName: '性别',
      dataType: 'CHAR',
      dataLength: 1,
      codeTableId: 12,
      codeTableName: '性别',
    });
    expect(codeTableField({ id: 3, name: '行政区划' }).dataLength).toBe(64);
  });

  it('keeps the existing primary key for non-application tables when parsing SQL', () => {
    expect(parsedField({ fieldName: 'id', pkFlag: true }, 'fact').pkFlag).toBe(false);
    expect(parsedField({ fieldName: 'id', pkFlag: true }, 'application').pkFlag).toBe(true);
  });

  it('appends without duplicates and replaces everything but the locked key', () => {
    const existing = [
      newLogicField({ fieldName: 'order_id', pkFlag: true }),
      newLogicField({ fieldName: 'amount' }),
    ];
    const appended = mergeImportedFields(
      existing,
      [{ fieldName: 'AMOUNT' }, { fieldName: 'region' }],
      'fact',
    );
    expect(appended.fields.map((row) => row.fieldName)).toEqual([
      'order_id',
      'amount',
      'region',
    ]);
    expect(appended.skipped).toEqual(['AMOUNT']);
    expect(appended.added).toBe(1);
    expect(appended.fields[2]?._key).toBeTruthy();

    const replaced = mergeImportedFields(existing, [{ fieldName: 'region' }], 'fact', true);
    expect(replaced.fields.map((row) => row.fieldName)).toEqual(['order_id', 'region']);
    expect(
      mergeImportedFields(existing, [{ fieldName: 'region' }], 'application', true)
        .fields.map((row) => row.fieldName),
    ).toEqual(['region']);
  });
});
