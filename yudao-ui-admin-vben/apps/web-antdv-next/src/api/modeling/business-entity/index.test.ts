import { beforeEach, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { deleteBusinessEntityList, getBusinessEntityOptions, getBusinessEntityPage, updateBusinessEntityStatus } from './index';
vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());
it('业务实体分页与关联选项保持板块/主题范围和无损编号', async () => {
  const dataDomainId = '9007199254740995';
  const subjectDomainId = '9007199254740997';
  const query = { dataDomainId, subjectDomainId, pageNo: 1, pageSize: 10, status: 0 as const, sortOrder: 'asc' as const };
  await getBusinessEntityPage(query);
  expect(requestClient.get).toHaveBeenCalledWith('/modeling/business-entity/page', { params: query });
  await getBusinessEntityOptions(dataDomainId, subjectDomainId);
  expect(requestClient.get).toHaveBeenLastCalledWith('/modeling/business-entity/options', { params: { dataDomainId, excludeId: subjectDomainId } });
});
it('状态更新和批量删除携带原始修订号，删除目标通过JSON请求体传输', async () => {
  const target = { id: '9007199254740995', expectedRevision: '9007199254740997' };
  await updateBusinessEntityStatus({ ...target, status: 0 });
  expect(requestClient.put).toHaveBeenCalledWith('/modeling/business-entity/update-status', { ...target, status: 0 });
  await deleteBusinessEntityList([target]);
  expect(requestClient.delete).toHaveBeenCalledWith('/modeling/business-entity/delete-list', { data: { targets: [target] } });
});
