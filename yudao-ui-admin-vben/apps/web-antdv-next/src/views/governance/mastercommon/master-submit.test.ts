import { expect, it } from 'vitest';

import { masterSubmitCommand } from './master-submit';

it('超时后的相同变更复用请求号，成功后新的变更生成新请求号', () => {
  const data = {
    modelId: '9007199254740995',
    operation: 'CREATE' as const,
    rows: [{ id: '9007199254740997', amount: '123456789012345678.123456' }],
  };
  const original = masterSubmitCommand('tenant-a', data);
  const retry = masterSubmitCommand('tenant-a', { ...data });
  expect(retry.data.requestId).toBe(original.data.requestId);
  expect(retry.data.rows[0]).toEqual(data.rows[0]);
  original.complete();
  const next = masterSubmitCommand('tenant-a', data);
  expect(next.data.requestId).not.toBe(original.data.requestId);
  next.complete();
});

it('租户、用户和变更内容隔离请求号', () => {
  const data = {
    modelId: '1',
    operation: 'UPDATE' as const,
    rows: [{ id: '2', name: '甲' }],
  };
  const original = masterSubmitCommand('tenant-a/user-a', data);
  const tenant = masterSubmitCommand('tenant-b/user-a', data);
  const user = masterSubmitCommand('tenant-a/user-b', data);
  const changed = masterSubmitCommand('tenant-a/user-a', {
    ...data,
    rows: [{ id: '2', name: '乙' }],
  });
  expect(
    new Set(
      [original, tenant, user, changed].map(
        (command) => command.data.requestId,
      ),
    ).size,
  ).toBe(4);
  for (const command of [original, tenant, user, changed]) command.complete();
});
