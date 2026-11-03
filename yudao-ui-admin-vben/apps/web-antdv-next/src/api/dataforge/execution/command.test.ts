import { describe, expect, it, vi } from 'vitest';

import { createCommandTracker, createRunCommand } from './command';

describe('durable run commands', () => {
  it('keeps long IDs and a stable command for explicit retries', () => {
    const command = createRunCommand('9007199254740993');
    expect(command.taskId).toBe('9007199254740993');
    expect(command.requestId).toBeTruthy();
    expect(command.requestId.length).toBeLessThanOrEqual(64);
  });

  it('reuses the same request after a lost response and deduplicates concurrent clicks', async () => {
    const tracker = createCommandTracker(() => 'tenant-a');
    const payload = { taskId: '9007199254740993' };
    const send = vi
      .fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValue({ recordId: '9' });
    await expect(tracker.execute('run:9', payload, send)).rejects.toThrow(
      'network',
    );
    await tracker.execute('run:9', payload, send);
    expect(send.mock.calls[0]?.[0]).toEqual(send.mock.calls[1]?.[0]);
    let finish!: (value: string) => void;
    const blocked = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const first = tracker.execute('stop:9', { recordId: '9' }, blocked);
    expect(
      await tracker.execute('stop:9', { recordId: '9' }, blocked),
    ).toBeUndefined();
    expect(blocked).toHaveBeenCalledTimes(1);
    finish('accepted');
    expect(await first).toBe('accepted');
  });

  it('does not replace an unresolved command with changed content', async () => {
    const tracker = createCommandTracker(() => 'tenant-a');
    const send = vi.fn().mockRejectedValue(new Error('network'));
    await expect(
      tracker.execute('resolve:9', { expectedVersion: '7' }, send),
    ).rejects.toThrow('network');
    await expect(
      tracker.execute('resolve:9', { expectedVersion: '8' }, send),
    ).rejects.toThrow('核对');
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('invalidates responses and command identity on tenant or component lifetime changes', async () => {
    let scope = 'tenant-a';
    const tracker = createCommandTracker(() => scope);
    let finish!: (value: string) => void;
    const send = vi.fn(
      (_command: { requestId: string }) =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const first = tracker.execute('run:9', { taskId: '9' }, send);
    scope = 'tenant-b';
    finish('old tenant receipt');
    expect(await first).toBeUndefined();
    const firstId = send.mock.calls[0]?.[0].requestId;
    tracker.invalidate();
    const next = vi.fn().mockResolvedValue('new tenant');
    expect(await tracker.execute('run:9', { taskId: '9' }, next)).toBe(
      'new tenant',
    );
    expect(next.mock.calls[0]?.[0].requestId).not.toBe(firstId);
  });
});
