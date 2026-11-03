/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- Minimal component stubs exercise form behavior. */
import type { App } from 'vue';

import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getCategoryCleanRuleList } from '#/api/governance/categorycleanrule';
import {
  createCleanRule,
  getCleanRule,
  getCleanStrategyList,
  updateCleanRule,
} from '#/api/governance/cleanrule';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '7' } },
  error: vi.fn(),
  success: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/utils', () => ({ handleTree: (rows: unknown[]) => rows }));
vi.mock('#/api/governance/categorycleanrule', () => ({
  getCategoryCleanRuleList: vi.fn(),
}));
vi.mock('#/api/governance/cleanrule', () => ({
  createCleanRule: vi.fn(),
  getCleanRule: vi.fn(),
  getCleanStrategyList: vi.fn(),
  updateCleanRule: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
  }) => {
    const visible = ref(false);
    const set = (open: boolean) => {
      visible.value = open;
      options.onOpenChange(open);
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value
              ? h('section', [
                  slots.default?.(),
                  h(
                    'button',
                    {
                      'data-action': 'save',
                      onClick: () => void options.onConfirm(),
                    },
                    '保存',
                  ),
                  h(
                    'button',
                    { 'data-action': 'close', onClick: () => set(false) },
                    '关闭',
                  ),
                ])
              : null,
      }),
      {
        open: () => set(true),
        close: () => set(false),
        lock: vi.fn(),
        unlock: vi.fn(),
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const input = (tag: string, numeric = false) =>
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(tag, {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) =>
              emit(
                'update:value',
                numeric
                  ? Number((event.target as HTMLInputElement).value)
                  : (event.target as HTMLInputElement).value,
              ),
          }),
    });
  return {
    message: { error: harness.error, success: harness.success },
    Input: input('input'),
    TextArea: input('textarea'),
    InputNumber: input('input', true),
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Form: defineComponent({
      setup: (_, { slots, expose }) => {
        expose({ validate: async () => {} });
        return () => h('form', slots.default?.());
      },
    }),
    FormItem: defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.label, slots.default?.()]),
    }),
    TreeSelect: input('input'),
    Select: defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: props.value,
              disabled: props.disabled,
              onChange: (event: Event) => {
                const text = (event.target as HTMLSelectElement).value;
                const selected = props.options?.find(
                  (item: { value: unknown }) => String(item.value) === text,
                )?.value;
                emit('update:value', selected);
                emit('change', selected);
              },
            },
            [
              h('option', { value: '' }, '请选择'),
              ...(props.options ?? []).map(
                (item: { label: string; value: string }) =>
                  h('option', { value: item.value }, item.label),
              ),
            ],
          ),
    }),
    Button: defineComponent({
      props: ['disabled'],
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
  };
});

