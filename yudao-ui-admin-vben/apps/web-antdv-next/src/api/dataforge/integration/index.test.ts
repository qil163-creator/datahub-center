import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getIntegrationPage,
  getIntegrationRecords,
  setIntegrationSchedule,
  setIntegrationStatus,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

describe('集成任务接口契约', () => {
  it('分页筛选包含类目、历史日期和无损负责人ID', () => {
    const params = {
      mode: 'OFFLINE' as const,
      pageNo: 2,
      pageSize: 10,
      categoryId: '9007199254740993',
      ownerId: '9007199254740995',
      runDateStart: '2026-08-01',
      runDateEnd: '2026-08-31',
    };
    getIntegrationPage(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/integration/page',
      { params },
    );
  });
  it('状态及调度提交读取时修订号，实例筛选不丢失任务ID', () => {
    const id = '9007199254740993';
    setIntegrationStatus({ id, revision: 3, status: 0 });
    setIntegrationSchedule({
      id,
      revision: 4,
      scheduleStatus: 0,
      cronExpression: '0 0 0 * * ?',
    });
    getIntegrationRecords({
      taskId: id,
      pageNo: 1,
      pageSize: 10,
      status: 'SUCCESS',
    });
    expect(requestClient.put).toHaveBeenNthCalledWith(
      1,
      '/dataforge/integration/status',
      { id, revision: 3, status: 0 },
    );
    expect(requestClient.put).toHaveBeenNthCalledWith(
      2,
      '/dataforge/integration/schedule',
      { id, revision: 4, scheduleStatus: 0, cronExpression: '0 0 0 * * ?' },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/integration/records',
      { params: { taskId: id, pageNo: 1, pageSize: 10, status: 'SUCCESS' } },
    );
  });
});
