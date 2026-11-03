import { expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createDataPushTask,
  previewDataPushTask,
  resolveDataPushRun,
  updateDataPushTask,
} from './index';
vi.mock('#/api/request', () => ({
  requestClient: { post: vi.fn(), put: vi.fn() },
}));
it('核查合同走管理网关且不转换大整数或重复解包', async () => {
  const payload = {
    recordId: '9007199254740995',
    requestId: '761a1a47-efef-43a4-af4c-650d885f6a26',
    outcome: 'NO_EFFECT' as const,
    confirmedStopped: true as const,
    outputReviewed: true as const,
    reason: '已核查',
    stoppedEvidenceRef: 'STOP-1',
    outputEvidenceRef: 'OUTPUT-1',
  };
  const result = {
    id: payload.recordId,
    runStatus: 6,
    resolvedBy: '9007199254740997',
  };
  vi.mocked(requestClient.post).mockResolvedValue(result);
  expect(await resolveDataPushRun(payload)).toBe(result);
  expect(requestClient.post).toHaveBeenCalledWith(
    '/dataforge/data-push-task/resolve-command',
    payload,
  );
});

it('任务保存与预览一次传递完整接收端，不转换 ID 或持久化预览', async () => {
  const data = {
    name: '人口推送',
    sourceDatabaseId: '9007199254740995',
    pushConfig: {
      name: '人口推送',
      channelType: 1,
      config: { http: { url: 'https://consumer.example.com/push' } },
    },
  };
  const taskId = '9007199254740993';
  vi.mocked(requestClient.post).mockResolvedValue(taskId);
  expect(await createDataPushTask(data)).toBe(taskId);
  expect(requestClient.post).toHaveBeenLastCalledWith(
    '/dataforge/data-push-task/create',
    data,
  );
  const update = {
    ...data,
    id: taskId,
    pushConfig: { ...data.pushConfig, id: '9007199254740997' },
  };
  vi.mocked(requestClient.put).mockResolvedValue(true);
  expect(await updateDataPushTask(update)).toBe(true);
  expect(requestClient.put).toHaveBeenLastCalledWith(
    '/dataforge/data-push-task/update',
    update,
  );
  const preview = { rowCount: 1 };
  vi.mocked(requestClient.post).mockResolvedValue(preview);
  expect(await previewDataPushTask({ ...update, limit: 10 })).toBe(preview);
  expect(requestClient.post).toHaveBeenLastCalledWith(
    '/dataforge/data-push-task/preview',
    { ...update, limit: 10 },
  );
});
