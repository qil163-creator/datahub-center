import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

export type MasterDataCondition = GovernanceMasterDataApi.DataCondition;
export type MasterDataFilterOperator =
  GovernanceMasterDataApi.DataFilterOperator;

export interface MasterDataFilterRow extends MasterDataCondition {
  enabled: boolean;
  key: number;
  operator: MasterDataFilterOperator;
  value: string;
}

const OPERATOR_LABELS: Record<MasterDataFilterOperator, string> = {
  CONTAINS: '包含',
  EQ: '等于（=）',
  GE: '大于等于（≥）',
  GT: '大于（>）',
  IS_EMPTY: '空字符串',
  IS_NOT_EMPTY: '非空字符串',
  IS_NOT_NULL: '不为空（NULL）',
  IS_NULL: '为空（NULL）',
  LE: '小于等于（≤）',
  LT: '小于（<）',
  NE: '不等于（≠）',
  NOT_CONTAINS: '不包含',
};
const NULL_OPERATORS: MasterDataFilterOperator[] = ['IS_NULL', 'IS_NOT_NULL'];
const COMPARISON_OPERATORS: MasterDataFilterOperator[] = [
  'EQ',
  'NE',
  'GT',
  'GE',
  'LT',
  'LE',
  ...NULL_OPERATORS,
];
const STRING_OPERATORS: MasterDataFilterOperator[] = [
  'EQ',
  'NE',
  'CONTAINS',
  'NOT_CONTAINS',
  ...NULL_OPERATORS,
  'IS_EMPTY',
  'IS_NOT_EMPTY',
];

/** 操作集合与后端字段类型白名单保持一致，未知类型仅支持 NULL 判断。 */
export function masterDataFilterOperatorOptions(
  fieldType?: number,
  allowedOperators?: MasterDataFilterOperator[],
) {
  let operators: MasterDataFilterOperator[];
  switch (fieldType) {
    case 1:
    case 3:
    case 5:
    case 6:
    case 9:
    case 10: {
      operators = COMPARISON_OPERATORS;
      break;
    }
    case 2: {
      operators = STRING_OPERATORS;
      break;
    }
    case 4: {
      operators = ['EQ', 'NE', ...NULL_OPERATORS];
      break;
    }
    default: {
      operators = NULL_OPERATORS;
    }
  }
  return operators
    .filter(
      (operator) => !allowedOperators || allowedOperators.includes(operator),
    )
    .map((value) => ({ label: OPERATOR_LABELS[value], value }));
}

export function defaultMasterDataFilterOperator(
  fieldType?: number,
  allowedOperators?: MasterDataFilterOperator[],
): MasterDataFilterOperator {
  const options = masterDataFilterOperatorOptions(fieldType, allowedOperators);
  const preferred = fieldType === 2 ? 'CONTAINS' : 'EQ';
  return options.some((option) => option.value === preferred)
    ? preferred
    : (options[0]?.value ?? 'IS_NULL');
}

export function masterDataFilterNeedsValue(operator: MasterDataFilterOperator) {
  return !['IS_EMPTY', 'IS_NOT_EMPTY', 'IS_NOT_NULL', 'IS_NULL'].includes(
    operator,
  );
}

/** 兼容未带操作符的已应用快照，避免初始化时误报待应用。 */
export function normalizeMasterDataConditions(
  conditions: MasterDataCondition[],
  columns: GovernanceMasterDataApi.Column[],
  allowedOperators?: MasterDataFilterOperator[],
): (MasterDataCondition & { operator: MasterDataFilterOperator })[] {
  const fieldTypes = new Map(
    columns.map((column) => [column.name, column.fieldType]),
  );
  return conditions.map((condition) => {
    const operator =
      condition.operator ??
      defaultMasterDataFilterOperator(
        fieldTypes.get(condition.name),
        allowedOperators,
      );
    return {
      name: condition.name,
      operator,
      ...(masterDataFilterNeedsValue(operator)
        ? { value: condition.value ?? '' }
        : {}),
    };
  });
}

export function collectMasterDataConditions(
  rows: MasterDataFilterRow[],
  columns: GovernanceMasterDataApi.Column[],
  allowedOperators?: MasterDataFilterOperator[],
):
  | { conditions: MasterDataCondition[]; valid: true }
  | { invalidKeys: number[]; valid: false } {
  const fieldTypes = new Map(
    columns.map((column) => [column.name, column.fieldType]),
  );
  const enabled = rows.filter((row) => row.enabled);
  const invalidKeys = enabled
    .filter(
      (row) =>
        !row.name ||
        !fieldTypes.has(row.name) ||
        !masterDataFilterOperatorOptions(
          fieldTypes.get(row.name),
          allowedOperators,
        ).some((option) => option.value === row.operator) ||
        (masterDataFilterNeedsValue(row.operator) && row.value.trim() === ''),
    )
    .map((row) => row.key);
  if (invalidKeys.length > 0) return { invalidKeys, valid: false };
  return {
    conditions: enabled.map(({ name, operator, value }) => ({
      name,
      operator,
      ...(masterDataFilterNeedsValue(operator) ? { value } : {}),
    })),
    valid: true,
  };
}
