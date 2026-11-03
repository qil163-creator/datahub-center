import type { BusinessConditionApi } from '#/api/modeling/business-condition';
import type { TableModelApi } from '#/api/modeling/tablemodel';

export const CONFIG_LABELS = {
  BASIC: '基础限定',
  COMPOSITE: '组合限定',
} as const;
export const LOGIC_LABELS = {
  UNIFIED: '全时段统一',
  SEGMENTED: '分时段配置',
} as const;
export const STATUS_LABELS = { ACTIVE: '启用', DRAFT: '禁用' } as const;
export const CONDITION_LIST_PATH = '/model/indicator-model/condition';

/** 路由编号保持十进制字符串，避免大整数被数值转换截断。 */
export function conditionRouteId(value: unknown): string | undefined {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
    ? value
    : undefined;
}

/** 保存只提交可写字段，修改时使用当前详情的版本执行 CAS。 */
export function conditionSaveData(
  values: BusinessConditionApi.Save,
  current?: BusinessConditionApi.Model,
): BusinessConditionApi.Save {
  return {
    id: current?.id,
    expectedRevision: current?.revision,
    code: values.code.trim(),
    name: values.name.trim(),
    dataDomainId: values.dataDomainId,
    subjectDomainId: values.subjectDomainId,
    entityId: values.entityId,
    status: values.status || 'DRAFT',
    ownerUserId: values.ownerUserId,
    description: values.description,
    businessEntity: values.businessEntity,
    dataFreshness: values.dataFreshness,
    sourceModelId: values.sourceModelId,
    configType: values.configType,
    logicMode: values.logicMode,
    expression: values.expression.trim(),
  };
}

/** 克隆保留定义和来源，清除编号、编码、状态和审计字段。 */
export function conditionCloneData(
  detail: BusinessConditionApi.Model,
): BusinessConditionApi.Save {
  const values = conditionSaveData(detail);
  return { ...values, code: '', name: detail.name, status: 'DRAFT' };
}

export function conditionValidation(
  values: Partial<BusinessConditionApi.Save>,
  definition = false,
): string | undefined {
  if (!values.name?.trim()) return '请输入业务限定中文名';
  if (values.name.trim().length > 100) return '业务限定中文名不能超过100个字符';
  if (
    !values.code?.trim() ||
    !/^[a-zA-Z][a-zA-Z0-9_]*$/.test(values.code.trim()) ||
    values.code.trim().length > 100
  )
    return '英文名需以字母开头，仅含字母、数字或下划线，最长100个字符';
  if (!values.dataDomainId) return '请选择数据域';
  if (!values.subjectDomainId) return '请选择主题域';
  if (!values.entityId) return '请选择业务实体';
  if (!values.ownerUserId) return '请选择负责人';
  if (definition && !['1', '2', '3'].includes(values.dataFreshness || ''))
    return '请选择数据时效';
  if (definition && !values.sourceModelId) return '请选择来源逻辑模型';
  if ((values.description?.length ?? 0) > 2000) return '描述不能超过2000个字符';
  if ((values.businessEntity?.length ?? 0) > 100)
    return '业务实体不能超过100个字符';
  if ((values.dataFreshness?.length ?? 0) > 100)
    return '数据时效不能超过100个字符';
  if (
    definition &&
    (values.configType !== 'BASIC' || values.logicMode !== 'UNIFIED')
  )
    return '当前仅支持基础限定、全时段统一配置';
  if (definition && !values.expression?.trim()) return '请输入计算逻辑';
  if (definition && (values.expression?.length ?? 0) > 4000)
    return '计算逻辑不能超过4000个字符';
  return undefined;
}

/** 来源目录与服务端保持一致，仅引用启用且已发布模型的当前设计。 */
export function conditionSourceMatches(
  model: Partial<TableModelApi.Model>,
  values: Partial<BusinessConditionApi.Save>,
) {
  return (
    model.enabled === 1 &&
    model.status === 'PUBLISHED' &&
    ['DETAIL', 'DIMENSION'].includes(model.tableType || '') &&
    !!values.dataDomainId &&
    String(model.dataDomainId) === String(values.dataDomainId) &&
    !!values.subjectDomainId &&
    String(model.subjectDomainId) === String(values.subjectDomainId) &&
    !!values.entityId &&
    String(model.entityId) === String(values.entityId) &&
    !!values.dataFreshness &&
    String(model.dataTimeliness) === String(values.dataFreshness)
  );
}
