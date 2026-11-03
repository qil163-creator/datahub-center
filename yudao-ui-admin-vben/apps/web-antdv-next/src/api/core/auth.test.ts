import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getAuthPermissionInfoApi } from './auth';

vi.mock('#/api/request', () => ({
  baseRequestClient: {},
  requestClient: { get: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('登录权限信息传输', () => {
  it.each([1, '9223372036854775807'])('将真实 id 字段无损转换为用户标识 %s', async (id) => {
    const response = {
      menus: [],
      permissions: [],
      roles: [],
      user: { id, avatar: '', nickname: '测试用户', username: 'test', deptId: 1 },
    };
    vi.mocked(requestClient.get).mockResolvedValue(response);
    const result = await getAuthPermissionInfoApi();
    expect(result.user).toEqual({ ...response.user, homePath: '', userId: String(id) });
    expect(result.permissions).toEqual([]);
    expect(requestClient.get).toHaveBeenCalledWith('/system/auth/get-permission-info');
    expect(response.user).not.toHaveProperty('userId');
  });

  it.each([undefined, 0, -1, 'invalid', 9_223_372_036_854_776_000])('拒绝无效或已丢失精度的身份 %s', async (id) => {
    vi.mocked(requestClient.get).mockResolvedValue({ user: { id } });
    await expect(getAuthPermissionInfoApi()).rejects.toThrow('登录用户编号无效');
  });
});
