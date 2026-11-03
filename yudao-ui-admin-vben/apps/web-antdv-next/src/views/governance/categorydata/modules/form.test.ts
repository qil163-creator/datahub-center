/* eslint-disable vue/one-component-per-file -- Minimal controls isolate the existing modal and form contracts. */
import { createApp, defineComponent, h, ref } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import Form from './form.vue';

const mock = vi.hoisted(() => ({ get: vi.fn(), save: vi.fn(), values: vi.fn(), setValues: vi.fn(), success: vi.fn(), emit: vi.fn(), data: { id: '9007199254740993' }, modal: {} as { onConfirm: () => Promise<void>; onOpenChange: (open: boolean) => Promise<void>; } }));
const subject = ref('session');
vi.mock('#/views/governance/category/use-session', () => ({ useCategorySession: () => ({ identity: subject, can: () => true }) }));
vi.mock('#/api/governance/categorydata', () => ({ getCategoryData: mock.get, createCategoryData: mock.save, updateCategoryData: mock.save }));
vi.mock('../data', () => ({ useFormSchema: () => [] }));
vi.mock('#/locales', () => ({ $t: (value: string) => value }));
vi.mock('antdv-next', () => ({ message: { success: mock.success } }));
vi.mock('#/adapter/form', () => ({ useVbenForm: () => [defineComponent({ setup: () => () => h('div') }), { validate: async () => ({ valid: true }), getValues: mock.values, setValues: mock.setValues, resetForm: vi.fn() }] }));
vi.mock('@vben/common-ui', () => ({ useVbenModal: (options: typeof mock.modal) => {
  mock.modal = options;
  return [defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }), { getData: () => mock.data, close: () => options.onOpenChange(false), lock: vi.fn(), unlock: vi.fn() }];
} }));
let dispose: (() => void) | undefined;
function mount() {
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp(Form, { onSuccess: mock.emit }); app.mount(host);
  dispose = () => { app.unmount(); host.remove(); };
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.resetAllMocks(); subject.value = 'session';
  mock.get.mockResolvedValue({ id: '9007199254740993', revision: '9007199254740995', parentId: '9007199254740994', categoryName: '目录', status: 0, remark: '说明' });
  mock.values.mockResolvedValue({ id: 'spoof', revision: '1', parentId: '9007199254740994', categoryName: '修改名', status: 0, remark: '说明' });
  mock.save.mockResolvedValue(true); mount();
});
afterEach(() => dispose?.());
it('submits the fresh ID and revision even when form values omit or spoof the hidden revision', async () => {
  await mock.modal.onOpenChange(true); await mock.modal.onConfirm();
  expect(mock.save).toHaveBeenCalledWith(expect.objectContaining({ id: '9007199254740993', revision: '9007199254740995', categoryName: '修改名' }));
  expect(mock.emit).toHaveBeenCalledOnce();
});
it('ignores a read completed after the editor closes', async () => {
  const pending = deferred<unknown>(); mock.get.mockReturnValueOnce(pending.promise);
  const opening = mock.modal.onOpenChange(true); await mock.modal.onOpenChange(false); pending.resolve({ id: '9007199254740993', revision: '9' }); await opening;
  expect(mock.setValues).not.toHaveBeenCalled(); await mock.modal.onConfirm(); expect(mock.save).not.toHaveBeenCalled();
});
it('refuses an identity-mismatched detail instead of editing the supplied stale row', async () => {
  mock.get.mockResolvedValueOnce({ id: 'other', revision: '9' }); await mock.modal.onOpenChange(true); await mock.modal.onConfirm(); expect(mock.save).not.toHaveBeenCalled();
});
it('blocks a stale success result after the session changes', async () => {
  await mock.modal.onOpenChange(true); const pending = deferred<unknown>(); mock.save.mockReturnValueOnce(pending.promise);
  const saving = mock.modal.onConfirm();
  for (let index = 0; index < 12 && mock.save.mock.calls.length === 0; index++) await Promise.resolve();
  expect(mock.save).toHaveBeenCalledOnce(); subject.value = 'replacement'; pending.resolve(true); await saving;
  expect(mock.emit).not.toHaveBeenCalled(); expect(mock.success).not.toHaveBeenCalled();
});
