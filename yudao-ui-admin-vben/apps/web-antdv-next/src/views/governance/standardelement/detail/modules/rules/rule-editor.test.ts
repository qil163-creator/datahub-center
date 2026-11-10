/* eslint-disable vue/one-component-per-file -- 隔离基础控件，验证真实规则编辑器的请求生命周期与保存契约。 */
import type { App, PropType } from 'vue';

import type { QualityTaskApi } from '#/api/governance/quality/task';
import type { StandardElementRuleApi as Api } from '#/api/governance/standardelement/rule';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getElementRule,
  getElementRuleContext,
  getElementRuleTemplates,
  saveElementRule,
} from '#/api/governance/standardelement/rule';

import RuleEditor from './rule-editor.vue';

const harness = vi.hoisted(() => ({
  saved: vi.fn(),
  success: vi.fn(),
  access: {
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:standard-element:update'],
  },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.every((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('#/api/governance/standardelement/rule', () => ({
  getElementRule: vi.fn(),
  getElementRuleContext: vi.fn(),
  getElementRuleTemplates: vi.fn(),
  sampleElementRule: vi.fn(),
  saveElementRule: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: { onClosed?: () => void }) => {
    const visible = ref(false);
    return [
      defineComponent({
        props: { title: { type: String, default: '' } },
        setup:
          (props, { slots }) =>
          () =>
            visible.value
              ? h('section', { 'data-modal': props.title }, [
                  h('h2', props.title),
                  slots.default?.(),
                  slots.footer?.(),
                ])
              : null,
      }),
      {
        open: async () => {
          visible.value = true;
        },
        close: async () => {
          visible.value = false;
          options.onClosed?.();
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = defineComponent({
    props: {
      value: { type: [String, Number], default: undefined },
      disabled: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-alert': '' }, [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled || props.loading },
            slots.default?.(),
          ),
    }),
    Input: input,
    TextArea: input,
    Select: container,
    RadioGroup: container,
    Spin: container,
    message: { success: harness.success, warning: vi.fn() },
  };
});
vi.mock('../../../../quality/task/modules/rule-config.vue', () => ({
  default: defineComponent({
    props: {
      value: {
        type: Object as PropType<QualityTaskApi.Evaluation>,
        required: true,
      },
    },
    emits: ['update:value'],
    setup: (props, { emit }) => () =>
      h('div', [
        h('pre', { 'data-config': '' }, JSON.stringify(props.value.config)),
        h(
          'button',
          {
            onClick: () => {
              emit('update:value', {
                ...props.value,
                columnNames: ['order_no', 'order_date'],
              });
            },
          },
          '选择组合字段',
        ),
      ]),
  }),
}));
vi.mock('./clean-config.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));

const template: Api.Template = {
  id: '9007199254740993',
  kind: 'AUDIT',
  code: '111',
  name: '枚举值校验',
  strategyKey: 'ENUM_VALIDATION',
  enabled: true,
  engineSupported: true,
  config: {},
};
function record(id: string, name: string): Api.Rule {
  return {
    id,
    elementId: 228,
    kind: 'AUDIT',
    templateId: template.id,
    name,
    status: 0,
    revision: '9007199254740995',
    config: { useCodeTable: false, validValues: ['A', 'B'] },
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App;
let host: HTMLDivElement;
let editor: InstanceType<typeof RuleEditor>;
async function settle() {
  for (let i = 0; i < 8; i++) await nextTick();
}
function button(text: string) {
  const found = [...host.querySelectorAll('button')].find(
    (node) => node.textContent?.trim() === text,
  );
  if (!found) throw new Error(`Missing button: ${text}`);
  return found;
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:standard-element:update'],
  });
  vi.mocked(getElementRuleTemplates).mockResolvedValue([template]);
  vi.mocked(getElementRuleContext).mockResolvedValue({
    fields: [],
    referenceObjects: [],
  });
  vi.mocked(saveElementRule).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RuleEditor, {
    elementId: 228,
    kind: 'AUDIT',
    onSaved: harness.saved,
  });
  editor = app.mount(host) as InstanceType<typeof RuleEditor>;
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('数据元关联规则编辑器', () => {
  it.each([
    ['311', 'COMPOSITE_UNIQUENESS_VALIDATION'],
    ['205', 'GROUP_FIELD_COMPLETENESS'],
  ])('已开放的 %s 加载授权字段并保存多字段绑定', async (code, strategyKey) => {
    vi.mocked(getElementRuleTemplates).mockResolvedValue([
      { ...template, code, strategyKey },
    ]);
    await editor.open();
    host.querySelector<HTMLButtonElement>('.standard-rule-card-grid button')!.click();
    await settle();
    expect(getElementRuleContext).toHaveBeenCalledWith(228);
    button('选择组合字段').click();
    const name = host.querySelector<HTMLInputElement>(
      'input[placeholder="请输入稽查名称"]',
    )!;
    name.value = '订单组合字段检查';
    name.dispatchEvent(new Event('input'));
    await settle();
    button('确定').click();
    await settle();
    expect(saveElementRule).toHaveBeenCalledWith(
      expect.objectContaining({
        config: expect.objectContaining({
          columnNames: ['order_no', 'order_date'],
        }),
      }),
    );
  });
  it('保存响应未返回时切换会话，关闭旧弹窗且不发出保存成功事件', async () => {
    const pending = deferred<boolean>();
    const existing = record('9007199254741005', '会话规则');
    vi.mocked(getElementRule).mockResolvedValue(existing);
    vi.mocked(saveElementRule).mockReturnValue(pending.promise);
    await editor.open(existing);
    button('确定').click();
    await settle();
    expect(saveElementRule).toHaveBeenCalledTimes(1);
    harness.access.accessToken = 'actor-b';
    await settle();
    pending.resolve(true);
    await settle();
    expect(host.querySelector('[data-modal]')).toBeNull();
    expect(harness.saved).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
  });
  it('较慢的旧详情不能覆盖随后打开的规则', async () => {
    const slow = deferred<Api.Rule>();
    const first = record('9007199254741001', '旧规则');
    const second = record('9007199254741002', '新规则');
    vi.mocked(getElementRule)
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValueOnce(second);
    const pending = editor.open(first);
    await settle();
    await editor.open(second);
    slow.resolve(first);
    await pending;
    await settle();
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入稽查名称"]',
      )?.value,
    ).toBe('新规则');
    button('确定').click();
    await settle();
    expect(saveElementRule).toHaveBeenCalledWith(
      expect.objectContaining({ id: second.id, name: '新规则' }),
    );
  });

  it('详情失败后重试仍使用原记录 ID，随后保存为修改', async () => {
    const existing = record('9007199254741003', '重试规则');
    vi.mocked(getElementRule)
      .mockRejectedValueOnce(new Error('load failed'))
      .mockResolvedValueOnce(existing);
    await editor.open(existing);
    await settle();
    expect(host.textContent).not.toContain('规则加载失败');
    button('确定').click();
    await settle();
    expect(saveElementRule).not.toHaveBeenCalled();
    await editor.open(existing);
    await settle();
    expect(getElementRule).toHaveBeenNthCalledWith(2, 228, existing.id);
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入稽查名称"]',
      )?.value,
    ).toBe(existing.name);
    button('确定').click();
    await settle();
    expect(saveElementRule).toHaveBeenCalledWith(
      expect.objectContaining({ id: existing.id, revision: existing.revision }),
    );
  });

  it('手工枚举补齐显示标签并保留所有旧值及已命名标签', async () => {
    const existing = record('9007199254741004', '枚举规则');
    existing.config.valueLabels = [{ value: 'A', label: '甲' }];
    vi.mocked(getElementRule).mockResolvedValue(existing);
    await editor.open(existing);
    await settle();
    expect(
      JSON.parse(host.querySelector('[data-config]')!.textContent!),
    ).toMatchObject({
      valueLabels: [
        { value: 'A', label: '甲' },
        { value: 'B', label: 'B' },
      ],
    });
    button('确定').click();
    await settle();
    expect(saveElementRule).toHaveBeenCalledWith(
      expect.objectContaining({
        config: expect.objectContaining({
          validValues: ['A', 'B'],
          valueLabels: [
            { value: 'A', label: '甲' },
            { value: 'B', label: 'B' },
          ],
        }),
      }),
    );
    expect(existing.config.valueLabels).toEqual([{ value: 'A', label: '甲' }]);
  });
});
