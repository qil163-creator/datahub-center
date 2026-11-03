import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getTableModelDesign,
  modelSaveData,
  publishTableModel,
  saveTableModelDraft,
} from './tablemodel';

const client = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: client }));
beforeEach(() => vi.clearAllMocks());

describe('modeling tablemodel api', () => {
  it('publishes the current model with lossless model and revision identifiers', async () => {
    client.put.mockResolvedValueOnce(true);
    await publishTableModel('9007199254740993', '9007199254740995');
    expect(client.put).toHaveBeenCalledWith('/modeling/table-model/publish', null, {
      params: { modelId: '9007199254740993', expectedRevision: '9007199254740995' },
    });
  });

  it('reads a single current design without a version selection', async () => {
    await getTableModelDesign('9007199254740993');
    expect(client.get).toHaveBeenCalledWith('/modeling/table-model/design-get', {
      params: { modelId: '9007199254740993' },
    });
  });

  it('saves current fields and type configuration atomically with its original revision', async () => {
    client.put.mockResolvedValueOnce(true);
    const data = { modelId: '9007199254740993', expectedRevision: '9007199254740995', fields: [{ fieldUid: 'stable', fieldName: 'ID' }], typeConfig: null };
    await saveTableModelDraft(data);
    expect(client.put).toHaveBeenCalledWith('/modeling/table-model/draft-save', data);
    expect(data).not.toHaveProperty('versionId');
  });

  it('builds save payload', () => {
    const data = modelSaveData({
      name: 'n',
      code: 'c',
      fields: [],
    } as any);
    expect(data).toBeTruthy();
  });
});
