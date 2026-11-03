import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { preflightWritePermission, savePermissionConfig } from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));

describe('独立写授权契约', () => {
  beforeEach(() => vi.clearAllMocks());
  it('预检保留大编号及精确十进制文本，不允许注入操作者和租户', async () => {
    const value = {
      databaseId: '9223372036854775807',
      roleId: '9007199254740993',
      sql: 'UPDATE public.cases SET amount=? WHERE id=?',
      parameterValues: [
        '12345678901234567890.123456789',
        '9007199254740993',
        null,
      ],
      tenantId: 2,
      actorId: 99,
    };
    await preflightWritePermission(value);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/safety-permission/write-preflight',
      {
        databaseId: value.databaseId,
        roleId: value.roleId,
        sql: value.sql,
        parameterValues: value.parameterValues,
      },
    );
  });
  it('写策略和撤销理由沿既有配置保存，读取枚举不隐式生成写权', async () => {
    const value = {
      databaseId: '9007199254740993',
      schemaName: 'public',
      tableName: 'cases',
      tableReference: 'public.cases',
      rolePermissionConfigs: [
        {
          roleId: '20',
          tablePermission: {
            permissionType: 2,
            writePolicy: null,
            writeChangeReason: '部门授权到期，撤销写入权限',
          },
        },
      ],
    };
    await savePermissionConfig(value);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/safety-permission/save',
      value,
    );
  });
});
