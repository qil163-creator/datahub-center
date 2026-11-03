import type { FullSyncConfig } from '#/api/dataforge/fullsync';

/** 来源表移除时同时清理映射，避免已不可见的配置阻断后续保存。 */
export function reconcileTableSelection(
  config: FullSyncConfig,
  selectedTables: string[],
): FullSyncConfig {
  const selected = new Set(selectedTables);
  return {
    ...config,
    sourceSelectedTables: [...selected],
    configMap: Object.fromEntries(
      Object.entries(config.configMap ?? {}).filter(([name]) =>
        selected.has(name),
      ),
    ),
    tableProperties: Object.fromEntries(
      Object.entries(config.tableProperties ?? {}).filter(([name]) =>
        selected.has(name),
      ),
    ),
  };
}
