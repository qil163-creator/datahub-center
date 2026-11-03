 
import { afterEach, beforeEach, expect, it } from 'vitest';

import { deferred, flush, mock, mountState, resetMocks } from '../test-support';
import Detail from './index.vue';
interface State {
  tag: { id?: string };
  rows: unknown[];
  errorText: string;
  loadTag: () => Promise<void>;
  loadAssets: () => Promise<void>;
  removeAsset: (row: {
    assetId: string;
    relationId: string;
    relationRevision: number;
    sourceRecordId?: string;
  }) => void;
  tableChange: (p: unknown, f: unknown, s: unknown) => void;
}
let handle: ReturnType<typeof mountState<State>>;
beforeEach(resetMocks);
afterEach(() => handle?.dispose());
async function mount() {
  handle = mountState<State>(Detail);
  await flush();
  return handle.state;
}
it('uses the tag identity and server sorting for linked assets', async () => {
  const s = await mount();
  expect(mock.get).toHaveBeenCalledWith('9007199254740993');
  expect(mock.linked).toHaveBeenCalledWith(
    expect.objectContaining({
      id: '9007199254740993',
      sortField: 'createTime',
      sortOrder: 'desc',
    }),
  );
  s.tableChange({}, {}, { columnKey: 'assetId', order: 'ascend' });
  await flush();
  expect(mock.linked).toHaveBeenLastCalledWith(
    expect.objectContaining({ sortField: 'assetId', sortOrder: 'asc' }),
  );
});
it('rejects a mismatched detail without requesting its associations', async () => {
  mock.get.mockResolvedValueOnce({ id: '2', name: 'wrong' });
  const s = await mount();
  expect(s.errorText).toBeTruthy();
  expect(mock.linked).not.toHaveBeenCalled();
});
it('drops old detail reads after navigation to another tag', async () => {
  const s = await mount();
  const old = deferred<{ id: string }>();
  mock.get.mockReturnValueOnce(old.promise);
  const pending = s.loadTag();
  mock.get.mockResolvedValueOnce({ id: '2', name: 'new' });
  mock.route.params.id = '2';
  await flush();
  old.resolve({ id: '9007199254740993' });
  await pending;
  expect(s.tag.id).toBe('2');
});
it('unbinds only the selected relation revision and reloads authoritative detail and counts', async () => {
  const s = await mount();
  s.removeAsset({
    relationId: '9007199254740995',
    relationRevision: 9,
    assetId: '9007199254740996',
    sourceRecordId: '6',
  });
  await mock.confirm.mock.calls[0]![0].onOk();
  expect(mock.unbind).toHaveBeenCalledWith('9007199254740995', 9);
  expect(mock.get).toHaveBeenCalledTimes(2);
});
it('invalidates relation confirmations after route or identity changes', async () => {
  const s = await mount();
  s.removeAsset({ relationId: '11', relationRevision: 2, assetId: '22' });
  const options = mock.confirm.mock.calls[0]![0];
  mock.route.params.id = '2';
  await flush();
  expect(mock.destroy).toHaveBeenCalled();
  await options.onOk();
  expect(mock.unbind).not.toHaveBeenCalled();
});
it('blocks reads and unbinding without the required access codes', async () => {
  mock.access.accessCodes = [];
  const s = await mount();
  expect(mock.get).not.toHaveBeenCalled();
  s.removeAsset({ relationId: '11', relationRevision: 2, assetId: '22' });
  expect(mock.confirm).not.toHaveBeenCalled();
});
it('reloads after each concurrent relation deletion finishes', async () => {
  const s = await mount();
  const first = deferred<unknown>();
  const second = deferred<unknown>();
  mock.unbind.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
  s.removeAsset({ relationId: '11', relationRevision: 2, assetId: '21' });
  s.removeAsset({ relationId: '12', relationRevision: 3, assetId: '22' });
  const pendingFirst = mock.confirm.mock.calls[0]![0].onOk();
  const pendingSecond = mock.confirm.mock.calls[1]![0].onOk();
  const reads = mock.get.mock.calls.length;
  second.resolve(true);
  await pendingSecond;
  first.resolve(true);
  await pendingFirst;
  expect(mock.get).toHaveBeenCalledTimes(reads + 2);
});
