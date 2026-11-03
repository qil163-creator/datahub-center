import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import * as api from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));
vi.mock('#/api/governance/rule', () => ({ getRulePage: vi.fn() }));
beforeEach(() => vi.clearAllMocks());

describe('governance permission exact schema transport', () => {
  const databaseId = '9223372036854775807';
  const roleId = '9007199254740993';
  const table = { schemaName: 'Gov.Schema', tableName: 'Citizen".One', tableReference: '"Gov.Schema"."Citizen"".One"' };

  it('sends schema and exact quoted reference without lossy id conversion', async () => {
    await api.getPermissionConfig(databaseId, table, roleId);
    expect(requestClient.get).toHaveBeenCalledWith('/governance/safety-permission/config', {
      params: { databaseId, roleId, ...table },
    });
    await api.getPermissionColumnList(databaseId, table);
    expect(requestClient.get).toHaveBeenCalledWith('/governance/safety-permission/column-list', {
      params: { databaseId, ...table },
    });
  });

  it('saves only the explicit physical binding selected by the user', async () => {
    const data = { databaseId, ...table, rolePermissionConfigs: [{ roleId, tablePermission: { permissionType: 1 } }] };
    await api.savePermissionConfig(data);
    expect(requestClient.post).toHaveBeenCalledWith('/governance/safety-permission/save', data);
  });

  it('legacy rebinding is a separate audited operation, not a config-save fallback', async () => {
    const data = { databaseId, roleId, legacyTableName: 'Citizen".One', ...table, note: '已核对物理模式和字段' };
    await api.bindLegacyPermission(data);
    expect(requestClient.post).toHaveBeenCalledWith('/governance/safety-permission/bind-legacy', data);
    expect(requestClient.post).not.toHaveBeenCalledWith('/governance/safety-permission/save', expect.anything());
  });
});
