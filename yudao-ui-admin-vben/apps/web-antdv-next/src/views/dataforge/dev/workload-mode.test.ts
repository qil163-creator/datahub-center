import { describe, expect, it } from 'vitest';

import {
  devTaskTypeOptions,
  newDevelopmentTask,
  parseDevWorkloadMode,
  resolveDevWorkloadMode,
} from './workload-mode';

describe('研发任务离线和实时模式', () => {
  it('历史空程序配置归为离线，编辑配置优先于服务端派生字段', () => {
    expect(resolveDevWorkloadMode({ programConfig: null })).toBe('BATCH');
    expect(resolveDevWorkloadMode({ programConfig: {} })).toBe('BATCH');
    expect(
      resolveDevWorkloadMode({
        programConfig: { workloadMode: 'STREAMING' },
        taskType: 'FLINK_SQL',
        workloadMode: 'BATCH',
      }),
    ).toBe('STREAMING');
    expect(resolveDevWorkloadMode({ workloadMode: 'STREAMING' })).toBe(
      'STREAMING',
    );
  });
  it('普通SQL分类来自executionConfig且保持正超时，页面归属优先于程序运行模式', () => {
    expect(
      resolveDevWorkloadMode({
        taskType: 'SQL',
        executionConfig: { workloadMode: 'STREAMING' },
        programConfig: { workloadMode: 'BATCH' },
      }),
    ).toBe('STREAMING');
    expect(
      resolveDevWorkloadMode({
        taskType: 'FLINK_SQL',
        executionConfig: { workloadMode: 'STREAMING' },
        programConfig: { workloadMode: 'BATCH' },
      }),
    ).toBe('STREAMING');
    expect(newDevelopmentTask('STREAMING', 'SQL')).toEqual({
      taskType: 'SQL',
      timeoutSeconds: 300,
      executionConfig: { workloadMode: 'STREAMING' },
    });
  });
  it('路由模式拒绝数组、未知值及大小写变体', () => {
    expect(parseDevWorkloadMode('BATCH')).toBe('BATCH');
    expect(parseDevWorkloadMode('STREAMING')).toBe('STREAMING');
    for (const value of [undefined, ['BATCH'], 'streaming', 'REALTIME', 0])
      expect(parseDevWorkloadMode(value)).toBeUndefined();
  });
  it('实时可创建原生持续任务和归类SQL，默认草稿不绑定数据库或自动运行', () => {
    const values = devTaskTypeOptions('STREAMING').map(
      (option) => option.value,
    );
    expect(values).toEqual(
      expect.arrayContaining([
        'SQL',
        'SPARK_SQL',
        'FLINK_SQL',
        'FLINK_JAR',
        'PYFLINK',
        'SPARK_JAR',
        'PYSPARK',
      ]),
    );
    for (const type of ['SHELL', 'PROCEDURE']) {
      expect(values).not.toContain(type);
    }
    expect(newDevelopmentTask('STREAMING')).toEqual({
      taskType: 'FLINK_SQL',
      executionConfig: { workloadMode: 'STREAMING' },
      programConfig: { schemaVersion: 1, workloadMode: 'STREAMING' },
      timeoutSeconds: 0,
    });
    expect(newDevelopmentTask('STREAMING', 'SPARK_SQL')).toEqual({
      taskType: 'SPARK_SQL',
      timeoutSeconds: 300,
      executionConfig: { workloadMode: 'STREAMING' },
      programConfig: { schemaVersion: 1, workloadMode: 'BATCH' },
    });
    expect(newDevelopmentTask('BATCH')).toEqual({
      taskType: 'SQL',
      timeoutSeconds: 300,
      executionConfig: { workloadMode: 'BATCH' },
    });
  });
});
