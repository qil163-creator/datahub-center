import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getRuleSetItems,
  getRuleSetSimpleList,
} from '#/api/governance/quality/ruleset';

import QualityCheck from './quality-check.vue';

const harness = vi.hoisted(() => ({
  access: {} as { allowed: boolean; accessToken: string; refreshToken?: string },
  user: {} as { userInfo: { userId: string } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.access.allowed }),
}));
vi.mock('#/api/governance/quality/ruleset', () => ({
  getRuleSetItems: vi.fn(),
  getRuleSetSimpleList: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('p', props.message),
    }),
    AutoComplete: slot('input'),
    Button: slot('button'),
    Input: slot('input'),
    Select: slot('div'),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
let config: Record<string, any>;
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function mountEditor() {
  host = document.createElement('div');
  document.body.append(host);
  config = reactive({ ruleSetId: '9007199254740993', fieldBindings: [] });
  app = createApp({ render: () => h(QualityCheck, { config }) });
  app.mount(host);
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    allowed: true,
    accessToken: '1',
    refreshToken: undefined as string | undefined,
  });
  harness.user = reactive({ userInfo: { userId: '7' } });
  vi.mocked(getRuleSetSimpleList).mockResolvedValue([]);
  vi.mocked(getRuleSetItems).mockResolvedValue([]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('行级质量规则集编辑', () => {
  it('更换规则集后忽略旧明细，规则与字段绑定归属当前选择', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof getRuleSetItems>>) => void;
    vi.mocked(getRuleSetItems).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    vi.mocked(getRuleSetItems).mockResolvedValueOnce([
      { ruleId: 2, ruleName: '新规则' },
    ]);
    mountEditor();
    config.ruleSetId = '9007199254740995';
    await flush();
    resolve([{ ruleId: 1, ruleName: '旧规则' }]);
    await flush();
    expect(config.fieldBindings).toEqual([{ ruleId: '2', field: '' }]);
    expect(host.textContent).toContain('新规则');
    expect(host.textContent).not.toContain('旧规则');
    expect(getRuleSetItems).toHaveBeenCalledWith('9007199254740993');
    expect(getRuleSetItems).toHaveBeenCalledWith('9007199254740995');
  });

  it('规则查询失败明确显示错误，不静默替换已有字段绑定', async () => {
    vi.mocked(getRuleSetItems).mockRejectedValueOnce(new Error('network'));
    mountEditor();
    config.fieldBindings = [{ ruleId: '9007199254740997', field: 'code' }];
    await flush();
    expect(host.textContent).toContain('规则明细加载失败');
    expect(config.fieldBindings).toEqual([
      { ruleId: '9007199254740997', field: 'code' },
    ]);
  });

  it('租户切换丢弃旧规则明细，保留草稿绑定并要求重新打开', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof getRuleSetItems>>) => void;
    vi.mocked(getRuleSetItems).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    mountEditor();
    config.fieldBindings = [{ ruleId: '9007199254740997', field: 'code' }];
    harness.access.refreshToken = '2';
    await flush();
    resolve([{ ruleId: 2, ruleName: '旧会话规则' }]);
    await flush();
    expect(host.textContent).not.toContain('旧会话规则');
    expect(host.textContent).toContain('请重新打开节点配置');
    expect(config.fieldBindings).toEqual([
      { ruleId: '9007199254740997', field: 'code' },
    ]);
    expect(getRuleSetItems).toHaveBeenCalledTimes(1);
  });

  it('撤权清除已加载明细并阻断新请求，恢复权限后重读', async () => {
    vi.mocked(getRuleSetItems).mockResolvedValueOnce([
      { ruleId: 2, ruleName: '私有规则' },
    ]);
    mountEditor();
    await flush();
    expect(host.textContent).toContain('私有规则');
    harness.access.allowed = false;
    await flush();
    expect(host.textContent).not.toContain('私有规则');
    expect(host.textContent).toContain('没有治理规则集查询权限');
    config.ruleSetId = '9007199254740995';
    await flush();
    expect(getRuleSetItems).toHaveBeenCalledTimes(1);
    vi.mocked(getRuleSetItems).mockResolvedValueOnce([
      { ruleId: 3, ruleName: '授权后规则' },
    ]);
    harness.access.allowed = true;
    await flush();
    expect(getRuleSetItems).toHaveBeenLastCalledWith('9007199254740995');
    expect(host.textContent).toContain('授权后规则');
  });
});
