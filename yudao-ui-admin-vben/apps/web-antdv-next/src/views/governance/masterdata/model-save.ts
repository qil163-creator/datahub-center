import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

/** 曾发布的模型固定目标数据源与物理表，字段定义仍可修改为草稿。 */
export function isMasterModelStructureFrozen(
  model?: GovernanceMasterDataApi.Model,
) {
  return (
    (model?.releaseTime !== undefined && model.releaseTime !== null) ||
    model?.status === 1 ||
    model?.status === 2
  );
}

function normalizedColumn(
  column: GovernanceMasterDataApi.Column,
  sort: number,
) {
  const pk = column.pk ?? 0;
  return {
    name: column.name ?? '',
    commentStr: column.commentStr ?? '',
    fieldType: column.fieldType ?? null,
    fieldLength: column.fieldLength ?? null,
    fieldScale: column.fieldScale ?? null,
    pk,
    nullable: pk === 1 ? 0 : (column.nullable ?? 1),
    standardElementId:
      column.standardElementId === undefined ||
      column.standardElementId === null
        ? null
        : String(column.standardElementId),
    sort,
  };
}

/** 按实际提交字段和数组顺序比较结构，忽略响应编号与界面行键。 */
export function isMasterModelStructureChanged(
  model: GovernanceMasterDataApi.Model | undefined,
  columns: readonly GovernanceMasterDataApi.Column[],
) {
  return (
    JSON.stringify(
      (model?.columns ?? []).map((column, sort) =>
        normalizedColumn(column, sort),
      ),
    ) !==
    JSON.stringify(
      columns.map((column, sort) => normalizedColumn(column, sort)),
    )
  );
}

/** 只发送保存契约字段，剥离状态、发布记录和表格行键。 */
export function masterModelSaveRequest(
  values: Partial<GovernanceMasterDataApi.ModelSaveReq>,
  id: GovernanceMasterDataApi.Id | undefined,
  columns: readonly GovernanceMasterDataApi.Column[],
  model?: GovernanceMasterDataApi.Model,
): GovernanceMasterDataApi.ModelSaveReq {
  const request: GovernanceMasterDataApi.ModelSaveReq = {
    id,
    domainId: values.domainId!,
    databaseId: values.databaseId!,
    tableName: values.tableName!,
    tableCn: values.tableCn!,
    description: values.description,
    approvalEnabled: values.approvalEnabled,
    approvalProcessKey: values.approvalProcessKey,
    columns: columns.map((row, sort) => ({
      name: row.name,
      commentStr: row.commentStr,
      fieldType: row.fieldType,
      fieldLength: row.fieldLength,
      fieldScale: row.fieldScale,
      nullable: row.nullable,
      pk: row.pk,
      standardElementId: row.standardElementId,
      sort,
    })),
  };
  if (id !== undefined && model !== undefined) {
    request.expectedVersion = model.version ?? 1;
  }
  return request;
}
