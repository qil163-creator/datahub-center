/* eslint-disable vue/one-component-per-file -- 测试桩用于隔离弹窗和分类树 */
import type { App } from 'vue';

import type { PageResult } from '@vben/request';

import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/model-materialized';

import MaterializedPage from './index.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'one', refreshToken: '' },
  push: vi.fn(),
  resetTree: vi.fn(),
  publish: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 'test-user' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('#/adapter/business-vxe-table', () => ({
  NativeVxeButton: defineComponent({
    setup(_, { attrs }) {
      return () => h('button', attrs);
    },
  }),
}));
vi.mock('#/api/modeling/model-materialized', () => ({
  getModelMaterializedPage: vi.fn(),
  getModelMaterializedCatTree: vi.fn(),
}));
vi.mock('./modules/category-tree.vue', () => ({
  default: defineComponent({
    emits: ['select'],
    setup(_, { emit, expose }) {
      expose({ reset: harness.resetTree });
      return () =>
        h(
          'button',
          {
            onClick: () =>
              emit('select', {
                key: 'layer',
                name: '分层',
                type: 'LAYER',
                layerId: '9007199254740993127',
              }),
          },
          '测试分类',
        );
    },
  }),
}));
vi.mock('./modules/publish-modal.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ openBatch: harness.publish });
      return () => null;
    },
  }),
}));
vi.mock('./modules/recovery-modal.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));

let app: App | undefined;
let host: HTMLDivElement;
const row: ModelMaterializedApi.Materialized = {
  modelId: '9007199254740993123',
  modelAlias: '防汛模型',
  modelName: 'dws_warning',
  tableType: 'SUMMARY',
  publishStatus: 'UNPUBLISHED',
};
async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(MaterializedPage);
  app.mount(host);
  await flush();
}
function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (element) => element.textContent?.replaceAll(/\s/g, '') === text,
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'one', refreshToken: '' });
  vi.mocked(api.getModelMaterializedPage).mockResolvedValue({
    list: [row],
    total: 1,
  });
  vi.mocked(api.getModelMaterializedCatTree).mockResolvedValue([]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.unstubAllGlobals();
});

