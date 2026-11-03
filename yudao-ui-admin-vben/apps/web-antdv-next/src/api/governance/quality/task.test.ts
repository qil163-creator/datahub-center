import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createQualityTask,
  getQualityTask,
  sampleQualityTaskRule,
  spotCheckQualityTaskRule,
  updateQualityTaskStatus,
} from './task';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

describe('quality task wire contract', () => {
  it('keeps large route and status IDs as strings in structured parameters', async () => {
    await getQualityTask('9223372036854775806');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-task/get',
      { params: { id: '9223372036854775806' } },
    );
    await updateQualityTaskStatus('9223372036854775806', 1);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/quality-task/update-status',
      null,
      { params: { id: '9223372036854775806', taskStatus: 1 } },
    );
  });
  it('submits nested task and current draft probes without lossy transforms', async () => {
    const object = {
      clientKey: 'object-a',
      objectName: '对象',
      datasourceConnId: '9223372036854775806',
      tableName: 'data',
    };
    const evaluation = {
      objectKey: 'object-a',
      evaluationName: '检查',
      strategyKey: 'NUMERIC_RANGE_VALIDATION',
      ruleCode: '306',
      qualityDim: 'CONSISTENCY',
      severity: 'CRITICAL' as const,
      status: 0,
      columnNames: ['amount'],
      config: { minValue: '9007199254740993.00000000000000001' },
    };
    const data = {
      taskName: '质量任务',
      objects: [object],
      evaluations: [evaluation],
    };
    await createQualityTask(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-task/create',
      data,
    );
    await spotCheckQualityTaskRule({
      object,
      objects: [object],
      evaluation,
      limit: 100,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-task/spot-check',
      { object, objects: [object], evaluation, limit: 100 },
    );
    await sampleQualityTaskRule({ evaluation, sampleData: '12333' });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-task/sample',
      { evaluation, sampleData: '12333' },
    );
  });
});
