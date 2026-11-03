import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createDevTask,
  getDevDirList,
  getDevRecordTaskLog,
  getDevRuntimeProfiles,
  getDevTask,
  getDevTaskListByDir,
  getDevTaskPage,
  getDevTaskStatistics,
  isNativeProgramNotReady,
  resolveDevRecordQuality,
  rollbackDevTaskVersion,
  runDevTask,
  updateDevTask,
  updateDevTaskSchedule,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());
describe('研发接口跨端契约', () => {
  it('查询保留超出安全整数范围的任务ID', () => {
    getDevTask('9007199254740993');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-task/get?id=9007199254740993',
    );
  });
  it('目录列表不带项目范围，任务列表按目录过滤并保留无损ID', () => {
    getDevDirList();
    getDevTaskListByDir('9007199254740995');
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/dev-dir/list');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-task/list-by-dir',
      { params: { dirId: '9007199254740995' } },
    );
  });
  it('离线与实时筛选传给服务端分页、统计和资源树，保存剥离派生模式', () => {
    getDevTaskPage({ pageNo: 1, pageSize: 10, workloadMode: 'STREAMING' });
    getDevTaskStatistics('BATCH');
    getDevTaskListByDir('9007199254740995', 'STREAMING');
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/dev-task/page', {
      params: { pageNo: 1, pageSize: 10, workloadMode: 'STREAMING' },
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-task/statistics',
      { params: { workloadMode: 'BATCH' } },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-task/list-by-dir',
      { params: { dirId: '9007199254740995', workloadMode: 'STREAMING' } },
    );
    createDevTask({
      taskType: 'FLINK_SQL',
      workloadMode: 'STREAMING',
      programConfig: { workloadMode: 'STREAMING' },
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/dev-task/create',
      { taskType: 'FLINK_SQL', programConfig: { workloadMode: 'STREAMING' } },
    );
  });
  it('保存携带读取时的修订号，运行携带业务日期与幂等键', () => {
    const task = {
      id: '9007199254740993',
      expectedRevision: 4,
      content: 'SELECT 1',
      executionUserId: '9007199254740995',
    };
    updateDevTask(task);
    const run = {
      id: task.id,
      businessDate: '2026-09-15',
      requestId: 'same-request-for-retry',
    };
    runDevTask(run);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/dev-task/update',
      {
        id: task.id,
        expectedRevision: task.expectedRevision,
        content: task.content,
      },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/dev-task/run',
      run,
    );
  });
  it('回滚携带打开版本时的修订号且调度仅提交启停', () => {
    rollbackDevTaskVersion('9007199254740993', 7);
    updateDevTaskSchedule({ id: '9007199254740993', scheduleStatus: 1 });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/dev-task/version/rollback?versionId=9007199254740993&expectedRevision=7',
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/dev-task/update-schedule',
      { id: '9007199254740993', scheduleStatus: 1 },
    );
  });
  it('质量异常核查需要终止确认且不支持人工判定通过', () => {
    const data = {
      id: '9007199254740993',
      expectedStatus: 'UNKNOWN' as const,
      confirmedTerminated: true as const,
      reason: '原质检已结束',
    };
    resolveDevRecordQuality(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/dev-record/resolve-quality',
      data,
    );
    expect(data).not.toHaveProperty('status');
  });

  it('保存程序配置保留制品ID并剥离调度器内部引用', () => {
    const data = {
      taskType: 'PYSPARK' as const,
      programConfig: {
        schemaVersion: 1,
        workloadMode: 'BATCH' as const,
        artifactFileId: '9007199254740993',
        dependencyFileIds: ['9007199254740995'],
      },
      dsDatasourceId: 12,
      dsDatasourceType: 'SPARK',
      dsWorkflowCode: '9007199254740997',
    };
    createDevTask(data);
    updateDevTask(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/dev-task/create',
      {
        taskType: data.taskType,
        programConfig: data.programConfig,
      },
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/dev-task/update',
      {
        taskType: data.taskType,
        programConfig: data.programConfig,
      },
    );
    expect(data.dsDatasourceId).toBe(12);
  });

  it('原有集群SQL保持兼容，缺少能力确认的原生配置与JAR/Python不可运行', () => {
    expect(
      isNativeProgramNotReady({ taskType: 'SPARK_SQL', programConfig: null }),
    ).toBe(false);
    expect(isNativeProgramNotReady({ taskType: 'FLINK_SQL' })).toBe(false);
    expect(
      isNativeProgramNotReady({
        taskType: 'FLINK_SQL',
        programConfig: { schemaVersion: 1 },
      }),
    ).toBe(true);
    for (const taskType of [
      'FLINK_JAR',
      'SPARK_JAR',
      'PYFLINK',
      'PYSPARK',
    ] as const) {
      expect(isNativeProgramNotReady({ taskType })).toBe(true);
    }
    expect(
      isNativeProgramNotReady({
        taskType: 'SQL',
        executionCapability: 'NOT_READY',
      }),
    ).toBe(true);
  });

  it('原生配置遵循服务端执行能力，部署已接通时不会被本地配置永久禁用', () => {
    expect(
      isNativeProgramNotReady({
        taskType: 'FLINK_SQL',
        programConfig: {
          schemaVersion: 1,
          runtimeProfileCode: 'flink-managed',
        },
        executionCapability: 'NATIVE',
      }),
    ).toBe(false);
    expect(
      isNativeProgramNotReady({
        taskType: 'SPARK_JAR',
        executionCapability: 'NOT_READY',
      }),
    ).toBe(true);
    expect(
      isNativeProgramNotReady({
        taskType: 'PYFLINK',
        executionCapability: 'NATIVE',
      }),
    ).toBe(false);
  });

  it('受管部署选择从研发API获取，不访问内部RPC或集群端点', () => {
    getDevRuntimeProfiles();
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-task/runtime-profiles',
    );
  });

  it('分页执行日志只传平台实例ID与服务端游标', () => {
    getDevRecordTaskLog({ id: '9007199254740993', skipLineNum: 7, limit: 200 });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/dev-record/task-log',
      {
        params: { id: '9007199254740993', skipLineNum: 7, limit: 200 },
      },
    );
  });
});
