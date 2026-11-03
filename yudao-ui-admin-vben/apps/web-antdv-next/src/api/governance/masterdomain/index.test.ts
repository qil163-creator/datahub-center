import type { GovernanceMasterDomainApi } from './index';

import { beforeEach, expect, expectTypeOf, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createMasterDomain,
  getMasterDomain,
  getMasterDomainPage,
  updateMasterDomain,
  updateMasterDomainStatus,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

it('三个域筛选使用独立主数据接口及真实分页参数', async () => {
  const params = {
    name: '人员',
    code: 'PERSON',
    status: 0,
    pageNo: 2,
    pageSize: 10,
  };
  const result = { list: [], total: 0 };
  vi.mocked(requestClient.get).mockResolvedValue(result);
  expect(await getMasterDomainPage(params)).toBe(result);
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-domain/page',
    { params },
  );
});

it('域和负责人大ID、备注及审计字段保持无损协议', async () => {
  expectTypeOf<keyof GovernanceMasterDomainApi.SaveReq>().toEqualTypeOf<
    | 'code'
    | 'description'
    | 'id'
    | 'leaderPhone'
    | 'leaderUserId'
    | 'name'
    | 'remark'
    | 'status'
  >();
  const id = '9007199254740995';
  const leaderUserId = '9007199254740997';
  const data = {
    name: '人员主数据域',
    code: 'PERSON',
    leaderUserId,
    leaderPhone: '13000000000',
    description: '统一维护人员主数据',
    remark: '合成合同数据',
    status: 0,
  } satisfies GovernanceMasterDomainApi.SaveReq;
  vi.mocked(requestClient.post).mockResolvedValue(id);
  expect(await createMasterDomain(data)).toBe(id);
  expect(requestClient.post).toHaveBeenCalledWith(
    '/governance/master-domain/create',
    data,
  );
  const update = { ...data, id };
  await updateMasterDomain(update);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-domain/update',
    update,
  );
  const detail = {
    ...update,
    leaderUserName: '合成负责人',
    creator: leaderUserId,
    creatorName: '合成创建人',
    createTime: 1_791_756_000_000,
    updater: leaderUserId,
    updaterName: '合成更新人',
    updateTime: 1_791_756_001_000,
  };
  vi.mocked(requestClient.get).mockResolvedValue(detail);
  expect(await getMasterDomain(id)).toBe(detail);
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-domain/get',
    { params: { id } },
  );
  await updateMasterDomainStatus(id, 1);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-domain/update-status',
    { id, status: 1 },
  );
});
