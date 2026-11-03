import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getModelMaterialized,
  getModelMaterializedByModel,
  getModelMaterializedCatTree,
  getModelMaterializedDatabases,
  getModelMaterializedPage,
  reconcileModelMaterialized,
} from './index';

const client = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('#/api/request', () => ({ requestClient: client }));
beforeEach(() => vi.clearAllMocks());

describe('materialized recovery API contract', () => {
  const id = '9007199254740993127';

  it('模型详情查询使用 modelId，分类与连接走发布权限目录', async () => {
    await getModelMaterializedByModel(id);
    expect(client.get).toHaveBeenLastCalledWith(
      '/modeling/model-materialized/get',
      { params: { modelId: id } },
    );
    await getModelMaterializedCatTree();
    expect(client.get).toHaveBeenLastCalledWith(
      '/modeling/model-materialized/cat-tree',
    );
    await getModelMaterializedDatabases();
    expect(client.get).toHaveBeenLastCalledWith(
      '/modeling/model-materialized/database-list',
    );
  });

  it('透传真实分类与表类型筛选和服务器排序', async () => {
    const params = {
      pageNo: 1,
      pageSize: 10,
      tableType: 'SOURCE',
      layerIds: [id],
      sourceSystemId: id,
      sortField: 'publishTime' as const,
      sortOrder: 'desc' as const,
    };
    await getModelMaterializedPage(params);
    expect(client.get).toHaveBeenLastCalledWith(
      '/modeling/model-materialized/page',
      { params },
    );
  });

  it('preserves deployment and version IDs and timestamp primitives', async () => {
    const detail = {
      id,
      modelId: '9007199254740993123',
      designRevision: '9007199254740993125',
      attemptId: '9007199254740993129',
      publishStatus: 'UNKNOWN',
      createTime: 1_791_964_800_000,
    };
    client.get.mockResolvedValue(detail);
    expect(await getModelMaterialized(id)).toEqual(detail);
    expect(client.get).toHaveBeenCalledExactlyOnceWith(
      '/modeling/model-materialized/get',
      { params: { id } },
    );
  });

  it('reconciles through POST query params without losing the record ID', async () => {
    client.post.mockResolvedValue('UNKNOWN');
    expect(await reconcileModelMaterialized(id)).toBe('UNKNOWN');
    expect(client.post).toHaveBeenCalledExactlyOnceWith(
      '/modeling/model-materialized/reconcile',
      undefined,
      { params: { id } },
    );
  });
});
