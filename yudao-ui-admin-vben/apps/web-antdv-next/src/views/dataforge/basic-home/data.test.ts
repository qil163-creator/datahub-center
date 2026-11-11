import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadBasicOverview } from './data';

const api = vi.hoisted(() => ({
  getDashboardAuditRuleDimensionList: vi.fn(),
  getDashboardAuditRulePage: vi.fn(),
  getDashboardCleanRulePage: vi.fn(),
  getDashboardDatabasePage: vi.fn(),
  getDashboardDatabaseTypePage: vi.fn(),
  getDashboardDataforgeSummary: vi.fn(),
  getDashboardFileConnections: vi.fn(),
  getDashboardSystemSourcePage: vi.fn(),
}));

vi.mock('#/api/dashboard', () => api);
vi.mock('@vben/utils', () => ({ formatDate: () => '' }));

const page = (total: number, list: unknown[] = []) => ({ list, total });

beforeEach(() => {
  vi.clearAllMocks();
  api.getDashboardDataforgeSummary.mockResolvedValue({
    databaseTypeDistribution: [{ name: 'Doris', count: 2 }],
  });
  api.getDashboardDatabasePage.mockResolvedValue(
    page(2, [
      { id: '9007199254740993', name: '旧连接', createTime: 1000 },
      { id: 2, name: '新连接', createTime: 3000 },
    ]),
  );
  api.getDashboardDatabaseTypePage.mockImplementation((status?: number) =>
    Promise.resolve(page(status === 0 ? 40 : 46)),
  );
  api.getDashboardSystemSourcePage.mockResolvedValue(
    page(1, [{ id: 7, name: '业务系统', createTime: 2000 }]),
  );
  api.getDashboardFileConnections.mockResolvedValue([{ id: 1 }]);
  api.getDashboardCleanRulePage.mockResolvedValue(page(4));
  api.getDashboardAuditRuleDimensionList.mockResolvedValue([
    { id: 2, name: '一致性', code: 'D02', sort: 2, status: 0 },
    { id: 1, name: '完整性', code: 'D01', sort: 1, status: 0 },
  ]);
});

describe('基础概览数据', () => {
  it('按维度排序统计稽查规则，并按时间合并最近接入', async () => {
    api.getDashboardAuditRulePage.mockImplementation(
      (params?: { dimensionId?: number; status?: number }) => {
        if (params?.dimensionId) return Promise.resolve(page(params.dimensionId * 3));
        return Promise.resolve(page(params?.status === 0 ? 8 : 9));
      },
    );
    const result = await loadBasicOverview();
    expect(result.metrics.databaseType).toMatchObject({
      value: 46,
      hint: '已启用 40 种',
    });
    expect(result.metrics.auditRule).toMatchObject({
      value: 9,
      hint: '已启用 8 条',
    });
    expect(result.distributions.auditDimension?.rows).toEqual([
      { key: '1', name: '完整性', code: 'D01', count: 3 },
      { key: '2', name: '一致性', code: 'D02', count: 6 },
    ]);
    expect(result.activity?.items.map((item) => item.id)).toEqual([
      'database-2',
      'source-7',
      'database-9007199254740993',
    ]);
  });

  it('任一维度统计失败时整个维度分布降级，避免残缺占比', async () => {
    api.getDashboardAuditRulePage.mockImplementation(
      (params?: { dimensionId?: number }) =>
        params?.dimensionId === 2
          ? Promise.reject(new Error('down'))
          : Promise.resolve(page(1)),
    );
    const result = await loadBasicOverview();
    expect(result.distributions.auditDimension).toBeUndefined();
    expect(result.metrics.auditRule).toMatchObject({ value: 1 });
  });
});
