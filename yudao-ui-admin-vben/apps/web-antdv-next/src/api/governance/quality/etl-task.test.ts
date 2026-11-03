import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { createEtlQualityTask, getEtlQualityTaskCreation } from './etl-task';
vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('etl质量任务创建传输', () => {
  it('创建保留SQL表引用与大编号；结果查询复用原请求键', async () => {
    const data = {
      requestId: '12345678-1234-1234-1234-123456789012',
      databaseId: '9007199254740993',
      taskName: '批次验收',
      schemaName: 'public',
      tableName: '"orders"',
      ruleIds: ['9007199254740995'],
      activate: true,
    };
    await createEtlQualityTask(data);
    await getEtlQualityTaskCreation(data.requestId);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-task/create-for-etl',
      data,
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-task/etl-create-result',
      { params: { requestId: data.requestId } },
    );
  });
});