describe('发布模型列表查询与身份边界', () => {
  it('列设置确定后生效并在重新进入时恢复', async () => {
    const storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    });
    await mount();
    host.querySelector<HTMLElement>(`[aria-label="选择模型 ${row.modelAlias}"]`)!.click();
    await flush();
    expect(button('批量发布').disabled).toBe(false);
    button('批量发布').click();
    expect(harness.publish).toHaveBeenCalledWith([row]);
    host.querySelector<HTMLButtonElement>('[aria-label="个性化设置"]')!.click();
    await flush();
    document.querySelector<HTMLElement>('[aria-label="显示选择"]')!.click();
    document.querySelector<HTMLElement>('[aria-label="显示发布人"]')!.click();
    document.querySelector<HTMLElement>('[aria-label="左侧冻结编号"]')!.click();
    document.querySelector<HTMLElement>('[aria-label="左侧冻结模型信息"]')!.click();
    document.querySelector<HTMLElement>('[aria-label="右侧冻结发布信息"]')!.click();
    await nextTick();
    const extraFreeze = document.querySelector<HTMLButtonElement>('[aria-label="左侧冻结归属信息"]')!;
    expect(extraFreeze.disabled).toBe(true);
    document.querySelector<HTMLButtonElement>('[aria-label="右侧冻结发布信息"]')!.click();
    await nextTick();
    expect(extraFreeze.disabled).toBe(false);
    document.querySelector<HTMLButtonElement>('[aria-label="右侧冻结发布信息"]')!.click();
    const confirmButton = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
      (element) => element.textContent?.replaceAll(/\s/g, '') === '确定',
    )!;
    confirmButton.click();
    await flush();
    expect(button('批量发布').disabled).toBe(true);
    expect(host.querySelector('[data-column-key="publisher"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[data-column-key="id"]')!.style.left).toBe('0px');
    expect(host.querySelector<HTMLElement>('[data-column-key="model"]')!.style.left).toBe('60px');
    expect(host.querySelector<HTMLElement>('[data-column-key="release"]')!.style.right).toBe('175px');
    expect(host.querySelector<HTMLElement>('[data-column-key="action"]')!.style.right).toBe('0px');
    Object.defineProperty(host.querySelector('[data-column-key="id"]')!, 'getBoundingClientRect', {
      value: () => ({ width: 85 }),
    });
    Object.defineProperty(host.querySelector('[data-column-key="action"]')!, 'getBoundingClientRect', {
      value: () => ({ width: 210 }),
    });
    host.querySelector('.qd-table__wrapper')!.dispatchEvent(new Event('scroll'));
    await nextTick();
    expect(host.querySelector<HTMLElement>('[data-column-key="model"]')!.style.left).toBe('85px');
    expect(host.querySelector<HTMLElement>('[data-column-key="release"]')!.style.right).toBe('210px');
    expect(storage.size).toBe(1);
    const previousCalls = vi.mocked(api.getModelMaterializedPage).mock.calls.length;
    host.querySelector<HTMLButtonElement>('[aria-label="刷新"]')!.click();
    await flush();
    expect(api.getModelMaterializedPage).toHaveBeenCalledTimes(previousCalls + 1);
    expect(host.querySelector('[data-column-key="publisher"]')).toBeNull();
    app?.unmount();
    host.remove();
    await mount();
    expect(host.querySelector('[data-column-key="publisher"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[data-column-key="id"]')!.style.left).toBe('0px');
    expect(host.querySelector<HTMLElement>('[data-column-key="release"]')!.style.right).toBe('175px');
  });
  it('横向滚动时固定操作列，并在未到最右侧时显示阴影', async () => {
    await mount();
    const wrapper = host.querySelector<HTMLElement>('.qd-table__wrapper')!;
    Object.defineProperties(wrapper, {
      clientWidth: { configurable: true, value: 500 },
      scrollWidth: { configurable: true, value: 1000 },
    });
    wrapper.scrollLeft = 0;
    wrapper.dispatchEvent(new Event('scroll'));
    await nextTick();
    const fixedCells = host.querySelectorAll('.is-fixed-right');
    expect(fixedCells).toHaveLength(2);
    expect([...fixedCells].every((cell) => cell.classList.contains('has-shadow'))).toBe(true);
    wrapper.scrollLeft = 500;
    wrapper.dispatchEvent(new Event('scroll'));
    await nextTick();
    expect([...fixedCells].every((cell) => !cell.classList.contains('has-shadow'))).toBe(true);
  });
  it('真实模型编号导航到独立详情页并使用服务端编号排序', async () => {
    await mount();
    button('防汛模型(dws_warning)').click();
    expect(harness.push).toHaveBeenCalledWith({
      name: 'ModelingMaterializedDetail',
      params: { modelId: row.modelId },
    });
    button('编号').click();
    await flush();
    expect(api.getModelMaterializedPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ sortField: 'id', sortOrder: 'asc' }),
    );
    expect(host.querySelector('tbody')!.textContent).toContain(row.modelId);
    expect(host.querySelector<HTMLElement>('.materialized-action-icon')!.style.maskImage).toMatch(/^url\("/);
  });
  it('重置同时清除分类选择与查询参数', async () => {
    await mount();
    button('测试分类').click();
    await flush();
    expect(api.getModelMaterializedPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ layerId: '9007199254740993127' }),
    );
    const previousCalls = vi.mocked(api.getModelMaterializedPage).mock.calls.length;
    button('搜索').click();
    await vi.waitFor(() => {
      expect(api.getModelMaterializedPage).toHaveBeenCalledTimes(previousCalls + 1);
    });
    button('重置').click();
    await flush();
    expect(harness.resetTree).toHaveBeenCalledOnce();
    expect(api.getModelMaterializedPage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        layerId: undefined,
        name: undefined,
        pageNo: 1,
      }),
    );
  });
  it('查询乱序及会话切换后旧结果不能覆盖新列表', async () => {
    let finish!: (value: PageResult<ModelMaterializedApi.Materialized>) => void;
    vi.mocked(api.getModelMaterializedPage).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mount();
    harness.access.refreshToken = 'two';
    await flush();
    finish({ list: [{ ...row, modelAlias: '过期模型' }], total: 1 });
    await flush();
    expect(host.textContent).toContain('防汛模型');
    expect(host.textContent).not.toContain('过期模型');
    expect(host.querySelector('.qd-table')!.getAttribute('aria-busy')).toBe(
      'false',
    );
  });
});
