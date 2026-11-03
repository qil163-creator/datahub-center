import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import * as api from './monitor';

vi.mock('#/api/request', () => ({
  requestClient: { delete: vi.fn(), get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('database monitoring transport', () => {
  const databaseId = '9007199254740993';
  const id = '9007199254740995';

  it('scopes all paged records and details to the selected database', async () => {
    const params = { databaseId, pageNo: 2, pageSize: 10 };
    await api.getDatabaseMonitorLogPage(params);
    await api.getDatabaseAlarmPage(params);
    await api.getDatabaseRulePage(params);
    await api.getDatabaseAlarm(databaseId, id);
    await api.getDatabaseRule(databaseId, id);
    expect(vi.mocked(requestClient.get).mock.calls).toEqual([
      ['/dataforge/database-monitor/log/page', { params }],
      ['/dataforge/database-monitor/alarm/page', { params }],
      ['/dataforge/database-monitor/rule/page', { params }],
      ['/dataforge/database-monitor/alarm/get', { params: { databaseId, id } }],
      ['/dataforge/database-monitor/rule/get', { params: { databaseId, id } }],
    ]);
  });

  it('keeps decimal thresholds and large IDs unchanged through rule writes', async () => {
    const rule: api.DatabaseMonitorApi.RuleSave = {
      databaseId,
      id,
      name: '响应时间',
      metric: 'RESPONSE_TIME',
      operator: 'GT',
      threshold: '3000.0000000000000001',
      level: 'SERIOUS',
      status: 0,
      owner: '',
    };
    await api.createDatabaseRule(rule);
    await api.updateDatabaseRule(rule);
    await api.updateDatabaseRuleStatus({ databaseId, id, status: 1 });
    await api.deleteDatabaseRule(databaseId, id);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/database-monitor/rule/create',
      rule,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/database-monitor/rule/update',
      rule,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/database-monitor/rule/status',
      { databaseId, id, status: 1 },
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/dataforge/database-monitor/rule/delete',
      { params: { databaseId, id } },
    );
  });

  it('sends an explicit alarm disposition and its explanation', async () => {
    const command = {
      databaseId,
      id,
      action: 'IGNORE' as const,
      remark: '已确认维护窗口',
    };
    await api.handleDatabaseAlarm(command);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/database-monitor/alarm/handle',
      command,
    );
  });

  it('loads trend parameters and uses POST to collect a new sample', async () => {
    await api.getDatabaseMonitorOverview(databaseId);
    await api.getDatabaseMonitorTrend(databaseId, 15);
    await api.collectDatabaseMonitor(databaseId);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/database-monitor/overview',
      { params: { databaseId } },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/database-monitor/trend',
      { params: { databaseId, days: 15 } },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/database-monitor/collect',
      undefined,
      { params: { databaseId } },
    );
  });
});
