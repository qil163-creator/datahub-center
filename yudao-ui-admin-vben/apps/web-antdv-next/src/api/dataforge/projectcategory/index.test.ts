import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createProjectCategory,
  deleteProjectCategory,
  deleteProjectCategoryList,
  getProjectCategory,
  getProjectCategoryList,
  updateProjectCategory,
  updateProjectCategoryStatus,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('项目类目管理请求契约', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each(['integration', 'development'] as const)(
    '%s 列表和详情由同一管理接口按种类区分，长ID保持原值',
    (kind) => {
      const id = '9007199254740993';
      getProjectCategoryList(kind);
      expect(requestClient.get).toHaveBeenCalledWith(
        '/dataforge/project-category/list',
        { params: { kind } },
      );
      getProjectCategory(kind, id);
      expect(requestClient.get).toHaveBeenLastCalledWith(
        '/dataforge/project-category/get',
        { params: { kind, id } },
      );
    },
  );

  it('修改、状态和批量删除保留完整父子身份，不把数组拼成有损数字', () => {
    const kind = 'integration';
    const id = '9007199254740993';
    const parentId = '9007199254740995';
    const data = {
      kind,
      id,
      parentId,
      name: '类目',
      description: '说明',
      status: 0,
      sort: 0,
    } as const;
    createProjectCategory({ ...data, id: undefined });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/project-category/create',
      { ...data, id: undefined },
    );
    updateProjectCategory(data);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/project-category/update',
      data,
    );
    updateProjectCategoryStatus(kind, id, 1);
    expect(requestClient.put).toHaveBeenLastCalledWith(
      '/dataforge/project-category/update-status',
      { kind, id, status: 1 },
    );
    deleteProjectCategory(kind, id);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/dataforge/project-category/delete',
      { params: { kind, id } },
    );
    deleteProjectCategoryList(kind, [id, parentId]);
    expect(requestClient.delete).toHaveBeenLastCalledWith(
      '/dataforge/project-category/delete-list',
      { params: { kind, ids: [id, parentId] } },
    );
  });

  it('主数据推送类目使用独立接口，读写和批量操作保留长 ID', () => {
    const kind = 'masterPush';
    const base = '/governance/master-push-category';
    const id = '9007199254740993';
    const parentId = '9007199254740995';
    const body = {
      id,
      parentId,
      name: '类目',
      description: '',
      status: 0,
      sort: 0,
    };
    const data = { ...body, kind } as const;
    getProjectCategoryList(kind);
    expect(requestClient.get).toHaveBeenCalledWith(
      `${base}/list`,
    );
    getProjectCategory(kind, id);
    expect(requestClient.get).toHaveBeenLastCalledWith(
      `${base}/get`,
      { params: { id } },
    );
    createProjectCategory(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      `${base}/create`,
      body,
    );
    updateProjectCategory(data);
    expect(requestClient.put).toHaveBeenCalledWith(
      `${base}/update`,
      body,
    );
    updateProjectCategoryStatus(kind, id, 1);
    expect(requestClient.put).toHaveBeenLastCalledWith(
      `${base}/update-status`,
      { id, status: 1 },
    );
    deleteProjectCategory(kind, id);
    expect(requestClient.delete).toHaveBeenCalledWith(
      `${base}/delete`,
      { params: { id } },
    );
    deleteProjectCategoryList(kind, [id, parentId]);
    expect(requestClient.delete).toHaveBeenLastCalledWith(
      `${base}/delete-list`,
      { params: { ids: [id, parentId] } },
    );
  });
});
