import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  checkMarketCheck,
  getMarketCheckApplicants,
  getMarketCheckDetail,
  getMarketCheckPage,
} from './check';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

describe('审批工单接口契约', () => {
  it('区分来源并无损传递大编号与冻结修订', async () => {
    const id = '9223372036854775807';
    await getMarketCheckDetail(id, 'QDATA');
    await checkMarketCheck({
      id,
      origin: 'QDATA',
      status: '4',
      approvalReason: '材料不完整',
      expectedRevision: 7,
    });
    expect(requestClient.get).toHaveBeenCalledWith('/asset/market/check/get', {
      params: { id, origin: 'QDATA' },
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/asset/market/check/check',
      {
        id,
        origin: 'QDATA',
        status: '4',
        approvalReason: '材料不完整',
        expectedRevision: 7,
      },
    );
  });

  it('按源申请人键和实际分页查询，不把展示姓名当本地用户编号', async () => {
    const params = {
      pageNo: 2,
      pageSize: 10,
      martName: '河流',
      status: '2',
      applicantKey: 'QDATA:external-user',
    };
    await getMarketCheckPage(params);
    await getMarketCheckApplicants();
    expect(requestClient.get).toHaveBeenCalledWith('/asset/market/check/page', {
      params,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/asset/market/check/applicants',
    );
  });
});
