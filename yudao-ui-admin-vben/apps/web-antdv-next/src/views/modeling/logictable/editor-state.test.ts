import { describe, expect, it } from 'vitest';

import { logicFieldPayload, logicNamingParts, newLogicField, validateLogicFields } from './editor-state';

describe('逻辑模型编辑边界', () => {
  it.each(['DECIMAL', 'NUMERIC', 'NUMBER'])('定点数 %s 明确校验和提交总精度，不将旧属性长度当成精度', (dataType) => {
    const field = newLogicField({ fieldUid: 'amount', fieldName: 'amount', cnName: '金额', dataType, dataLength: 18, dataScale: 2 });
    expect(validateLogicFields([field])).toContain('总精度');
    field.dataPrecision = 38;
    expect(validateLogicFields([field])).toBe('');
    expect(logicFieldPayload([field])[0]).toMatchObject({ dataPrecision: 38, dataScale: 2, dataLength: 18 });
    field.dataScale = 39;
    expect(validateLogicFields([field])).toContain('总精度');
    field.dataScale = 0;
    expect(validateLogicFields([field])).toBe('');
    field.dataPrecision = 18.5;
    expect(validateLogicFields([field])).toContain('整数总精度');
  });

  it('定点数要求明确小数位数，字符长度保持原有语义', () => {
    const decimal = newLogicField({ fieldName: 'amount', cnName: '金额', dataType: 'DECIMAL', dataPrecision: 18 });
    expect(validateLogicFields([decimal])).toContain('小数位数');
    const text = newLogicField({ fieldName: 'name', cnName: '名称', dataType: 'VARCHAR', dataLength: 64 });
    expect(validateLogicFields([text])).toBe('');
    expect(logicFieldPayload([text])[0]).toMatchObject({ dataLength: 64 });
    expect(logicFieldPayload([text])[0]).not.toHaveProperty('dataPrecision');
  });

  it('字段编号与精确默认值不经过数字转换，主键强制非空', () => {
    const value = newLogicField({
      fieldUid: 'stable-field', fieldName: 'amount', cnName: '金额',
      dataType: 'DECIMAL', defaultValue: '9007199254740993.123456789',
      standardId: '9007199254740993', pkFlag: true, nullableFlag: true,
    });
    const [payload] = logicFieldPayload([value]);
    expect(payload).toMatchObject({ fieldUid: 'stable-field', standardId: '9007199254740993', defaultValue: '9007199254740993.123456789', pkFlag: true, nullableFlag: false, uniqueFlag: true, fieldCategory: 'PRIMARY_KEY', sort: 1 });
    expect(payload).not.toHaveProperty('_key');
  });

  it('字段去重忽略大小写，字段编辑保留稳定标识', () => {
    const original = newLogicField({ fieldUid: 'field-a', fieldName: 'station_id', cnName: '测站' });
    const duplicate = newLogicField({ fieldName: 'STATION_ID', cnName: '重复测站' });
    expect(newLogicField(original)._key).toBe('field-a');
    expect(validateLogicFields([original, duplicate])).toContain('重复');
    expect(validateLogicFields([newLogicField({ fieldName: '9invalid', cnName: '非法' })])).toContain('须以字母开头');
  });

  it('现有时效字典编码与业务板块命名规则一致', () => {
    const domain = { namingRules: [{ tableType: 'FACT_EVENT' as const, prefix: 'dwd_', daySuffix: '_di', hourSuffix: '_hi', minuteSuffix: '_mi', includeSubjectAbbreviation: true }] };
    expect(logicNamingParts('fact', domain, 'rain_collect', 'EVENT', 'hydrology', '2', 1)).toEqual({ prefix: 'DWD_', text: 'HYDROLOGY_RAIN_COLLECT_HI' });
    expect(logicNamingParts('fact', domain, 'rain_collect', 'EVENT', 'hydrology', '3', 2)).toEqual({ prefix: 'dwd_', text: 'hydrology_rain_collect_mi' });
    expect(logicNamingParts('fact', domain, 'rain_collect', 'EVENT', 'hydrology', '1', 2).text).toBe('hydrology_rain_collect_di');
  });
});
