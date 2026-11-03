import type { ModelingDataDomainApi } from './index';

import { beforeEach, expect, expectTypeOf, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { createDataDomain, getDataDomain, updateDataDomain } from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

it('数据域保存契约只包含管理字段，并无损传递负责人和域编号', async () => {
  expectTypeOf<keyof ModelingDataDomainApi.SaveReq>().toEqualTypeOf<
    | 'businessOwnerIds'
    | 'code'
    | 'dataOwnerIds'
    | 'description'
    | 'icon'
    | 'id'
    | 'leaderPhone'
    | 'leaderUserId'
    | 'name'
    | 'namingRules'
    | 'remark'
    | 'status'
  >();
  const id = '9007199254740995';
  const leaderUserId = '9007199254740997';
  const data = {
    name: '人员数据域',
    code: 'PERSON',
    leaderUserId,
    leaderPhone: '13000000000',
    description: '人员基础数据',
    remark: '合成合同数据',
    status: 0,
  } satisfies ModelingDataDomainApi.SaveReq;
  vi.mocked(requestClient.post).mockResolvedValue(id);
  expect(await createDataDomain(data)).toBe(id);
  expect(requestClient.post).toHaveBeenCalledWith(
    '/modeling/data-domain/create',
    data,
  );
  const update = { ...data, id };
  await updateDataDomain(update);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/modeling/data-domain/update',
    update,
  );
  const detail = {
    ...update,
    creator: leaderUserId,
    createTime: 1_791_756_000_000,
  };
  vi.mocked(requestClient.get).mockResolvedValue(detail);
  expect(await getDataDomain(id)).toBe(detail);
  expect(requestClient.get).toHaveBeenCalledWith(
    `/modeling/data-domain/get?id=${id}`,
  );
});
