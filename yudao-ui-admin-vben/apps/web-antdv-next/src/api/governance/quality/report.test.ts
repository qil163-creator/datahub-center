import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  actOnIssueRows,
  getIssueData,
  getIssueDataPage,
  getIssueRows,
  updateIssueStatus,
} from './issue';
import { getQualityReport, getQualityReportPage } from './report';

vi.mock('#/api/request', () => ({
  requestClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    download: vi.fn(),
  },
}));

describe('质量报告与问题处置协议', () => {
  beforeEach(() => vi.clearAllMocks());

  it('列表以任务名称和执行状态筛选，ID 保持十进制字符串', () => {
    getQualityReportPage({
      pageNo: 2,
      pageSize: 10,
      taskName: '测站',
      instanceStatus: 'SUCCESS',
      taskId: '9007199254740993',
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-audit-log/report-page',
      {
        params: {
          pageNo: 2,
          pageSize: 10,
          taskName: '测站',
          instanceStatus: 'SUCCESS',
          taskId: '9007199254740993',
        },
      },
    );
  });

  it('报告按实例和天数读取，不拼接未编码查询字符串', () => {
    getQualityReport('9007199254740995', 30);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-audit-log/report',
      { params: { id: '9007199254740995', days: 30 } },
    );
  });

  it('问题行筛选与 CAS 处置只发送定位键和版本', () => {
    const auditLogId = '9007199254740997';
    getIssueRows({
      auditLogId,
      pageNo: 1,
      pageSize: 10,
      filters: { STCD: '00123' },
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/quality-issue-data/rows',
      { auditLogId, pageNo: 1, pageSize: 10, filters: { STCD: '00123' } },
    );
    actOnIssueRows({
      auditLogId,
      action: 'IGNORE',
      rows: [{ rowKey: 'opaque-row-key', revision: 4 }],
    });
    expect(requestClient.post).toHaveBeenLastCalledWith(
      '/governance/quality-issue-data/row-action',
      {
        auditLogId,
        action: 'IGNORE',
        rows: [{ rowKey: 'opaque-row-key', revision: 4 }],
      },
    );
  });

  it('现有汇总问题查询与状态更新支持超安全范围 ID', () => {
    getIssueData('9007199254740999');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/quality-issue-data/get',
      { params: { id: '9007199254740999' } },
    );
    getIssueDataPage({
      pageNo: 1,
      pageSize: 10,
      instanceId: '9007199254740995',
    });
    expect(requestClient.get).toHaveBeenLastCalledWith(
      '/governance/quality-issue-data/page',
      { params: { pageNo: 1, pageSize: 10, instanceId: '9007199254740995' } },
    );
    updateIssueStatus('9007199254740999', 'PROCESSED');
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/quality-issue-data/update-status',
      null,
      { params: { id: '9007199254740999', issueStatus: 'PROCESSED' } },
    );
  });
});
