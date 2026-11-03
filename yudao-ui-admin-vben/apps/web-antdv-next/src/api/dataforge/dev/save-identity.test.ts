import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { createDevTask, updateDevTask } from './index';

vi.mock('#/api/request', () => ({
  requestClient: { post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('任务保存执行身份边界', () => {
  it.each([createDevTask, updateDevTask])(
    '保存剥离历史执行身份，保持原编辑对象和无损编号',
    (save) => {
      const task = {
        id: '9007199254740993',
        expectedRevision: 4,
        executionUserId: '9007199254740995',
        content: 'SELECT 1',
        dependencyTaskIds: ['9007199254740997'],
      };
      save(task);
      const mock = save === createDevTask ? requestClient.post : requestClient.put;
      const payload = vi.mocked(mock).mock.calls[0]?.[1];
      expect(payload).toEqual({
        id: task.id,
        expectedRevision: 4,
        content: task.content,
        dependencyTaskIds: task.dependencyTaskIds,
      });
      expect(JSON.stringify(payload)).not.toContain('executionUserId');
      expect(task.executionUserId).toBe('9007199254740995');
    },
  );
});
