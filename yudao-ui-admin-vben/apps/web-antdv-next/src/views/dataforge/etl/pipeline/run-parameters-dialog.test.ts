import type { App } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getEtlRunParameters } from '#/api/dataforge/etl';

import RunParametersDialog from './run-parameters-dialog.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '7' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('#/api/dataforge/etl', () => ({ getEtlRunParameters: vi.fn() }));
vi.mock('antdv-next', () => ({
  Modal: defineComponent({
    props: ['open'],
    emits: ['ok', 'cancel'],
    setup:
      (props, { emit, slots }) =>
      () =>
        props.open
          ? h('section', [
              slots.default?.(),
              h('button', { onClick: () => emit('ok') }, '确定'),
              h('button', { onClick: () => emit('cancel') }, '取消'),
            ])
          : null,
  }),
  Spin: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', props.message),
  }),
  Checkbox: defineComponent({
    props: ['checked'],
    emits: ['update:checked'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('label', [
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            onChange: (e: Event) =>
              emit('update:checked', (e.target as HTMLInputElement).checked),
          }),
          slots.default?.(),
        ]),
  }),
  Input: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          type: 'text',
          value: props.value,
          onInput: (e: Event) =>
            emit('update:value', (e.target as HTMLInputElement).value),
        }),
  }),
  Select: defineComponent({
    props: ['value', 'options'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            value: props.value,
            onChange: (e: Event) =>
              emit('update:value', (e.target as HTMLSelectElement).value),
          },
          props.options.map((item: { label: string; value: string }) =>
            h('option', { value: item.value }, item.label),
          ),
        ),
  }),
}));
let app: App;
let host: HTMLDivElement;
let view: {
  close: () => void;
  open: (id: string) => Promise<unknown>;
  openPreview: (items: DataforgeEtlApi.ParamItem[]) => Promise<unknown>;
};
async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent === text,
  )!;
}
function input(value: string) {
  const field = host.querySelector<HTMLInputElement>('input[type=text]')!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '7' } });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RunParametersDialog);
  view = app.mount(host) as unknown as typeof view;
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('运行参数确认', () => {
  it('历史null默认值不显示为默认，必填参数仍需要输入', async () => {
    vi.mocked(getEtlRunParameters).mockResolvedValue(
      JSON.parse(
        '{"published":true,"definitionRevision":4,"parameters":[{"name":"requiredValue","required":true,"type":null,"defaultValue":null,"minLength":null,"maxLength":null},{"name":"optionalValue","defaultValue":null}]}',
      ),
    );
    const result = view.open('1');
    await flush();
    expect(host.textContent).not.toContain('默认值：');
    expect(
      host.querySelector<HTMLInputElement>('input[type=checkbox]')!.checked,
    ).toBe(true);
    button('确定').click();
    await flush();
    expect(host.textContent).toContain('必填参数不能为空');
    input('value');
    await flush();
    button('确定').click();
    await expect(result).resolves.toEqual({
      expectedDefinitionRevision: 4,
      parameters: { requiredValue: 'value' },
    });
  });
  it('读取实际发布定义，提交原样整数、小数默认值与定义修订', async () => {
    vi.mocked(getEtlRunParameters).mockResolvedValue({
      published: true,
      definitionRevision: 9,
      parameters: [
        { name: 'id', type: 'INTEGER', required: true },
        {
          name: 'amount',
          type: 'DECIMAL',
          defaultValue: '9007199254740993.123456789',
        },
      ],
    });
    const result = view.open('9007199254740993');
    await flush();
    expect(host.textContent).toContain('已发布版本');
    expect(host.textContent).toContain('修订 9');
    input('9007199254740995');
    await flush();
    button('确定').click();
    await expect(result).resolves.toEqual({
      expectedDefinitionRevision: 9,
      parameters: {
        id: '9007199254740995',
        amount: '9007199254740993.123456789',
      },
    });
  });
  it('区分未提供与显式空串，空串不能绕过最小长度', async () => {
    const result = view.openPreview([
      { name: 'text', minLength: 1 },
      { name: 'absent' },
    ]);
    await flush();
    const checkbox = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    await flush();
    button('确定').click();
    await flush();
    expect(host.textContent).toContain('小于最小长度');
    input('x');
    await flush();
    button('确定').click();
    await expect(result).resolves.toEqual({
      expectedDefinitionRevision: undefined,
      parameters: { text: 'x' },
    });
  });
  it('关闭与新打开淘汰旧定义响应', async () => {
    let finish!: (value: DataforgeEtlApi.RunParameterDefinition) => void;
    vi.mocked(getEtlRunParameters)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve;
        }),
      )
      .mockResolvedValueOnce({
        published: false,
        definitionRevision: 3,
        parameters: [{ name: 'newValue' }],
      });
    const old = view.open('1');
    view.close();
    await expect(old).resolves.toBeUndefined();
    const current = view.open('2');
    await flush();
    finish({
      published: true,
      definitionRevision: 8,
      parameters: [{ name: 'oldPrivate' }],
    });
    await flush();
    expect(host.textContent).toContain('当前已保存草稿');
    expect(host.textContent).not.toContain('oldPrivate');
    view.close();
    await expect(current).resolves.toBeUndefined();
  });
  it('身份切换立即清空参数并拒绝迟到结果', async () => {
    let finish!: (value: DataforgeEtlApi.RunParameterDefinition) => void;
    vi.mocked(getEtlRunParameters).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const result = view.open('1');
    harness.user.userInfo.userId = '8';
    await flush();
    finish({
      published: true,
      definitionRevision: 2,
      parameters: [{ name: 'privateValue', defaultValue: 'secret' }],
    });
    await flush();
    await expect(result).resolves.toBeUndefined();
    expect(host.textContent).not.toContain('secret');
    expect(host.querySelector('section')).toBeNull();
  });
  it('加载失败不可按空定义提交，值中的宏文本不可提交', async () => {
    vi.mocked(getEtlRunParameters).mockRejectedValue(new Error('forbidden'));
    const failed = view.open('1');
    await flush();
    button('确定').click();
    await flush();
    expect(host.textContent).toContain('定义加载失败');
    view.close();
    await expect(failed).resolves.toBeUndefined();
    const result = view.openPreview([{ name: 'p', required: true }]);
    await flush();
    input(`\${params.other}`);
    await flush();
    button('确定').click();
    await flush();
    expect(host.textContent).toContain('不能包含');
    view.close();
    await expect(result).resolves.toBeUndefined();
  });
});
