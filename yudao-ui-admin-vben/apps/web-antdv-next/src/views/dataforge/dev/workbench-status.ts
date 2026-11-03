import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { getDevTaskIcon } from './task-icon-model';

function directoryKey(id?: DataforgeDevApi.Id | null) {
  if (
    id === null ||
    id === undefined ||
    (typeof id === 'number' && !Number.isSafeInteger(id))
  )
    return undefined;
  return String(id);
}

/** 只拼接实际目录名称；父级缺失或成环时保留能够确认的路径片段。 */
export function resolveWorkbenchDirectoryPath(
  directories: readonly DataforgeDevApi.Dir[],
  directoryId?: DataforgeDevApi.Id | null,
): string[] {
  const indexed = new Map<
    string,
    { directory: DataforgeDevApi.Dir; parentKey?: string }
  >();
  const visitedNodes = new Set<DataforgeDevApi.Dir>();
  const pending = directories.map((directory) => ({
    directory,
    parentKey: undefined as string | undefined,
  }));
  while (pending.length > 0) {
    const entry = pending.pop()!;
    if (visitedNodes.has(entry.directory)) continue;
    visitedNodes.add(entry.directory);
    const key = directoryKey(entry.directory.id);
    if (key !== undefined && !indexed.has(key))
      indexed.set(key, {
        directory: entry.directory,
        parentKey:
          entry.directory.parentId === undefined
            ? entry.parentKey
            : directoryKey(entry.directory.parentId),
      });
    for (const child of entry.directory.children ?? [])
      pending.push({ directory: child, parentKey: key });
  }

  const names: string[] = [];
  const visitedKeys = new Set<string>();
  let current = directoryKey(directoryId);
  while (
    current !== undefined &&
    current !== '0' &&
    !visitedKeys.has(current)
  ) {
    const entry = indexed.get(current);
    if (!entry) break;
    visitedKeys.add(current);
    if (entry.directory.name?.trim()) names.push(entry.directory.name);
    current = entry.parentKey;
  }
  return names.toReversed();
}

/** 底栏使用参考产品文案，不改变任务页面归属或原生运行配置。 */
export function resolveWorkbenchRuntimeLabel(
  taskType?: string,
  dbType?: string,
  workloadMode?: DataforgeDevApi.WorkloadMode,
): string {
  const type = taskType
    ?.trim()
    .toUpperCase()
    .replaceAll(/[\s-]+/g, '_');
  if (type === 'FLINK_SQL')
    return workloadMode === 'STREAMING' ? 'Flink流' : 'Flink批';
  if (type === 'SPARK_SQL') return 'SparkSql';
  const icon = getDevTaskIcon({ taskType, dbType });
  if (['FLINK_JAR', 'PYFLINK', 'PYSPARK', 'SPARK_JAR'].includes(type ?? ''))
    return `${icon.product === 'FLINK' ? 'Flink' : 'Spark'}${workloadMode === 'STREAMING' ? '流' : '批'}`;
  if (icon.product === 'DM') return 'DM8';
  if (icon.product === 'HIVE') return 'Hive';
  return icon.label;
}
