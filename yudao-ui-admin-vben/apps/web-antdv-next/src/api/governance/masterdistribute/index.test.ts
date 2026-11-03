import { expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getMasterDistribute,
  getMasterDistributeLog,
  getMasterDistributePage,
  getPushModel,
  getPushModelList,
  handRunMasterDistribute,
  resolveMasterDistributeRun,
  retryMasterDistribute,
  stopMasterDistributeRun,
  updateMasterDistributeStatus,
} from './index';
vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn() },
}));
it('停止以运行编号提交，保留大整数和原因并直接返回持久回执', async () => {
  const command = { recordId: '9007199254740995', reason: '维护停止' };
  const receipt = {
    id: command.recordId,
    runStatus: 5,
    stopRequestedAt: 1_790_488_800_000,
  };
  vi.mocked(requestClient.put).mockResolvedValue(receipt);
  expect(await stopMasterDistributeRun(command)).toBe(receipt);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-distribute/stop',
    command,
  );
});
it('rEST 启停仅修改状态，不隐式发布或发送，重试使用独立端点', async () => {
  const id = '9007199254740999';
  vi.mocked(requestClient.put).mockClear().mockResolvedValue(true);
  await updateMasterDistributeStatus(id, 1);
  expect(requestClient.put).toHaveBeenCalledExactlyOnceWith(
    '/governance/master-distribute/update-status',
    undefined,
    { params: { id, status: 1 } },
  );
  await retryMasterDistribute(id);
  expect(requestClient.put).toHaveBeenLastCalledWith(
    '/governance/master-distribute/retry',
    undefined,
    { params: { id } },
  );
});
it('使用推送专用模型权限入口并传递执行结果筛选', async () => {
  const id = '9007199254740999';
  await getPushModelList();
  expect(requestClient.get).toHaveBeenLastCalledWith(
    '/governance/master-distribute/model-list',
  );
  await getPushModel(id);
  expect(requestClient.get).toHaveBeenLastCalledWith(
    '/governance/master-distribute/model',
    { params: { id } },
  );
  const params = {
    pageNo: 2,
    pageSize: 10,
    name: '下游系统',
    latestRunStatus: 4,
  };
  await getMasterDistributePage(params);
  expect(requestClient.get).toHaveBeenLastCalledWith(
    '/governance/master-distribute/page',
    { params },
  );
});
it('派发查询和执行保留大整数编号，不重复解包占位状态', async () => {
  const id = '9007199254740993';
  const result = { id, blockingRunStatus: 5, nextRunTime: 1_790_488_800_000 };
  vi.mocked(requestClient.get).mockResolvedValue(result);
  expect(await getMasterDistribute(id)).toBe(result);
  expect(requestClient.get).toHaveBeenCalledWith(
    `/governance/master-distribute/get?id=${id}`,
  );
  await handRunMasterDistribute(id);
  expect(requestClient.put).toHaveBeenCalledWith(
    `/governance/master-distribute/hand-run?id=${id}`,
  );
});

it('核查命令保留请求号、大编号与完整证据，直接返回解包回执', async () => {
  const command = {
    recordId: '9007199254740995',
    requestId: '12345678-1234-1234-1234-123456789012',
    outcome: 'NO_EFFECT' as const,
    reason: '核查全部输出',
    confirmedStopped: true as const,
    outputReviewed: true as const,
    stoppedEvidenceRef: 'CASE-STOP-1',
    outputEvidenceRef: 'CASE-OUTPUT-1',
  };
  const receipt = {
    id: command.recordId,
    runStatus: 6,
    resolvedBy: '9007199254740997',
    resolvedAt: 1_790_488_800_000,
  };
  vi.mocked(requestClient.put).mockResolvedValue(receipt);
  expect(await resolveMasterDistributeRun(command)).toBe(receipt);
  expect(requestClient.put).toHaveBeenCalledWith(
    '/governance/master-distribute/resolve',
    command,
  );
  vi.mocked(requestClient.get).mockResolvedValue(receipt);
  expect(await getMasterDistributeLog(command.recordId)).toBe(receipt);
  expect(requestClient.get).toHaveBeenCalledWith(
    '/governance/master-distribute/log-get',
    { params: { id: command.recordId } },
  );
});
