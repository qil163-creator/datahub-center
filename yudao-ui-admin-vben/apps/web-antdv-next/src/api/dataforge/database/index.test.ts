import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  deleteDatabase,
  deleteDatabaseList,
  getDatabase,
  testDatabase,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { delete: vi.fn(), get: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe('database API transport', () => {
  const id = '9007199254740993';

  it('loads a detail without rounding its ID or converting timestamp fields', async () => {
    const detail = {
      id,
      orgId: '9007199254740995',
      databaseType: 1,
      createTime: 1_792_000_000_000,
      updateTime: 1_792_000_060_000,
    };
    vi.mocked(requestClient.get).mockResolvedValue(detail);

    expect(await getDatabase(id)).toBe(detail);
    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/database/get', {
      params: { id },
    });
  });

  it('preserves large IDs when testing and deleting a database', async () => {
    await testDatabase(id);
    await deleteDatabase(id);

    expect(requestClient.get).toHaveBeenCalledWith('/dataforge/database/test', {
      params: { id },
    });
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/dataforge/database/delete',
      { params: { id } },
    );
  });

  it('passes mixed safe numeric and large string IDs to the array serializer', async () => {
    const ids = [9, id];
    await deleteDatabaseList(ids);

    expect(requestClient.delete).toHaveBeenCalledWith(
      '/dataforge/database/delete-list',
      { params: { ids } },
    );
  });
});
