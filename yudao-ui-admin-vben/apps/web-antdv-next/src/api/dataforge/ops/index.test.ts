import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { downloadOpsInstanceLog, getOpsInstance, getOpsInstancePage } from './index';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn(), download: vi.fn() } }));

describe('运维实例跨端标识与筛选契约', () => {
  it('同编号的源记录与平台记录通过 origin 区分，保留大整数编号', () => {
    const source = { kind: 'DEVELOPMENT' as const, id: '9007199254740993', origin: 'SOURCE' as const };
    const platform = { ...source, origin: 'PLATFORM' as const };
    getOpsInstance(source);
    downloadOpsInstanceLog(platform);
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/ops-instance/get', { params: source });
    expect(requestClient.download).toHaveBeenCalledWith('/dataforge/ops-instance/log-download', { params: platform });
  });
  it('分类来源前缀、分页及执行日期直接交给后端，排序不改变标识', () => {
    const params = { kind: 'INTEGRATION' as const, pageNo: 2, pageSize: 20, categoryId: 'source:9007199254740993', startDate: '2026-08-01', endDate: '2026-08-31', sortField: 'id' as const, sortOrder: 'asc' as const };
    getOpsInstancePage(params);
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/ops-instance/page', { params });
  });
});
