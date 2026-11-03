import type { ModelingModelApi } from '#/api/modeling/model';

export const FIELD_CATEGORY_OPTIONS = [
  { label: '属性', value: 'ATTRIBUTE' },
  { label: '维度', value: 'DIMENSION' },
  { label: '度量', value: 'MEASURE' },
  { label: '时间', value: 'TIME' },
  { label: '普通字段', value: 'NORMAL' },
] as const;

export function detailText(value: unknown) {
  return value === null || value === undefined || value === ''
    ? '-'
    : String(value);
}

export function fieldCategory(field: ModelingModelApi.ModelField) {
  if (
    field.pkFlag &&
    !['GRAIN', 'INDICATOR', 'TIME'].includes(field.fieldCategory || '')
  )
    return '主键';
  return (
    (
      {
        ATTRIBUTE: '属性',
        DIMENSION: '维度',
        GRAIN: '粒度字段',
        INDICATOR: '指标字段',
        MEASURE: '度量',
        NORMAL: '普通字段',
        PRIMARY_KEY: '主键',
        TIME: '时间字段',
      } as Record<string, string>
    )[field.fieldCategory || ''] || '-'
  );
}

export function fieldConstraints(field: ModelingModelApi.ModelField) {
  return [
    field.pkFlag ? '主键' : '',
    field.nullableFlag === false ? '非空' : '',
    field.uniqueFlag || field.pkFlag ? '唯一性' : '',
  ].filter(Boolean);
}

export function filteredDetailFields(
  fields: ModelingModelApi.ModelField[],
  keyword: string,
) {
  const normalized = keyword.trim().toLocaleLowerCase();
  return fields.filter(
    (field) =>
      !normalized ||
      `${field.fieldName || ''} ${field.cnName || ''}`
        .toLocaleLowerCase()
        .includes(normalized),
  );
}

export function summaryFieldGroups(fields: ModelingModelApi.ModelField[]) {
  return [
    {
      key: 'TIME',
      title: '时间字段',
      description:
        '系统根据统计时效自动生成，用于标识每条汇总数据所属的统计时间。',
      fields: fields.filter((field) => field.fieldCategory === 'TIME'),
    },
    {
      key: 'GRAIN',
      title: '粒度字段',
      description:
        '根据所选统计粒度自动生成，用于标识汇总对象，并共同决定一条汇总结果的唯一性。',
      fields: fields.filter((field) => field.fieldCategory === 'GRAIN'),
    },
    {
      key: 'INDICATOR',
      title: '派生指标字段',
      description:
        '系统根据当前统计粒度和统计时效，自动匹配并回填符合条件的派生指标。',
      fields: fields.filter((field) => field.fieldCategory === 'INDICATOR'),
    },
  ];
}
