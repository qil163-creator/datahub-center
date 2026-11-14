import type { EditableLogicKind, LogicFieldRow } from './editor-state';

import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { ModelingModelApi } from '#/api/modeling/model';

import { newLogicField } from './editor-state';

type Field = ModelingModelApi.ModelField;

/** 转为合法英文字段名：小写、非字母数字转下划线、必须以字母开头。 */
export function sanitizeFieldName(raw: string | undefined, fallback: string) {
  const cleaned = (raw || '')
    .trim()
    .toLowerCase()
    .replaceAll(/[^a-z0-9_]+/g, '_')
    .replaceAll(/_+/g, '_')
    .replaceAll(/^_+|_+$/g, '');
  const name = /^[a-z]/.test(cleaned) ? cleaned : `${fallback}${cleaned ? `_${cleaned}` : ''}`;
  return name.slice(0, 64);
}

/** 后端字段还会带回来源属性标识，前端类型未声明，引入时一并去掉。 */
type FieldWithSource = Field & { sourceAttrUid?: string };

/** 引入的字段一律重新生成稳定标识；其他模型的标识不能带进当前设计。 */
function detach(field: FieldWithSource): Field {
  const { fieldUid: _uid, sourceAttrUid: _source, sort: _sort, ...rest } = field;
  return rest;
}

/** 从其他逻辑表引入：沿用字段定义，主键在当前表另行确定。 */
export function logicTableField(field: Field, kind: EditableLogicKind): Field {
  const copy = detach(field);
  if (kind !== 'application') {
    copy.pkFlag = false;
    if (copy.fieldCategory === 'PRIMARY_KEY') copy.fieldCategory = 'ATTRIBUTE';
  }
  return copy;
}

const INDICATOR_TYPES: Record<string, Partial<Field>> = {
  BIGINT: { dataType: 'BIGINT' },
  DATE: { dataType: 'DATE' },
  DATETIME: { dataType: 'DATETIME' },
  DECIMAL: { dataType: 'DECIMAL', dataPrecision: 20, dataScale: 4 },
  DOUBLE: { dataType: 'DOUBLE' },
};

/** 引用派生指标：每个指标生成一个度量字段，并在“对应对象/指标”列显示指标名。 */
export function indicatorField(option: IndicatorModelApi.Option): Field {
  return {
    fieldName: sanitizeFieldName(option.code, 'ind'),
    cnName: option.name,
    comment: option.unit ? `${option.name}（${option.unit}）` : option.name,
    fieldCategory: 'NORMAL',
    pkFlag: false,
    nullableFlag: true,
    indicatorId: option.id,
    dimensionName: option.name,
    statisticsPeriod: option.timePeriod,
    measureUnit: option.unit,
    ...(INDICATOR_TYPES[option.resultDataType || 'DECIMAL'] ||
      INDICATOR_TYPES.DECIMAL),
  };
}

/** 从代码表引入：每个代码表生成一个绑定代码表的字段，类型与长度取代码表定义。 */
export function codeTableField(
  codeSet: GovernanceStandardCodeSetApi.StandardCodeSet,
): Field {
  const dataType = (codeSet.columnType || 'VARCHAR').toUpperCase();
  return {
    fieldName: sanitizeFieldName(undefined, `code_${String(codeSet.id)}`),
    cnName: codeSet.name,
    comment: codeSet.description || codeSet.name,
    dataType,
    dataLength: codeSet.dataLength ?? (dataType === 'VARCHAR' ? 64 : undefined),
    dataScale: codeSet.dataScale,
    fieldCategory: 'NORMAL',
    pkFlag: false,
    nullableFlag: true,
    codeTableId: codeSet.id,
    codeTableName: codeSet.name,
  };
}

/** SQL 解析的字段：非应用表的主键沿用当前表已有主键。 */
export function parsedField(field: Field, kind: EditableLogicKind): Field {
  const copy = detach(field);
  copy.fieldCategory = kind === 'application' ? 'NORMAL' : 'ATTRIBUTE';
  if (kind !== 'application') copy.pkFlag = false;
  return copy;
}

/**
 * 合并引入的字段。追加时英文名重复（忽略大小写）的跳过；替换时保留非应用表的主键行，其余全部替换。
 *
 * @returns 合并后的字段，以及因重名被跳过的字段名
 */
export function mergeImportedFields(
  existing: LogicFieldRow[],
  incoming: Field[],
  kind: EditableLogicKind,
  replace = false,
) {
  const kept = replace
    ? existing.filter((row) => kind !== 'application' && row.pkFlag)
    : existing;
  const names = new Set(
    kept.map((row) => (row.fieldName || '').trim().toLowerCase()),
  );
  const skipped: string[] = [];
  const added: LogicFieldRow[] = [];
  for (const field of incoming) {
    const name = (field.fieldName || '').trim().toLowerCase();
    if (!name || names.has(name)) {
      skipped.push(field.fieldName || '');
      continue;
    }
    names.add(name);
    added.push(newLogicField(field));
  }
  return { fields: [...kept, ...added], added: added.length, skipped };
}
