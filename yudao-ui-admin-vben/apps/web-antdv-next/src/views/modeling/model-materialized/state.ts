import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

export const MATERIALIZED_STATUS_OPTIONS = [
  { label: '未发布', value: 'UNPUBLISHED', color: 'default' },
  { label: '成功', value: 'SUCCESS', color: 'success' },
  { label: '失败', value: 'FAIL', color: 'error' },
  { label: '执行中', value: 'RUNNING', color: 'processing' },
  { label: '结果未知', value: 'UNKNOWN', color: 'warning' },
] as const;

export function materializedStatus(
  row?: Pick<ModelMaterializedApi.Materialized, 'publishStatus' | 'status'>,
) {
  return row?.publishStatus || row?.status || 'UNPUBLISHED';
}

export function isUnresolvedMaterialization(
  row: ModelMaterializedApi.Materialized,
) {
  return ['RUNNING', 'UNKNOWN'].includes(materializedStatus(row));
}

export function recoveryAction(row: ModelMaterializedApi.Materialized) {
  const id = row.materializedId ?? row.id;
  if (
    id === undefined ||
    id === null ||
    id === '' ||
    !isUnresolvedMaterialization(row)
  ) {
    return undefined;
  }
  return 'reconcile' as const;
}
