import type { App } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import RelatedList from './related-list.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['modeling:model:query'],
  },
  loadModel: vi.fn(),
  loadElement: vi.fn(),
  loadCodeSet: vi.fn(),
  grid: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [() => null, { setData: vi.fn(), open: vi.fn() }],
}));
vi.mock('antdv-next', () => ({ Alert: () => null, Button: () => null }));
vi.mock('#/components/dict-tag', () => ({ DictTag: () => null }));
vi.mock('#/adapter/vxe-table', () => ({
  TableAction: () => null,
  useVbenVxeGrid: () => [() => null, { setGridOptions: harness.grid }],
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModelPage: (...args: unknown[]) => harness.loadModel(...args),
}));
vi.mock('#/api/governance/standardelement', () => ({
  getStandardElementPage: (...args: unknown[]) => harness.loadElement(...args),
}));
vi.mock('#/api/governance/standardcodeset', () => ({
  getStandardCodeSetPage: (...args: unknown[]) => harness.loadCodeSet(...args),
}));
vi.mock('#/views/governance/standardelement/modules/form.vue', () => ({
  default: () => null,
}));
vi.mock('#/views/governance/standardcodeset/modules/form.vue', () => ({
  default: () => null,
}));

let app: App | undefined;
const props = reactive<{
  documentId: string;
  kind: 'element' | 'model';
  standardType: string;
}>({
  documentId: '9007199254740993',
  kind: 'model',
  standardType: 'NATIONAL',
});
function deferred() {
  let resolve!: (value: {
    list: { id: string; name: string }[];
    total: number;
  }) => void;
  const promise = new Promise<{
    list: { id: string; name: string }[];
    total: number;
  }>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 6; index++) await nextTick();
}
async function mount() {
  app = createApp(() => h(RelatedList, props));
  app.mount(document.createElement('div'));
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['modeling:model:query'],
  });
  Object.assign(props, {
    documentId: '9007199254740993',
    kind: 'model',
    standardType: 'NATIONAL',
  });
  harness.loadModel.mockResolvedValue({ list: [], total: 0 });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('标准关联对象的请求边界', () => {
  it('关联请求携带无损documentId，缺少对象查询权限时不请求', async () => {
    await mount();
    expect(harness.loadModel).toHaveBeenCalledExactlyOnceWith({
      documentId: '9007199254740993',
      pageNo: 1,
      pageSize: 10,
    });
    props.kind = 'element';
    await flush();
    expect(harness.loadElement).not.toHaveBeenCalled();
  });
  it('切换标准后较早的结果不能覆盖当前标准', async () => {
    const old = deferred();
    harness.loadModel.mockReturnValueOnce(old.promise);
    await mount();
    props.documentId = '9007199254740994';
    harness.loadModel.mockResolvedValue({
      list: [{ id: '8', name: '当前标准模型' }],
      total: 1,
    });
    await flush();
    old.resolve({ list: [{ id: '7', name: '旧标准模型' }], total: 1 });
    await flush();
    const last = harness.grid.mock.calls.at(-1)![0];
    expect(last.data[0].name).toBe('当前标准模型');
  });
  it('退出会话后清除关联数据并丢弃迟到响应', async () => {
    const old = deferred();
    harness.loadModel.mockReturnValueOnce(old.promise);
    await mount();
    harness.access.accessCodes = [];
    harness.access.accessToken = '';
    await flush();
    old.resolve({ list: [{ id: '7', name: '不可回写的数据' }], total: 1 });
    await flush();
    expect(harness.grid.mock.calls.at(-1)![0].data).toEqual([]);
  });
});
