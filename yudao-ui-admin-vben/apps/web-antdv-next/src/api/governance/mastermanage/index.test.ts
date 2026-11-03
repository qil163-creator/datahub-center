import { beforeEach, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getMasterModel,
  getMasterModelPage,
  verifyMasterModelRelease,
} from '../masterdata';
import { deleteMasterDomainList, getMasterDomainPage } from '../masterdomain';
import {
  approveMasterData,
  getMasterApprovalPage,
  getMasterChangeLogPage,
  getMasterCodeRulePage,
  previewMasterCodeRule,
  submitMasterData,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

it('域、模型分页和批量删除使用无损ID以及真实参数协议', async () => {
  const id = '9007199254740995';
  await deleteMasterDomainList([id, '9007199254740997']);
  expect(requestClient.delete).toHaveBeenCalledWith(
    '/governance/master-domain/delete-list',
    { params: { ids: `${id},9007199254740997` } },
  );
  await getMasterDomainPage({
    name: '人员',
    status: 0,
    pageNo: 2,
    pageSize: 10,
  });
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-domain/page',
    { params: { name: '人员', status: 0, pageNo: 2, pageSize: 10 } },
  );
  await getMasterModelPage({ domainId: id, pageNo: 1, pageSize: 10 });
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-model/page',
    { params: { domainId: id, pageNo: 1, pageSize: 10 } },
  );
  await getMasterModel(id);
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-model/get',
    { params: { id } },
  );
  await verifyMasterModelRelease(id);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-model/verify-release',
    undefined,
    { params: { id } },
  );
});

it('维护提交保留原请求号、十进制与大ID，并直接返回回执', async () => {
  const data = {
    modelId: '9007199254740995',
    requestId: '12345678-1234-1234-1234-123456789012',
    operation: 'UPDATE' as const,
    rows: [{ id: '9007199254740997', amount: '123456789012345678.123456' }],
  };
  const receipt = {
    id: '9007199254740999',
    status: 'PENDING',
    approvalRequired: true,
  };
  vi.mocked(requestClient.post).mockResolvedValue(receipt);
  expect(await submitMasterData(data)).toBe(receipt);
  expect(requestClient.post).toHaveBeenCalledWith(
    '/governance/master-data/submit',
    data,
  );
  await approveMasterData({ id: receipt.id, remark: '核对通过' });
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-approval/approve',
    { id: receipt.id, remark: '核对通过' },
  );
});

it('规则预览和只读查询保持DTO与领域枚举', async () => {
  const data = {
    modelId: '9007199254740995',
    code: 'PERSON',
    name: '人员编码',
    fieldName: 'code',
    status: 0,
    segments: [{ segmentType: 'SEQUENCE' as const, segmentValue: '6' }],
  };
  vi.mocked(requestClient.post).mockResolvedValue('000001');
  expect(await previewMasterCodeRule(data)).toBe('000001');
  expect(requestClient.post).toHaveBeenCalledWith(
    '/governance/master-code-rule/preview',
    data,
    { silentError: true },
  );
  await getMasterCodeRulePage({
    modelId: data.modelId,
    pageNo: 1,
    pageSize: 10,
  });
  await getMasterApprovalPage({
    operation: 'UPDATE',
    status: 'UNKNOWN',
    pageNo: 1,
    pageSize: 10,
  });
  await getMasterChangeLogPage({
    changeType: 'CREATE',
    pageNo: 1,
    pageSize: 10,
  });
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-change-log/page',
    { params: { changeType: 'CREATE', pageNo: 1, pageSize: 10 } },
  );
});
