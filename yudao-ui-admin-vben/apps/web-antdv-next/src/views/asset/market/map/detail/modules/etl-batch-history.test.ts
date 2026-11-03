/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟表格和操作组件 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAssetEtlBatchHistory } from '#/api/asset/dataasset/etl-batch';

import EtlBatchHistory from './etl-batch-history.vue';

const identity = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => identity.access,
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('#/api/asset/dataasset/etl-batch', () => ({
  getAssetEtlBatchHistory: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Alert: defineComponent({
    props: { message: { type: String, default: '' } },
    setup: (props) => () => h('p', props.message),
  }),
  Table: defineComponent({
    props: { dataSource: { type: Array, default: () => [] } },
    setup: (props) => () => h('div', JSON.stringify(props.dataSource)),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(EtlBatchHistory, {
    assetId: '9007199254740993',
    currentRevision: 2,
  });
  app.mount(host);
}
beforeEach(() => {
  vi.resetAllMocks();
  identity.access = reactive({ accessToken: '1', refreshToken: '' });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
describe('资产 ETL 关联修订', () => {
  it('按修订继续读取并明确历史不等于准入，不触发数据读取', async () => {
    vi.mocked(getAssetEtlBatchHistory)
      .mockResolvedValueOnce({
        list: [
          {
            id: '1',
            assetId: '9007199254740993',
            revision: 2,
            batchId: '9007199254740995',
          } as any,
        ],
        hasMore: true,
        nextBeforeRevision: 2,
      })
      .mockResolvedValue({ list: [], hasMore: false });
    mount();
    await flush();
    expect(getAssetEtlBatchHistory).not.toHaveBeenCalled();
    button('查看关联修订历史').click();
    await flush();
    expect(host.textContent).toContain('9007199254740995');
    expect(host.textContent).toContain('历史修订不代表当前准入');
    button('加载更早修订').click();
    await flush();
    expect(getAssetEtlBatchHistory).toHaveBeenLastCalledWith({
      assetId: '9007199254740993',
      beforeRevision: 2,
      limit: 20,
    });
  });
  it('关闭后不显示旧请求，失败不是空历史', async () => {
    let resolve!: (
      value: Awaited<ReturnType<typeof getAssetEtlBatchHistory>>,
    ) => void;
    vi.mocked(getAssetEtlBatchHistory)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolve = done;
        }),
      )
      .mockRejectedValue(new Error('denied'));
    mount();
    button('查看关联修订历史').click();
    await flush();
    button('收起关联历史').click();
    resolve({
      list: [{ assetId: '9007199254740993', reason: 'old-history' } as any],
      hasMore: false,
    });
    await flush();
    expect(host.textContent).not.toContain('old-history');
    button('查看关联修订历史').click();
    await flush();
    expect(host.textContent).toContain('关联历史读取失败');
  });
});
