import type { LogicTableKind } from './config';

import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingModelApi } from '#/api/modeling/model';

import { isDecimalField } from '../modelcommon/field-state';

export type EditableLogicKind = Extract<
  LogicTableKind,
  'application' | 'dimension' | 'fact'
>;

export interface LogicFieldRow extends ModelingModelApi.ModelField {
  _key: string;
  fieldCategory?: string;
  dimensionId?: number | string;
  dimensionName?: string;
  dataClassificationId?: number | string;
  dataClassificationName?: string;
  dataGrade?: string;
  codeTableId?: number | string;
  codeTableName?: string;
  uniqueFlag?: boolean | null;
}

export const LOGIC_FIELD_TYPES = [
  'TINYINT',
  'INTEGER',
  'BIGINT',
  'DECIMAL',
  'NUMERIC',
  'FLOAT',
  'DOUBLE',
  'NUMBER',
  'CHAR',
  'VARCHAR',
  'VARCHAR2',
  'TEXT',
  'DATE',
  'TIMESTAMP',
  'DATETIME',
  'BOOLEAN',
].map((value) => ({ label: value, value }));

export const LOGIC_FIELD_CATEGORIES = [
  { value: 'ATTRIBUTE', label: '属性' },
  { value: 'DIMENSION', label: '维度' },
  { value: 'MEASURE', label: '度量' },
  { value: 'TIME', label: '时间' },
];

export function numericField(type?: string) {
  return ['DECIMAL', 'DOUBLE', 'FLOAT', 'NUMBER', 'NUMERIC'].includes(
    String(type).toUpperCase(),
  );
}

export function newLogicField(
  field: ModelingModelApi.ModelField & Partial<LogicFieldRow> = {},
): LogicFieldRow {
  return {
    dataType: 'VARCHAR',
    fieldCategory: 'ATTRIBUTE',
    pkFlag: false,
    nullableFlag: true,
    ...field,
    _key: field.fieldUid || globalThis.crypto.randomUUID(),
  };
}

/** 字段默认值保持字符串，避免数值类型默认值经过浮点转换。 */
export function validateLogicFields(fields: LogicFieldRow[]) {
  if (fields.length === 0) return '请添加属性字段';
  const names = new Set<string>();
  for (const [index, field] of fields.entries()) {
    const label = `第 ${index + 1} 行`;
    const name = field.fieldName?.trim() || '';
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(name))
      return `${label}字段英文名称须以字母开头，仅包含字母、数字和下划线`;
    if (names.has(name.toLowerCase())) return `字段英文名称 ${name} 重复`;
    names.add(name.toLowerCase());
    if (!field.cnName?.trim()) return `${label}请输入字段中文名称`;
    if (!field.dataType) return `${label}请选择数据类型`;
    if (isDecimalField(field)) {
      if (
        !Number.isInteger(field.dataPrecision) ||
        (field.dataPrecision ?? 0) <= 0
      )
        return `${label}请输入大于 0 的整数总精度`;
      if (
        !Number.isInteger(field.dataScale) ||
        (field.dataScale ?? -1) < 0 ||
        field.dataScale! > field.dataPrecision!
      )
        return `${label}小数位数须为 0 到总精度之间的整数`;
    }
    if (
      !isDecimalField(field) &&
      field.dataLength !== undefined &&
      field.dataLength !== null &&
      field.dataLength <= 0
    )
      return `${label}属性长度必须大于 0`;
    if (
      field.dataScale !== undefined &&
      field.dataScale !== null &&
      field.dataScale < 0
    )
      return `${label}小数位数不能小于 0`;
    if (
      !isDecimalField(field) &&
      numericField(field.dataType) &&
      field.dataLength &&
      (field.dataScale || 0) > field.dataLength
    )
      return `${label}小数位数不能大于属性长度`;
  }
  return '';
}

export function logicFieldPayload(fields: LogicFieldRow[]) {
  return fields.map(({ _key, ...field }, index) => ({
    ...field,
    fieldUid: field.fieldUid || _key,
    fieldName: field.fieldName?.trim(),
    cnName: field.cnName?.trim(),
    nullableFlag: field.pkFlag ? false : field.nullableFlag !== false,
    uniqueFlag: field.pkFlag ? true : field.uniqueFlag,
    fieldCategory: field.pkFlag ? 'PRIMARY_KEY' : field.fieldCategory,
    sort: index + 1,
  }));
}

/** 命名规则来自所选数据板块，前缀与用户输入分开维护。 */
export function logicNamingParts(
  kind: EditableLogicKind,
  domain: ModelingDataDomainApi.DataDomain | undefined,
  entityCode: string,
  entitySubtype: string,
  subjectCode: string,
  timeliness: string,
  tableCase: number,
) {
  let ruleType = entitySubtype === 'PROCESS' ? 'FACT_PROCESS' : 'FACT_EVENT';
  if (kind === 'dimension') ruleType = 'DIM';
  else if (kind === 'application') ruleType = 'ADS';
  const rule = domain?.namingRules?.find((item) => item.tableType === ruleType);
  const prefixes = { dimension: 'dim_', fact: 'dwd_', application: 'ads_' };
  const prefix = rule?.prefix || prefixes[kind];
  let stem = entityCode;
  if (kind === 'fact' && rule?.includeSubjectAbbreviation && subjectCode)
    stem = `${subjectCode}_${stem}`;
  let suffix = rule?.daySuffix;
  if (kind === 'application') suffix = '';
  else if (timeliness === 'HOUR' || timeliness === '2')
    suffix = rule?.hourSuffix;
  else if (timeliness === 'MINUTE' || timeliness === '3')
    suffix = rule?.minuteSuffix;
  const text = `${stem}${suffix || ''}`;
  return {
    prefix: tableCase === 2 ? prefix.toLowerCase() : prefix.toUpperCase(),
    text: tableCase === 2 ? text.toLowerCase() : text.toUpperCase(),
  };
}
