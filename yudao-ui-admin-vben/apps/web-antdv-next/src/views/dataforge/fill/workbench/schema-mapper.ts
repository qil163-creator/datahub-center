/**
 * 模板版本 fieldSchema（服务端固化 JSON）→ 编辑器领域字段模型
 */
import type {
  FillFieldSchema,
  FillFormula,
  FillLayout,
  FillRowData,
} from './types';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

interface ServerField {
  dictOptions?: string[];
  editable?: boolean;
  fieldCode: string;
  fieldType?: number;
  fillType?: number;
  formula?: string;
  headerGroup?: string;
  hidden?: boolean;
  cascadeOptions?: Record<string, string[]>;
  cascadeParent?: string;
  multiple?: boolean;
  refKind?: FillFieldSchema['refKind'];
  length?: number;
  name?: string;
  required?: boolean;
  scale?: number;
  width?: number;
}

/** dbswitch 逻辑类型 → 编辑器字段类型（1小数 2字符串 3日期 5整数 6长整型 9日期时间 10时间） */
function mapFieldType(field: ServerField): FillFieldSchema['fieldType'] {
  if ((field.fillType === 2 || field.fillType === 3) && field.dictOptions?.length) {
    return 'dict';
  }
  switch (field.fieldType) {
    case 1: {
      return 'decimal';
    }
    case 3:
    case 9: {
      return 'date';
    }
    case 5:
    case 6: {
      return 'integer';
    }
    default: {
      return 'string';
    }
  }
}

/** 解析模板 fieldSchema JSON（隐藏字段不进编辑器）；refOptions 来自草稿快照 */
export function mapFieldSchema(
  fieldSchemaJson: string,
  refOptions?: Record<string, Array<{ label: string; value: string }>>,
): FillFieldSchema[] {
  let fields: ServerField[] = [];
  try {
    fields = JSON.parse(fieldSchemaJson) ?? [];
  } catch {
    return [];
  }
  return fields
    .filter((field) => !field.hidden)
    .map((field) => ({
      dictOptions: field.dictOptions?.length ? field.dictOptions : undefined,
      editable: field.editable !== false,
      fieldCode: field.fieldCode,
      fieldType: mapFieldType(field),
      formula: field.formula || undefined,
      headerGroup: field.headerGroup || undefined,
      logicalType: field.fieldType,
      multiple: field.multiple === true,
      cascadeOptions: field.cascadeOptions,
      cascadeParent: field.cascadeParent || undefined,
      refKind: field.refKind,
      refOptions: field.refKind ? (refOptions?.[field.fieldCode] ?? []) : undefined,
      name: field.name ?? field.fieldCode,
      required: field.required,
      scale: field.scale,
      width: field.width ?? 140,
    }));
}

/** 解析模板 formulaSchema JSON（保持服务端给出的依赖顺序） */
export function mapFormulaSchema(formulaSchemaJson?: string): FillFormula[] {
  if (!formulaSchemaJson) return [];
  try {
    const schema = JSON.parse(formulaSchemaJson) as { formulas?: FillFormula[] };
    return (schema.formulas ?? []).filter(
      (formula) => !!formula.fieldCode && !!formula.expression,
    );
  } catch {
    return [];
  }
}

/** 解析模板 layoutSnapshot JSON */
export function mapLayout(layoutJson?: string): FillLayout {
  if (!layoutJson) return {};
  try {
    const layout = JSON.parse(layoutJson) as FillLayout;
    return typeof layout === 'object' && layout ? layout : {};
  } catch {
    return {};
  }
}

/** 草稿快照行 → 编辑器行模型（按 rowOrder 升序） */
export function mapDraftRows(rows: DataforgeFillApi.DraftRow[]): FillRowData[] {
  return [...rows]
    .sort((a, b) => Number(a.rowOrder) - Number(b.rowOrder))
    .map((row) => ({ data: row.data ?? {}, rowId: row.rowId }));
}
