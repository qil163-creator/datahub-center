import { describe, expect, it } from 'vitest';

import { nativeTaskTemplates, newNativeProgramConfig } from './native-model';

describe('原生任务初始配置与模板', () => {
  it.each([
    'FLINK_SQL',
    'SPARK_SQL',
    'FLINK_JAR',
    'SPARK_JAR',
    'PYFLINK',
    'PYSPARK',
  ] as const)('%s 新任务不会隐式使用旧SQL兼容通道', (type) => {
    expect(newNativeProgramConfig(type)).toEqual({
      schemaVersion: 1,
      workloadMode: 'BATCH',
    });
  });
  it('数据库SQL不附加原生配置，JAR不伪造编辑器可执行源码', () => {
    expect(newNativeProgramConfig('SQL')).toBeUndefined();
    expect(nativeTaskTemplates('FLINK_JAR')).toEqual([]);
  });
  it.each(['FLINK_SQL', 'SPARK_SQL'] as const)(
    '%s 示例作为原生SQL内容提交',
    (type) => {
      const template = nativeTaskTemplates(type)[0]!;
      expect(template.preserveTaskType).toBe(true);
      expect(template).not.toHaveProperty('referenceOnly');
      expect(template.content).toContain('VALUES (1), (2), (3)');
      if (type === 'FLINK_SQL') {
        expect(template.content).toContain("'connector' = 'blackhole'");
        expect(template.content).toContain('INSERT INTO sample_sink');
        expect(template.description).toContain('结果丢弃');
      }
    },
  );
  it.each(['PYFLINK', 'PYSPARK'] as const)(
    '%s 模板仅作为受管制品源码参考',
    (type) => {
      expect(nativeTaskTemplates(type)[0]).toEqual(
        expect.objectContaining({
          referenceOnly: true,
          preserveTaskType: true,
        }),
      );
      expect(nativeTaskTemplates(type)[0]?.description).toContain('登记为受管');
    },
  );
});
