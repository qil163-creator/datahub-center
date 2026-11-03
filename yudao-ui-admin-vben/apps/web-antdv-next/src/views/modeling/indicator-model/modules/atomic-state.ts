import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

export const ATOMIC_AGGREGATIONS = [
  { value: 'MAX', label: '最大值' },
  { value: 'MIN', label: '最小值' },
  { value: 'COUNT', label: '计数' },
  { value: 'SUM', label: '求和' },
  { value: 'AVG', label: '平均值' },
  { value: 'COUNT_DISTINCT', label: '去重计数' },
];

export function atomicExpression(
  aggregation?: string,
  field?: IndicatorModelApi.SourceField,
) {
  if (!aggregation || !field) return '';
  if (aggregation === 'COUNT_DISTINCT')
    return `count(distinct ${field.fieldName})`;
  return `${aggregation.toLowerCase()}(${field.fieldName})`;
}

export function atomicTimeFields(fields: IndicatorModelApi.SourceField[]) {
  return fields.filter((field) =>
    /^(?:DATE|DATETIME|TIMESTAMP|TIME)(?:\b|\()/i.test(field.dataType.trim()),
  );
}

/** 来源选项以业务归属、实体和时效同时收敛，字段使用当前设计的稳定标识。 */
export function atomicSourceMatches(
  model: TableModelApi.Model,
  selection: {
    dataDomainId?: IndicatorModelApi.Id;
    dataFreshness?: string;
    entityId?: IndicatorModelApi.Id;
    subjectDomainId?: IndicatorModelApi.Id;
  },
) {
  return (
    model.enabled === 1 &&
    model.status === 'PUBLISHED' &&
    ['DETAIL', 'DIMENSION'].includes(model.tableType || '') &&
    selection.dataDomainId !== undefined &&
    String(model.dataDomainId) === String(selection.dataDomainId) &&
    selection.subjectDomainId !== undefined &&
    String(model.subjectDomainId) === String(selection.subjectDomainId) &&
    selection.entityId !== undefined &&
    String(model.entityId) === String(selection.entityId) &&
    !!selection.dataFreshness &&
    model.dataTimeliness === selection.dataFreshness
  );
}

/** 单位名称是持久值，字典备注仅用于目录分组；已存自由单位必须继续回显。 */
export function atomicUnitTree(
  options: { label: string; remark?: string; value: number | string }[],
  current?: string,
) {
  const groups = new Map<
    string,
    {
      children: { title: string; value: string }[];
      selectable: boolean;
      title: string;
      value: string;
    }
  >();
  for (const option of options) {
    const name = option.remark || '其他';
    let group = groups.get(name);
    if (!group) {
      group = {
        value: `unit-group/${name}`,
        title: name,
        selectable: false,
        children: [],
      };
      groups.set(name, group);
    }
    group.children.push({ value: String(option.value), title: option.label });
  }
  if (current && !options.some((option) => String(option.value) === current))
    groups.set('当前单位', {
      value: 'unit-current',
      title: '当前单位',
      selectable: false,
      children: [{ value: current, title: current }],
    });
  return [...groups.values()];
}
