import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  deleteConceptModel,
  getConceptModel,
  updateConceptModel,
  updateConceptModelStatus,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe('概念模型线协议', () => {
  beforeEach(() => vi.clearAllMocks());
  it('详情和删除保留超大编号及修订号字符串', async () => {
    const id = '9223372036854775806';
    const revision = '9007199254740993';
    await getConceptModel(id);
    await deleteConceptModel(id, revision);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/modeling/concept-model/get',
      { params: { id } },
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/modeling/concept-model/delete',
      { params: { id, expectedRevision: revision } },
    );
  });
  it('编辑和状态切换明确提交读取时的修订号', async () => {
    const data = {
      id: '9007199254740995',
      expectedRevision: '9007199254740993',
      name: '客户',
      code: 'customer',
      dataDomainId: '9007199254740994',
      subjectDomainId: null,
      design: { entities: [], relations: [] },
    };
    await updateConceptModel(data);
    await updateConceptModelStatus(data.id, data.expectedRevision, 'ACTIVE');
    expect(requestClient.put).toHaveBeenCalledWith(
      '/modeling/concept-model/update',
      data,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/modeling/concept-model/update-status',
      {
        id: data.id,
        expectedRevision: data.expectedRevision,
        status: 'ACTIVE',
      },
    );
  });
});
