 
import type { AssetTagApi } from '#/api/asset/tag';

import { afterEach, beforeEach, expect, it } from 'vitest';

import { deferred, flush, mock, mountState, resetMocks } from '../test-support';
import Editor from './form.vue';
interface State {
  form: AssetTagApi.Tag;
  errorText: string;
  saving: boolean;
  save: () => Promise<void>;
}
let handle: ReturnType<typeof mountState<State>>;
beforeEach(resetMocks);
afterEach(() => handle?.dispose());
async function open(data: Record<string, unknown> = {}) {
  mock.modalData = data;
  handle = mountState<State>(Editor, { onSuccess: mock.emit });
  await mock.modalOptions.onOpenChange(true);
  return handle.state;
}
it('creates with trimmed name, required real category and the enabled default while preserving long IDs', async () => {
  const s = await open({ categoryId: '9007199254740994' });
  s.form.name = ' 新标签 ';
  await s.save();
  expect(mock.create).toHaveBeenCalledWith(
    expect.objectContaining({
      name: '新标签',
      categoryId: '9007199254740994',
      status: 0,
    }),
  );
  expect(mock.emit).toHaveBeenCalledWith('9007199254740995');
});
it('rejects empty or oversized names and missing category before a write', async () => {
  const s = await open();
  for (const name of ['   ', 'a'.repeat(51), '正常名称']) {
    s.form.name = name;
    await s.save();
  }
  expect(mock.create).not.toHaveBeenCalled();
});
it('loads the fresh disabled definition and sends revision while retaining status outside definition updates', async () => {
  const s = await open({
    id: '9007199254740993',
    name: '过期标签',
    revision: 1,
  });
  s.form.name = '新定义';
  s.form.status = 0;
  await s.save();
  expect(mock.update).toHaveBeenCalledWith(
    expect.objectContaining({
      id: '9007199254740993',
      revision: 7,
      name: '新定义',
      status: undefined,
    }),
  );
});
it.each([
  { id: '2', status: 1, revision: 7 },
  { id: '9007199254740993', status: 0, revision: 8 },
])(
  'fails closed for a mismatched or newly enabled detail %o',
  async (value) => {
    mock.get.mockResolvedValue(value);
    const s = await open({ id: '9007199254740993' });
    s.form.name = '非法修改';
    await s.save();
    expect(mock.update).not.toHaveBeenCalled();
    expect(s.errorText).toBeTruthy();
  },
);
it('does not write when taxonomy fails and supports correcting and retrying a failed save', async () => {
  mock.categories.mockRejectedValueOnce(new Error('unavailable'));
  const s = await open({ categoryId: '1' });
  s.form.name = '标签';
  await s.save();
  expect(mock.create).not.toHaveBeenCalled();
  await mock.modalOptions.onOpenChange(false);
  await mock.modalOptions.onOpenChange(true);
  s.form.name = '标签';
  s.form.categoryId = '9007199254740994';
  mock.create.mockRejectedValueOnce(new Error('conflict'));
  await s.save();
  expect(s.form.name).toBe('标签');
  await s.save();
  expect(mock.emit).toHaveBeenCalledOnce();
});
it('ignores closed-dialog reads and stale identity write results', async () => {
  const work = deferred<unknown[]>();
  mock.categories.mockReturnValueOnce(work.promise);
  mock.modalData = {};
  handle = mountState<State>(Editor, { onSuccess: mock.emit });
  const pending = mock.modalOptions.onOpenChange(true);
  await mock.modalOptions.onOpenChange(false);
  work.resolve([]);
  await pending;
  expect(handle.state.form.categoryId).toBeUndefined();
  await mock.modalOptions.onOpenChange(true);
  handle.state.form.name = '标签';
  handle.state.form.categoryId = '9007199254740994';
  const write = deferred<string>();
  mock.create.mockReturnValueOnce(write.promise);
  const saving = handle.state.save();
  mock.access.accessToken = 'replacement';
  await flush();
  write.resolve('9007199254740995');
  await saving;
  expect(mock.emit).not.toHaveBeenCalled();
});
it('blocks duplicate submission and create permission loss', async () => {
  const s = await open({ categoryId: '9007199254740994' });
  s.form.name = '标签';
  const work = deferred<string>();
  mock.create.mockReturnValueOnce(work.promise);
  const saving = s.save();
  await s.save();
  expect(mock.create).toHaveBeenCalledOnce();
  work.resolve('9007199254740995');
  await saving;
  mock.access.accessCodes = [];
  await flush();
  await s.save();
  expect(mock.create).toHaveBeenCalledOnce();
});
