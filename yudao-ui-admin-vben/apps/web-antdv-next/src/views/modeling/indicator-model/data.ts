import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

export const TYPE_LABELS = { ATOMIC: '原子指标', DERIVED: '派生指标' } as const;
export const STATUS_LABELS = { DRAFT: '禁用', ACTIVE: '启用' } as const;
export const PERIOD_LABELS = {
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
  QUARTER: '季度',
  YEAR: '年',
} as const;
export const AGGREGATION_LABELS = {
  SUM: '求和 SUM',
  COUNT: '计数 COUNT',
  COUNT_DISTINCT: '去重计数 COUNT DISTINCT',
  AVG: '平均值 AVG',
  MIN: '最小值 MIN',
  MAX: '最大值 MAX',
} as const;
export const RESULT_LABELS = {
  INTEGER: '整数',
  DECIMAL: '小数',
  STRING: '字符串',
  DATE: '日期',
  DATETIME: '日期时间',
} as const;
export const options = (labels: Record<string, string>) =>
  Object.entries(labels).map(([value, label]) => ({ value, label }));

/** 只提交可写定义与状态，隔离显示字段以及另一类指标的来源。 */
export function indicatorSaveData(
  values: IndicatorModelApi.Save,
  current?: IndicatorModelApi.Model,
): IndicatorModelApi.Save {
  const data: IndicatorModelApi.Save = {
    id: current?.id,
    expectedRevision: current?.revision,
    indicatorType: current?.indicatorType ?? values.indicatorType,
    code: values.code.trim(),
    name: values.name.trim(),
    dataDomainId: values.dataDomainId,
    subjectDomainId: values.subjectDomainId,
    ownerUserId: values.ownerUserId,
    description: values.description,
    definition: values.definition,
    dataFreshness: values.dataFreshness,
    status: values.status ?? current?.status ?? 'DRAFT',
  };
  if (data.indicatorType === 'ATOMIC') {
    Object.assign(data, {
      entityId: values.entityId,
      sourceModelId: values.sourceModelId,
      valueFieldUid: values.valueFieldUid,
      timeFieldUid: values.timeFieldUid || undefined,
      aggregation: values.aggregation,
      resultDataType: values.resultDataType,
      unit: values.unit,
    });
  } else {
    Object.assign(data, {
      baseIndicatorId: values.baseIndicatorId,
      timePeriod: values.timePeriod,
      businessCondition: values.businessCondition,
    });
  }
  return data;
}
