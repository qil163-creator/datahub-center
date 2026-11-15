/* eslint-disable vue/one-component-per-file -- 测试桩用于隔离弹窗与组件库 */
import type { App, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/tablemodel';

import VersionPanel from './version-panel.vue';

const compareOpen = vi.fn();

vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 4 } }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'token', refreshToken: 'refresh' }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-11-14 10:00' }));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModelVersions: vi.fn(),
  getTableModelVersion: vi.fn(),
}));
vi.mock('./version-compare-modal.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ open: compareOpen });
      return () => null;
    },
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  });
  return {
    Button: (_: unknown, { attrs, slots }: SetupContext) =>
      h('button', attrs, slots.default?.()),
    ConfigProvider: plain,
    Modal: defineComponent({
      props: { open: Boolean },
      setup: (props, { slots }) => () =>
        props.open ? h('section', { 'data-test-modal': '' }, slots.default?.()) : null,
    }),
    Spin: plain,
  };
});

const modelId = '9007199254740993';
let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function button(text: string, index = 0) {
  const found = [...host.querySelectorAll('button')].filter(
    (element) => element.textContent?.trim() === text,
  );
  expect(found[index]).toBeDefined();
  return found[index]!;
}

async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(VersionPanel, { modelId, modelName: '订单事实表' });
  app.mount(host);
  await flush();
}

beforeEach(() => {
  compareOpen.mockReset();
  vi.mocked(api.getTableModelVersions).mockReset().mockResolvedValue([
    {
      id: '9007199254740995',
      modelId,
      versionNo: 2,
      versionLabel: 'V2',
      fieldCount: 3,
      changeSummary: { added: 1, removed: 0, modified: 2, headerChanged: 0 },
      publishedByName: '管理员',
      publishedTime: 1_789_000_000_000,
    },
    {
      id: '9007199254740994',
      modelId,
      versionNo: 1,
      versionLabel: 'V1',
      fieldCount: 2,
      changeSummary: { initial: true },
      publishedTime: 1_788_000_000_000,
    },
  ]);
  vi.mocked(api.getTableModelVersion).mockReset().mockResolvedValue({
    id: '9007199254740995',
    modelId,
    versionNo: 2,
    versionLabel: 'V2',
    modelSnapshot: { name: '订单事实表', tableNameNotation: 'dwd_order' },
    fields: [{ fieldName: 'order_id', cnName: '订单号', dataType: 'BIGINT', pkFlag: true }],
  });
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('逻辑表版本页签', () => {
  it('按模型加载版本并展示变更说明与最新标记', async () => {
    await mount();
    expect(api.getTableModelVersions).toHaveBeenCalledExactlyOnceWith(modelId);
    expect(host.textContent).toContain('V2');
    expect(host.textContent).toContain('最新');
    expect(host.textContent).toContain('字段新增 1，修改 2');
    expect(host.textContent).toContain('初始版本');
    expect(host.textContent).toContain('管理员');
    expect([...host.querySelectorAll('button')].filter((b) => b.textContent?.trim() === '与上一版比对'))
      .toHaveLength(1);
  });

  it('与上一版或当前设计比对时传递无损版本编号', async () => {
    await mount();
    button('与上一版比对').click();
    expect(compareOpen).toHaveBeenLastCalledWith(modelId, '订单事实表', {
      fromVersionId: '9007199254740994',
      toVersionId: '9007199254740995',
    });
    button('与当前设计比对', 1).click();
    expect(compareOpen).toHaveBeenLastCalledWith(modelId, '订单事实表', {
      fromVersionId: '9007199254740994',
    });
  });

  it('查看版本时展示快照字段', async () => {
    await mount();
    button('查看').click();
    await flush();
    expect(api.getTableModelVersion).toHaveBeenCalledExactlyOnceWith('9007199254740995');
    const modal = host.querySelector('[data-test-modal]');
    expect(modal?.textContent).toContain('dwd_order');
    expect(modal?.textContent).toContain('order_id');
  });

  it('没有版本时提示发布后生成', async () => {
    vi.mocked(api.getTableModelVersions).mockResolvedValue([]);
    await mount();
    expect(host.textContent).toContain('还没有发布过版本');
  });
});
