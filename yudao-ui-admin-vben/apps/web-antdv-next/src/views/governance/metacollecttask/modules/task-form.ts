import type { GovernanceMetaCollectTaskApi as Api } from '#/api/governance/metacollecttask';

/** 读取后端保存的采集范围，拒绝错误形状，避免默默覆盖已有配置。 */
export function parseCollectScope(value?: string): Api.Scope[] {
  const data: unknown = value ? JSON.parse(value) : [];
  if (
    !Array.isArray(data) ||
    !data.every(
      (scope) =>
        scope &&
        typeof scope === 'object' &&
        typeof scope.db === 'string' &&
        Array.isArray(scope.tables) &&
        scope.tables.every((table: unknown) => typeof table === 'string'),
    )
  ) {
    throw new Error('已有采集范围格式不正确，请重新配置');
  }
  return data.map((scope) => ({ db: scope.db, tables: [...scope.tables] }));
}

export function parseCollectBlacklist(value?: string): string[] {
  const data: unknown = value ? JSON.parse(value) : [];
  if (!Array.isArray(data) || !data.every((item) => typeof item === 'string')) {
    throw new Error('已有黑名单格式不正确，请重新配置');
  }
  return data;
}

/** 仅提交任务可编辑字段，连接摘要、创建人和运行状态不回写。 */
export function collectTaskPayload(
  model: Api.CollectTask,
  scopes: Api.Scope[],
  blacklist: string[],
): Api.CollectTask {
  return {
    id: model.id,
    name: model.name?.trim(),
    collectType: model.collectType,
    executeStrategy: model.executeStrategy,
    datasourceId: model.datasourceId,
    sourceSystemId: model.sourceSystemId,
    ownerUserId: model.ownerUserId,
    ownerPhone: model.ownerPhone,
    cron: model.cron,
    description: model.description,
    scopeType: model.collectType === 2 ? 1 : model.scopeType,
    filePath: model.collectType === 2 ? model.filePath?.trim() : undefined,
    scopeConfig:
      model.collectType === 1 && model.scopeType === 2
        ? JSON.stringify(scopes)
        : undefined,
    blacklist: model.collectType === 1 ? JSON.stringify(blacklist) : undefined,
  };
}
