import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { DEV_TASK_TYPE_OPTIONS } from '#/api/dataforge/dev';

export function parseDevWorkloadMode(
  value: unknown,
): DataforgeDevApi.WorkloadMode | undefined {
  return value === 'BATCH' || value === 'STREAMING' ? value : undefined;
}

/** 页面归属与引擎运行模式独立；历史任务回退到程序模式或离线。 */
export function resolveDevWorkloadMode(
  task: Pick<
    DataforgeDevApi.Task,
    'executionConfig' | 'programConfig' | 'taskType' | 'workloadMode'
  >,
): DataforgeDevApi.WorkloadMode {
  return (
    task.executionConfig?.workloadMode ??
    task.programConfig?.workloadMode ??
    task.workloadMode ??
    'BATCH'
  );
}

export function devTaskTypeOptions(mode?: DataforgeDevApi.WorkloadMode) {
  return mode === 'STREAMING'
    ? DEV_TASK_TYPE_OPTIONS.filter((option) =>
        [
          'FLINK_JAR',
          'FLINK_SQL',
          'PYFLINK',
          'PYSPARK',
          'SPARK_JAR',
          'SPARK_SQL',
          'SQL',
        ].includes(option.value),
      )
    : DEV_TASK_TYPE_OPTIONS;
}

export function newDevelopmentTask(
  mode?: DataforgeDevApi.WorkloadMode,
  taskType: DataforgeDevApi.TaskType = mode === 'STREAMING'
    ? 'FLINK_SQL'
    : 'SQL',
): Partial<DataforgeDevApi.Task> {
  const pageMode = mode ?? 'BATCH';
  const executionConfig = { workloadMode: pageMode };
  if (taskType === 'SQL')
    return { taskType, timeoutSeconds: 300, executionConfig };
  const programMode = taskType === 'SPARK_SQL' ? 'BATCH' : pageMode;
  return {
    taskType,
    executionConfig,
    programConfig: { schemaVersion: 1, workloadMode: programMode },
    timeoutSeconds: programMode === 'STREAMING' ? 0 : 300,
  };
}
