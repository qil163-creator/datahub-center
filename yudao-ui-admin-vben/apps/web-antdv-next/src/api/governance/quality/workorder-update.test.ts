import type { QualityWorkOrderApi } from './workorder';

import { message } from 'antdv-next';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { createWorkOrder, updateWorkOrder } from './workorder';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));
vi.mock('antdv-next', () => ({ message: { error: vi.fn() } }));
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(requestClient.put).mockResolvedValue(true);
});

describe('work-order update transport', () => {
  it.each([
    { id: '9007199254740993', expectedVersion: 0 },
    { id: 42, expectedVersion: 7 },
    { id: '9223372036854775807', expectedVersion: 2_147_483_646 },
  ])(
    'preserves the opened version $expectedVersion and ID $id, returning true',
    async ({ id, expectedVersion }) => {
      const issueIds = ['9223372036854775807', 23];
      const data: QualityWorkOrderApi.UpdateRequest = {
        id,
        orderName: 'fixture',
        expectedVersion,
        taskId: '9007199254740995',
        ownerUserId: '9007199254740997',
        handlingDeptId: '9007199254740999',
        confirmerUserId: '9007199254741001',
        deadline: 1_790_488_800_000,
        description: 'fixture description',
        problemType: 'fixture problem',
        severity: 'fixture severity',
        sourceType: 'fixture source',
        issueIds,
      };
      const before = { ...data, issueIds: [...issueIds] };
      Object.freeze(issueIds);
      Object.freeze(data);
      const result: Promise<boolean> = updateWorkOrder(data);

      await expect(result).resolves.toBe(true);
      expect(requestClient.put).toHaveBeenCalledExactlyOnceWith(
        '/governance/quality-work-order/update',
        before,
      );
      expect(data).toEqual(before);
      expect(requestClient.get).not.toHaveBeenCalled();
      expect(requestClient.post).not.toHaveBeenCalled();
    },
  );

  it('rejects a legacy update with no version before any transport call', async () => {
    const data = Object.freeze({ id: 42, orderName: 'fixture' });

    await expect(
      updateWorkOrder(data as unknown as QualityWorkOrderApi.UpdateRequest),
    ).rejects.toThrow(new Error('工单更新版本必须为 0 到 2147483646 的整数'));
    expect(data).not.toHaveProperty('expectedVersion');
    expect(requestClient.put).not.toHaveBeenCalled();
    expect(requestClient.get).not.toHaveBeenCalled();
    expect(requestClient.post).not.toHaveBeenCalled();
    expect(message.error).not.toHaveBeenCalled();
  });

  it.each([
    { label: 'undefined', version: undefined },
    { label: 'null', version: null },
    { label: 'negative', version: -1 },
    { label: 'fraction', version: 0.5 },
    { label: 'CAS overflow', version: 2_147_483_647 },
    { label: 'large integer', version: Number.MAX_SAFE_INTEGER },
    { label: 'NaN', version: Number.NaN },
    { label: 'positive infinity', version: Number.POSITIVE_INFINITY },
    { label: 'negative infinity', version: Number.NEGATIVE_INFINITY },
    { label: 'numeric string', version: '7' },
    { label: 'unsafe string', version: 'unsafe-version-value' },
    { label: 'boolean', version: true },
  ])('rejects $label with a fixed Error before transport', async ({ version }) => {
    const data = Object.freeze({
      id: '9223372036854775807',
      orderName: 'fixture',
      expectedVersion: version,
    });

    await expect(
      updateWorkOrder(data as unknown as QualityWorkOrderApi.UpdateRequest),
    ).rejects.toThrow(new Error('工单更新版本必须为 0 到 2147483646 的整数'));
    expect(data.expectedVersion).toBe(version);
    expect(requestClient.put).not.toHaveBeenCalled();
    expect(requestClient.get).not.toHaveBeenCalled();
    expect(requestClient.post).not.toHaveBeenCalled();
    expect(message.error).not.toHaveBeenCalled();
  });

  it('propagates a server conflict after one PUT without refresh, retry or mutation', async () => {
    const issueIds = ['9007199254740995'];
    const data: QualityWorkOrderApi.UpdateRequest = {
      id: '9223372036854775807',
      orderName: 'fixture',
      expectedVersion: 3,
      issueIds,
    };
    const before = { ...data, issueIds: [...issueIds] };
    Object.freeze(issueIds);
    Object.freeze(data);
    const conflict = new Error('fixture conflict');
    vi.mocked(requestClient.get).mockResolvedValue({ lockVersion: 4 });
    vi.mocked(requestClient.put)
      .mockRejectedValueOnce(conflict)
      .mockResolvedValue(true);

    await expect(updateWorkOrder(data)).rejects.toBe(conflict);
    expect(requestClient.put).toHaveBeenCalledExactlyOnceWith(
      '/governance/quality-work-order/update',
      before,
    );
    expect(requestClient.get).not.toHaveBeenCalled();
    expect(requestClient.post).not.toHaveBeenCalled();
    expect(data).toEqual(before);
  });

  it('keeps creation on its original endpoint without requiring a version', async () => {
    const issueIds = ['9223372036854775807'];
    const data: QualityWorkOrderApi.WorkOrder = {
      orderName: 'fixture',
      taskId: '9007199254740993',
      issueIds,
    };
    const before = { ...data, issueIds: [...issueIds] };
    Object.freeze(issueIds);
    Object.freeze(data);
    const createdId = '9007199254740995';
    vi.mocked(requestClient.post).mockResolvedValue(createdId);

    await expect(createWorkOrder(data)).resolves.toBe(createdId);
    expect(requestClient.post).toHaveBeenCalledExactlyOnceWith(
      '/governance/quality-work-order/create',
      before,
    );
    expect(vi.mocked(requestClient.post).mock.calls[0]?.[1]).not.toHaveProperty(
      'expectedVersion',
    );
    expect(data).toEqual(before);
    expect(requestClient.put).not.toHaveBeenCalled();
    expect(requestClient.get).not.toHaveBeenCalled();
  });
});
