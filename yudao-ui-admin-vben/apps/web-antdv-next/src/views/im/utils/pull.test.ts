import type { DbClient } from './db';

import { describe, expect, it, vi } from 'vitest';

import { runIncrementalPull, runMinIdPull } from './pull';

function cursorStore(initial?: { lastId?: number; lastUpdateTime?: number }) {
  const getSetting = vi.fn().mockResolvedValue(initial);
  const setSetting = vi.fn().mockResolvedValue(undefined);
  return {
    db: { getSetting, setSetting } as unknown as DbClient,
    setSetting,
  };
}

describe('im incremental pull cursors', () => {
  it('starts without a timestamp and persists the first applied page', async () => {
    const { db, setSetting } = cursorStore();
    const fetchPage = vi.fn().mockResolvedValue([{ id: 7, updateTime: 12_000 }]);
    await runIncrementalPull(db, 'cursor', fetchPage, () => true);
    expect(fetchPage).toHaveBeenCalledWith({
      lastId: undefined,
      lastUpdateTime: undefined,
      limit: 100,
    });
    expect(setSetting).toHaveBeenCalledWith('cursor', {
      lastId: 7,
      lastUpdateTime: 12_000,
    });
  });

  it('replays the overlap without moving the durable high water backwards', async () => {
    const { db, setSetting } = cursorStore({ lastId: 9, lastUpdateTime: 20_000 });
    const fetchPage = vi.fn().mockResolvedValue([{ id: 7, updateTime: 17_000 }]);
    await runIncrementalPull(db, 'cursor', fetchPage, () => true);
    expect(fetchPage).toHaveBeenCalledWith({
      lastId: 0,
      lastUpdateTime: 15_000,
      limit: 100,
    });
    expect(setSetting).not.toHaveBeenCalled();
  });

  it('does not persist missing timestamps or an unapplied page', async () => {
    for (const applied of [true, false]) {
      const { db, setSetting } = cursorStore();
      const fetchPage = vi.fn().mockResolvedValue([{ id: 7 }]);
      await runIncrementalPull(db, 'cursor', fetchPage, () => applied);
      expect(setSetting).not.toHaveBeenCalled();
      expect(fetchPage).toHaveBeenCalledTimes(1);
    }
  });

  it('advances equal timestamps by the last record ID', async () => {
    const { db, setSetting } = cursorStore({ lastId: 9, lastUpdateTime: 20_000 });
    await runIncrementalPull(
      db,
      'cursor',
      async () => [{ id: 10, updateTime: 20_000 }],
      () => true,
    );
    expect(setSetting).toHaveBeenCalledWith('cursor', {
      lastId: 10,
      lastUpdateTime: 20_000,
    });
  });
});

describe('im message ID pagination', () => {
  it('applies a page without valid IDs once and stops without a NaN cursor', async () => {
    const fetchPage = vi.fn().mockResolvedValue([{}, { id: Number.NaN }]);
    const applyPage = vi.fn().mockResolvedValue(true);
    await runMinIdPull({ initialMinId: 3, pageSize: 2, fetchPage, applyPage });
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(applyPage).toHaveBeenCalledWith([{}, { id: Number.NaN }], undefined);
  });

  it('uses valid IDs when some records omit them and stops on cancellation', async () => {
    let active = true;
    const fetchPage = vi.fn().mockResolvedValue([{}, { id: 8 }]);
    const applyPage = vi.fn(async () => {
      active = false;
      return true;
    });
    await runMinIdPull({
      initialMinId: 3,
      pageSize: 2,
      fetchPage,
      applyPage,
      isActive: () => active,
    });
    expect(applyPage).toHaveBeenCalledWith([{}, { id: 8 }], 8);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });
});
