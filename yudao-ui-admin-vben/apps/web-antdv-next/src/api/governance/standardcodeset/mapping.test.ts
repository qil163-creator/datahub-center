import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getStandardCodeSetPage, updateStandardCodeSetStatus } from './index';
import {
  createCodeMapping,
  deleteCodeMapping,
  getCodeMapping,
  getCodeMappingPage,
  updateCodeMapping,
} from './mapping';
vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
describe('代码表映射与版本状态接口', () => {
  beforeEach(() => vi.clearAllMocks());
  it('keeps mapping identity, target code item and revision lossless', () => {
    const id = '9007199254740993';
    const codeSetId = 23;
    const data = {
      id,
      codeSetId,
      originalValue: '原始值',
      codeItemId: '9007199254740995',
      revision: '9007199254740997',
    };
    updateCodeMapping(data);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/standard-code-set/mapping-update',
      data,
    );
    getCodeMapping(codeSetId, id);
    expect(requestClient.get).toHaveBeenLastCalledWith(
      '/governance/standard-code-set/mapping-get',
      { params: { codeSetId, id } },
    );
    deleteCodeMapping(codeSetId, id);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/governance/standard-code-set/mapping-delete',
      { params: { codeSetId, id } },
    );
    createCodeMapping({
      codeSetId,
      originalValue: '源',
      codeItemId: data.codeItemId,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/standard-code-set/mapping-create',
      { codeSetId, originalValue: '源', codeItemId: data.codeItemId },
    );
  });
  it('sends explicit pagination and sorting independently of business filters', () => {
    const params = {
      codeSetId: '23',
      pageNo: 2,
      pageSize: 20,
      originalValue: '2',
      sortField: 'id' as const,
      sortOrder: 'asc' as const,
    };
    getCodeMappingPage(params);
    expect(requestClient.get).toHaveBeenLastCalledWith(
      '/governance/standard-code-set/mapping-page',
      { params },
    );
    const filter = {
      pageNo: 1,
      pageSize: 10,
      categoryId: '9007199254740993',
      name: '中文',
      type: 'code',
      sortField: 'createTime' as const,
      sortOrder: 'desc' as const,
    };
    getStandardCodeSetPage(filter);
    expect(requestClient.get).toHaveBeenLastCalledWith(
      '/governance/standard-code-set/page',
      { params: filter },
    );
  });
  it('uses the CAS status endpoint without submitting an obsolete definition', () => {
    updateStandardCodeSetStatus('23', 1, '9007199254740993');
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/standard-code-set/update-status',
      undefined,
      { params: { id: '23', status: 1, revision: '9007199254740993' } },
    );
  });
});
