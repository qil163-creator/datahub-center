import type { App } from 'vue';

import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getCleanRulePage } from '#/api/governance/cleanrule';

import DataClean from './data-clean.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; allowed: boolean; refreshToken?: string },
  user: {} as { userInfo: { userId: string } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.access.allowed }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('#/api/governance/cleanrule', () => ({ getCleanRulePage: vi.fn() }));
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
      props: ['message'],
      setup:
        (props, { slots }) =>
        () =>
          h('aside', [props.message, slots.action?.()]),
    }),
    AutoComplete: slot('input'),
    Button: slot('button'),
    Checkbox: slot('div'),
    Input: defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    RadioGroup: slot('div'),
    Select: defineComponent({
      props: ['value', 'options', 'loading', 'disabled'],
      emits: ['change', 'update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              disabled: props.disabled,
              'data-loading': String(props.loading ?? false),
              value: props.value,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value;
                emit('update:value', value);
                emit('change', value);
              },
            },
            [
              h('option', { value: '' }, ''),
              ...(props.options ?? []).map(
                (option: { label: string; value: string }) =>
                  h('option', { value: option.value }, option.label),
              ),
            ],
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
let config: Record<string, any>;
async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
function rule(id: string, name: string): GovernanceCleanRuleApi.CleanRule {
  return {
    id,
    name,
    status: 0,
    ruleLevel: 1,
    strategyKey: 'REMOVE_WHITESPACE',
    engineSupported: true,
    rowTransformSupported: true,
    config: { mode: 'TRIM' },
  };
}
function mount() {
  host = document.createElement('div');
  document.body.append(host);
  config = reactive({ executionMode: 'GOVERNANCE', governanceRules: [] });
  app = createApp({
    render: () =>
      h(DataClean, {
        config,
        inputFields: [{ name: 'code', dataType: 'STRING' }],
      }),
  });
  app.mount(host);
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
function deferredPage() {
  let resolve!: (result: Awaited<ReturnType<typeof getCleanRulePage>>) => void;
  const promise = new Promise<Awaited<ReturnType<typeof getCleanRulePage>>>(
    (done) => {
      resolve = done;
    },
  );
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    allowed: true,
    accessToken: '1',
    refreshToken: undefined as string | undefined,
  });
  harness.user = reactive({ userInfo: { userId: '7' } });
  vi.mocked(getCleanRulePage).mockResolvedValue({ list: [], total: 0 });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('治理清洗规则读取', () => {
  it('刷新乱序响应不会覆盖新选项或提前结束当前加载', async () => {
    const older = deferredPage();
    const newer = deferredPage();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(older.promise);
    mount();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(newer.promise);
    button('刷新').click();
    await flush();
    older.resolve({ list: [rule('9007199254740993', '过期规则')], total: 1 });
    await flush();
    expect(host.textContent).not.toContain('过期规则');
    expect(host.querySelector('select')?.dataset.loading).toBe('true');
    newer.resolve({ list: [rule('9007199254740995', '当前规则')], total: 1 });
    await flush();
    expect(host.textContent).toContain('当前规则');
    expect(host.querySelector('select')?.dataset.loading).toBe('false');
  });

  it('退出治理模式后迟到响应不能恢复已失效选项', async () => {
    const pending = deferredPage();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(pending.promise);
    mount();
    config.executionMode = 'LOCAL';
    await flush();
    pending.resolve({ list: [rule('9007199254740993', '旧规则')], total: 1 });
    await flush();
    config.executionMode = 'GOVERNANCE';
    await flush();
    expect(host.textContent).not.toContain('旧规则');
  });

  it('失败后清除旧选项，保留刷新入口且不展示失败提示条', async () => {
    vi.mocked(getCleanRulePage).mockResolvedValueOnce({
      list: [rule('9007199254740993', '已加载规则')],
      total: 1,
    });
    mount();
    await flush();
    expect(host.textContent).toContain('已加载规则');
    vi.mocked(getCleanRulePage).mockRejectedValueOnce(new Error('forbidden'));
    button('刷新').click();
    await flush();
    expect(host.textContent).not.toContain('已加载规则');
    expect(host.textContent).not.toContain('规则列表加载失败');
    expect(button('刷新')).toBeTruthy();
  });

  it('租户变化后清除已读取的名称和参数且丢弃旧请求', async () => {
    const pending = deferredPage();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(pending.promise);
    mount();
    config.governanceRules = [{ ruleId: '9007199254740993', field: 'code' }];
    harness.access.refreshToken = '2';
    await flush();
    pending.resolve({
      list: [rule('9007199254740993', '旧会话私有规则')],
      total: 1,
    });
    await flush();
    expect(host.textContent).not.toContain('旧会话私有规则');
    expect(host.textContent).not.toContain('TRIM');
    expect(host.textContent).toContain('请重新打开节点配置');
    expect(getCleanRulePage).toHaveBeenCalledTimes(1);
    expect(config.governanceRules).toEqual([
      { ruleId: '9007199254740993', field: 'code' },
    ]);
  });

  it('撤销查询权限立即清空选项且禁止追加规则', async () => {
    vi.mocked(getCleanRulePage).mockResolvedValueOnce({
      list: [rule('9007199254740993', '私有规则')],
      total: 1,
    });
    mount();
    await flush();
    harness.access.allowed = false;
    await flush();
    expect(host.textContent).not.toContain('私有规则');
    expect(host.textContent).toContain('没有治理清洗规则查询权限');
    expect(host.querySelector('select')?.disabled).toBe(true);
    expect(config.governanceRules).toEqual([]);
  });

  it('显式显示分页上限并支持按名称重新搜索，旧分页立即退出', async () => {
    const pending = deferredPage();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(pending.promise);
    mount();
    const input = host.querySelector('input')!;
    input.value = '电话';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    vi.mocked(getCleanRulePage).mockImplementation(async () => ({
      list: Array.from({ length: 100 }, (_, index) =>
        rule(String(index + 1), '新规则'),
      ),
      total: 2001,
    }));
    button('搜索').click();
    await flush();
    pending.resolve({
      list: Array.from({ length: 100 }, (_, index) =>
        rule(String(index + 1), '旧规则'),
      ),
      total: 2001,
    });
    await flush();
    expect(getCleanRulePage).toHaveBeenCalledTimes(21);
    expect(getCleanRulePage).toHaveBeenLastCalledWith({
      pageNo: 20,
      pageSize: 100,
      status: 0,
      name: '电话',
    });
    expect(host.textContent).toContain('前 2000 条');
    expect(host.textContent).not.toContain('旧规则');
  });

  it('卸载后多页读取不会继续请求下一页', async () => {
    const pending = deferredPage();
    vi.mocked(getCleanRulePage).mockReturnValueOnce(pending.promise);
    mount();
    app?.unmount();
    app = undefined;
    pending.resolve({
      list: Array.from({ length: 100 }, (_, index) =>
        rule(String(index + 1), '旧规则'),
      ),
      total: 1000,
    });
    await flush();
    expect(getCleanRulePage).toHaveBeenCalledTimes(1);
  });
});
