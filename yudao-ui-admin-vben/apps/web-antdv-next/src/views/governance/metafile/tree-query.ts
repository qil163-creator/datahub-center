import type { GovernanceMetaFileApi as Api } from '#/api/governance/metafile';

/** 来源为空必须显式筛选，否则服务器会把缺省参数解释为全部来源。 */
export function fileTreeQuery(node?: Api.TreeNode) {
  return {
    sourceSystemId: node?.sourceSystemId,
    sourceSystemMissing:
      node &&
      (node.sourceSystemId === undefined || node.sourceSystemId === null)
        ? true
        : undefined,
    datasourceId: node?.datasourceId,
    path: node?.path,
  };
}
