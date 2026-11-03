import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createDatabase,
  diagnoseDatabase,
  testDatabaseConnection,
  updateDatabase,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('数据源密码提交契约', () => {
  it('只读凭据标记不进入创建、更新和两种测连接口的请求', () => {
    const values = {
      id: '9007199254740993',
      userName: 'fixture-user',
      hasPassword: true,
    };
    createDatabase(values);
    updateDatabase(values);
    testDatabaseConnection(values);
    diagnoseDatabase({ connection: values });
    for (const call of [
      ...vi.mocked(requestClient.post).mock.calls,
      ...vi.mocked(requestClient.put).mock.calls,
    ]) {
      const request = call[1] as { connection?: unknown };
      expect(request.connection ?? request).not.toHaveProperty('hasPassword');
    }
    expect(values.hasPassword).toBe(true);
  });
  it.each(['', null, undefined])(
    '编辑密码 %s 保持空值，不伪造旧密码',
    (password) => {
      const values = {
        id: '9007199254740993',
        userName: 'review_user',
        password,
      };
      updateDatabase(values);
      expect(requestClient.put).toHaveBeenCalledWith(
        '/dataforge/database/update',
        values,
      );
      expect(
        JSON.parse(
          JSON.stringify(vi.mocked(requestClient.put).mock.calls[0]?.[1]),
        ),
      ).toEqual(
        password === undefined
          ? { id: values.id, userName: values.userName }
          : values,
      );
    },
  );

  it('新输入密码随新目标提交，编号不经数值转换', () => {
    const values = {
      id: '9007199254740993',
      userName: 'review_user',
      password: 'new-test-password',
      jdbcUrl: 'jdbc:postgresql://receiver.example:5432/review',
    };
    testDatabaseConnection(values);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/database/test-connection',
      values,
    );
  });
});
