import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

/** 删除平台定义仅适用于草稿或已下线且发布结果明确的模型。 */
export function canDeleteMasterModel(model?: GovernanceMasterDataApi.Model) {
  return (
    model?.id !== undefined &&
    model.id !== null &&
    (model.status === 0 || model.status === 2) &&
    (model.publishState === undefined ||
      model.publishState === null ||
      model.publishState === 'READY')
  );
}
