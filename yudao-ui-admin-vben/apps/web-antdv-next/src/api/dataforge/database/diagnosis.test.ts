import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { diagnoseDatabase } from './index';

vi.mock('#/api/request', () => ({ requestClient: { post: vi.fn() } }));
beforeEach(() => vi.clearAllMocks());

describe('数据库诊断请求', () => {
  it('已保存连接仅传无损数据库 ID 与显式写入开关，并保留足够的等待时限', async () => {
    const signal = new AbortController().signal;
    const payload = {
      databaseId: '9223372036854775806',
      includeWriteChecks: false,
    };
    await diagnoseDatabase(payload, signal);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/database/diagnose',
      payload,
      { signal, timeout: 60_000 },
    );
    expect(payload).not.toHaveProperty('connection');
  });
  it('未保存或修改中的表单使用连接分支，密码只进入当前请求且不产生保存请求', async () => {
    const connection = {
      id: '9007199254740993',
      databaseType: 1,
      databaseIp: 'db.test',
      databasePort: '5432',
      userName: 'reader',
      password: 'fixture-only',
    };
    await diagnoseDatabase({ connection, includeWriteChecks: true });
    expect(requestClient.post).toHaveBeenCalledExactlyOnceWith(
      '/dataforge/database/diagnose',
      { connection, includeWriteChecks: true },
      { signal: undefined, timeout: 60_000 },
    );
  });
});