let app: App;
let host: HTMLDivElement;
let view: {
  open: (id?: number | string, categoryId?: number | string) => Promise<void>;
};
async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function action(name: string) {
  host.querySelector<HTMLButtonElement>(`button[data-action=${name}]`)!.click();
}
function input(id: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(`#${id}`)!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}
const strategy: GovernanceCleanRuleApi.Strategy = {
  strategyKey: 'WITHIN_BOUNDARY',
  strategyName: '数值边界调整',
  configExample: '{"columns":["age"],"min":"0","max":"150","handleType":1}',
};
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '7' } });
  vi.mocked(getCategoryCleanRuleList).mockResolvedValue([]);
  vi.mocked(getCleanStrategyList).mockResolvedValue([strategy]);
  vi.mocked(getCleanRule).mockResolvedValue({
    id: '9007199254740993',
    name: '已有规则',
    categoryId: '9007199254740994',
    strategyKey: strategy.strategyKey,
    config: { min: '1', max: '2' },
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Form);
  view = app.mount(host) as unknown as typeof view;
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('治理清洗规则业务参数表单', () => {
  it('未编辑参数时省略 config，保留旧客户端兼容并无损提交规则和类目 ID', async () => {
    await view.open('9007199254740993');
    await flush();
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        categoryId: '9007199254740994',
      }),
    );
    expect(vi.mocked(updateCleanRule).mock.calls[0]![0]).not.toHaveProperty(
      'config',
    );
  });
  it('业务输入保留精确小数，不经过 JSON number 或浮点转换', async () => {
    await view.open('9007199254740993');
    await flush();
    const exact = '9007199254740993.123456789012345678901';
    input('clean-parameter-min', exact);
    await flush();
    input('clean-parameter-max', '9007199254740994');
    await flush();
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({
        config: { min: exact, max: '9007199254740994' },
      }),
    );
    expect(host.querySelector('textarea[placeholder*=JSON]')).toBeNull();
  });
  it('切换策略从示例创建仅含函数参数的新配置', async () => {
    await view.open(undefined, '9007199254740994');
    await flush();
    const select = [...host.querySelectorAll<HTMLSelectElement>('select')].find(
      (item) => item.textContent?.includes(strategy.strategyName),
    )!;
    select.value = strategy.strategyKey;
    select.dispatchEvent(new Event('change'));
    await flush();
    action('save');
    await flush();
    expect(createCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryId: '9007199254740994',
        config: { min: '0', max: '150', handleType: 1 },
      }),
    );
  });
  it('历史未知策略回显原值，不赋予可编辑参数能力', async () => {
    vi.mocked(getCleanRule).mockResolvedValue({
      id: '8',
      strategyKey: 'OLD_FUNCTION',
      config: {},
    });
    await view.open('8');
    await flush();
    expect(host.textContent).toContain('历史或未知策略（OLD_FUNCTION）');
    expect(host.textContent).toContain('当前策略没有可编辑的参数表单');
    expect(host.querySelector('#clean-parameter-min')).toBeNull();
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({ strategyKey: 'OLD_FUNCTION' }),
    );
  });
  it('迟到的旧详情不能覆盖新打开的规则', async () => {
    let finish!: (value: GovernanceCleanRuleApi.CleanRule) => void;
    vi.mocked(getCleanRule)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve;
        }),
      )
      .mockResolvedValueOnce({
        id: '2',
        name: '当前规则',
        strategyKey: strategy.strategyKey,
        config: { min: '3', max: '4' },
      });
    const old = view.open('1');
    await view.open('2');
    await flush();
    finish({
      id: '1',
      name: '迟到私有规则',
      strategyKey: strategy.strategyKey,
      config: { min: '100', max: '200' },
    });
    await old;
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('#clean-parameter-min')!.value,
    ).toBe('3');
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({ id: '2', name: '当前规则' }),
    );
  });
  it.each(['close', 'session', 'user', 'unmount'])(
    '关闭或身份失效后拒绝迟到详情：%s',
    async (reason) => {
      let finish!: (value: GovernanceCleanRuleApi.CleanRule) => void;
      vi.mocked(getCleanRule).mockReturnValue(
        new Promise((resolve) => {
          finish = resolve;
        }),
      );
      const pending = view.open('1');
      await flush();
      if (reason === 'close') action('close');
      else if (reason === 'session') harness.access.refreshToken = '2';
      else if (reason === 'user') harness.user.userInfo.userId = '9';
      else app.unmount();
      finish({
        id: '1',
        name: 'private late',
        strategyKey: strategy.strategyKey,
        config: { min: '99', max: '100' },
      });
      await pending;
      await flush();
      expect(host.querySelector('section')).toBeNull();
      expect(updateCleanRule).not.toHaveBeenCalled();
    },
  );
  it('加载失败和未完成加载时不可提交', async () => {
    vi.mocked(getCleanRule).mockRejectedValue(new Error('forbidden'));
    await view.open('1');
    await flush();
    expect(host.textContent).toContain('加载失败');
    action('save');
    await flush();
    expect(updateCleanRule).not.toHaveBeenCalled();
  });
  it('结构参数可在业务表单添加映射，字符串原值不转换为数字', async () => {
    vi.mocked(getCleanStrategyList).mockResolvedValue([
      { strategyKey: 'MENU_CUSTOM', strategyName: '枚举值映射标准化' },
    ]);
    vi.mocked(getCleanRule).mockResolvedValue({
      id: '3',
      strategyKey: 'MENU_CUSTOM',
      config: { stringValue: [] },
    });
    await view.open('3');
    await flush();
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === '添加映射')!
      .click();
    await flush();
    const source = host.querySelector<HTMLInputElement>(
      'input[placeholder="原值（可为空串）"]',
    )!;
    source.value = '9007199254740993';
    source.dispatchEvent(new Event('input'));
    await flush();
    const target = host.querySelector<HTMLInputElement>(
      'input[placeholder="目标值（可为空串）"]',
    )!;
    target.value = '启用';
    target.dispatchEvent(new Event('input'));
    await flush();
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledWith(
      expect.objectContaining({
        config: { stringValue: [{ value: '9007199254740993', name: '启用' }] },
      }),
    );
  });
  it('历史畸形数组保留预览与校验提示，参数表单仍可打开', async () => {
    vi.mocked(getCleanStrategyList).mockResolvedValue([
      { strategyKey: 'MENU_CUSTOM', strategyName: '枚举值映射标准化' },
    ]);
    vi.mocked(getCleanRule).mockResolvedValue({
      id: '3',
      strategyKey: 'MENU_CUSTOM',
      config: { stringValue: [null] },
    });
    await view.open('3');
    await flush();
    expect(host.textContent).toContain('唯一原值映射');
    expect(host.querySelector('pre')!.textContent).toContain('null');
    expect(
      host.querySelector('input[placeholder="原值（可为空串）"]'),
    ).not.toBeNull();
  });
  it('详情加载中不能保存，保存期间不能重复提交', async () => {
    let loaded!: (value: GovernanceCleanRuleApi.CleanRule) => void;
    vi.mocked(getCleanRule).mockReturnValue(
      new Promise((resolve) => {
        loaded = resolve;
      }),
    );
    const pending = view.open('2');
    await flush();
    action('save');
    await flush();
    expect(updateCleanRule).not.toHaveBeenCalled();
    loaded({
      id: '2',
      strategyKey: strategy.strategyKey,
      config: { min: '1', max: '2' },
    });
    await pending;
    await flush();
    let saved!: () => void;
    vi.mocked(updateCleanRule).mockReturnValue(
      new Promise((resolve) => {
        saved = () => resolve(undefined);
      }),
    );
    action('save');
    await flush();
    action('save');
    await flush();
    expect(updateCleanRule).toHaveBeenCalledTimes(1);
    saved();
    await flush();
    expect(host.querySelector('section')).toBeNull();
  });
});
