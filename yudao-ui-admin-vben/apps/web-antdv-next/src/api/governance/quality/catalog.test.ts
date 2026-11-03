import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getRuleCatalogCategories,
  getRuleCatalogDetail,
  getRuleCatalogPage,
} from './catalog';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));

describe('规则目录只读接口', () => {
  beforeEach(() => vi.clearAllMocks());
  it.each(['audit', 'clean'] as const)(
    '使用 %s 目录接口并完整保留分类和详情 ID',
    async (kind) => {
      const id = '9007199254740993';
      const params = {
        pageNo: 2,
        pageSize: 20,
        description: '有效值',
        sortField: 'id' as const,
        sortOrder: 'asc' as const,
        ...(kind === 'audit' ? { dimensionId: id } : { categoryId: id }),
      };
      getRuleCatalogPage(kind, params);
      expect(requestClient.get).toHaveBeenCalledWith(
        `/governance/${kind}-rule/page`,
        { params },
      );
      getRuleCatalogDetail(kind, id);
      expect(requestClient.get).toHaveBeenLastCalledWith(
        `/governance/${kind}-rule/get`,
        { params: { id } },
      );
      vi.mocked(requestClient.get).mockResolvedValue([]);
      await getRuleCatalogCategories(kind);
      expect(requestClient.get).toHaveBeenLastCalledWith(
        `/governance/${kind}-rule/${kind === 'audit' ? 'dimension-list' : 'category-list'}`,
      );
    },
  );
  it('稽查目录按独立维度排序并保留无损ID，不使用业务类目名称字段', async () => {
    vi.mocked(requestClient.get).mockResolvedValue([
      {
        id: '9007199254740995',
        name: '完整性',
        code: 'COMPLETENESS',
        sort: 5,
        status: 0,
      },
      {
        id: '9007199254740993',
        name: '准确性',
        code: 'ACCURACY',
        sort: 1,
        status: 0,
      },
    ]);
    expect(await getRuleCatalogCategories('audit')).toEqual([
      {
        id: '9007199254740993',
        categoryName: '准确性',
        categoryCode: 'ACCURACY',
      },
      {
        id: '9007199254740995',
        categoryName: '完整性',
        categoryCode: 'COMPLETENESS',
      },
    ]);
  });
});
