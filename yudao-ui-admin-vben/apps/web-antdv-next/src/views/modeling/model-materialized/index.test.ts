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
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
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
});

describe('发布模型列表查询与身份边界', () => {
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
