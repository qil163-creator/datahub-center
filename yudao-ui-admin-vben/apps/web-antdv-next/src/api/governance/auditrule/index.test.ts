import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  getAuditRuleDimensionList,
  getAuditRulePage,
  updateAuditRule,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn() },
}));

describe('稽查规则独立维度契约', () => {
  beforeEach(() => vi.clearAllMocks());

  it('维度列表读取独立端点并保留名称、编码及Long', async () => {
    const rows = [
      {
        id: '9223372036854775807',
        name: '准确性',
        code: 'ACCURACY',
        status: 0,
        sort: 1,
        remark: '说明',
      },
    ];
    vi.mocked(requestClient.get).mockResolvedValue(rows);
    expect(await getAuditRuleDimensionList()).toEqual(rows);
    expect(requestClient.get).toHaveBeenCalledExactlyOnceWith(
      '/governance/audit-rule/dimension-list',
    );
  });

  it('分页和保存提交独立dimensionId，不转成业务categoryId或有损数字', async () => {
    const dimensionId = '9223372036854775807';
    const params = { pageNo: 1, pageSize: 10, dimensionId };
    await getAuditRulePage(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/audit-rule/page',
      { params },
    );
    await updateAuditRule({
      id: '9007199254740995',
      name: '规则',
      dimensionId,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/audit-rule/update',
      { id: '9007199254740995', name: '规则', dimensionId },
    );
  });
});
