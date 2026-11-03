import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { getDevProgramType, isClusterTaskType } from '#/api/dataforge/dev';

export function newNativeProgramConfig(taskType?: DataforgeDevApi.TaskType) {
  return isClusterTaskType(taskType)
    ? { schemaVersion: 1, workloadMode: 'BATCH' as const }
    : undefined;
}

/** 内置示例没有连接地址、账号或外部资源；实际运行仍需受管部署和独立授权。 */
export function nativeTaskTemplates(taskType?: DataforgeDevApi.TaskType) {
  if (!isClusterTaskType(taskType) || getDevProgramType(taskType) === 'JAR')
    return [];
  if (taskType === 'PYSPARK')
    return [
      {
        key: 'native-python',
        disabled: false,
        preserveTaskType: true,
        referenceOnly: true,
        name: 'example.pyspark · 制品源码参考',
        description: '需登记为受管 Python 制品后选择运行',
        content:
          'from pyspark.sql import SparkSession\n\nspark = SparkSession.builder.appName("example").getOrCreate()\nspark.range(10).show()\nspark.stop()\n',
      },
    ];
  if (taskType === 'PYFLINK')
    return [
      {
        key: 'native-python',
        disabled: false,
        preserveTaskType: true,
        referenceOnly: true,
        name: 'example.pyflink · 制品源码参考',
        description: '需登记为受管 Python 制品后选择运行',
        content:
          'from pyflink.table import EnvironmentSettings, TableEnvironment\n\nenvironment = TableEnvironment.create(EnvironmentSettings.in_batch_mode())\nenvironment.execute_sql("SELECT * FROM (VALUES (1), (2), (3)) AS example_data(id)").print()\n',
      },
    ];
  return [
    {
      key: 'native-sql',
      disabled: false,
      preserveTaskType: true,
      name: `example.${taskType === 'FLINK_SQL' ? 'flinksql' : 'sparksql'}`,
      description:
        taskType === 'FLINK_SQL'
          ? '有界 INSERT 计算示例，结果丢弃 · 保存后运行'
          : '有界 SQL 示例 · 保存后由原生引擎执行',
      content:
        taskType === 'FLINK_SQL'
          ? [
              '-- 有界计算示例：BlackHole 接收并丢弃结果，不写外部数据',
              'CREATE TEMPORARY TABLE sample_sink (id INT)',
              "WITH ('connector' = 'blackhole');",
              'INSERT INTO sample_sink VALUES (1), (2), (3);',
            ].join('\n')
          : 'SELECT * FROM (VALUES (1), (2), (3)) AS example_data(id);',
    },
  ];
}
