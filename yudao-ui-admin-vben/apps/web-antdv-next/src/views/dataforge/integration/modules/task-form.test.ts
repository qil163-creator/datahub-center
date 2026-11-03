import type { App } from 'vue';

import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createIntegration,
  updateIntegration,
} from '#/api/dataforge/integration';

import { defaultTask } from '../model';
import TaskForm from './task-form.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/integration', () => ({
  createIntegration: vi.fn(async () => '9007199254740993'),
  updateIntegration: vi.fn(async () => true),
}));
vi.mock('#/components/cron-tab', () => ({
  CronTab: defineComponent({
    props: { modelValue: String },
    setup: (props) => () => h('input', { value: props.modelValue }),
  }),
}));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number], disabled: Boolean },
    emits: ['update:value', 'change'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) => {
            const value = (event.target as HTMLInputElement).value;
            emit('update:value', value);
            emit('change', value);
          },
        }),
  });
  const Container = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Input: Field,
    InputNumber: Field,
    Select: Field,
    TextArea: Field,
    TreeSelect: Field,
    Radio: Container,
    RadioGroup: Container,
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'button',
            { disabled: props.disabled || props.loading },
            slots.default?.(),
          ),
    }),
    Modal: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', [slots.default?.(), slots.footer?.()])
            : null,
    }),
    message: { success: vi.fn() },
  };
});

let app: App;
let host: HTMLDivElement;
let props: { mode: Api.Mode; open: boolean; task?: Api.Task };
let configured: ReturnType<typeof vi.fn<(id: Api.Id) => void>>;
let saved: ReturnType<typeof vi.fn<() => void>>;
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}
function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(
    `[aria-label="${label}"]`,
  )!;
  element.value = value;
  element.dispatchEvent(new Event('input'));
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
function validTask(): Api.Task {
  return {
    ...defaultTask('OFFLINE'),
    id: '9007199254740995',
    revision: 8,
    categoryId: '9007199254740997',
    ownerId: '9007199254740999',
    name: '水位同步',
    contactNumber: '原负责人联系电话',
    configurationStatus: 'UNCONFIGURED',
    executable: false,
    importedSnapshot: false,
    lastRunStatus: 'IDLE',
    scheduleStatus: 0,
  };
}
beforeEach(() => {
  vi.clearAllMocks();
  configured = vi.fn();
  saved = vi.fn();
  props = reactive({ mode: 'OFFLINE', open: true });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () =>
      h(TaskForm, {
        ...props,
        categories: [{ id: '9007199254740997', parentId: '0', name: '同步' }],
        owners: [
          { id: '9007199254740999', name: '原负责人' },
          { id: '9007199254741001', name: '新负责人' },
        ],
        onConfigure: configured,
        onSaved: saved,
        'onUpdate:open': (value: boolean) => {
          props.open = value;
        },
      }),
  });
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('集成任务保存与流程配置入口', () => {
  it('实时任务只展示Flink，拦截历史非Flink配置且不默默改写', async () => {
    props.mode = 'REALTIME';
    await flush();
    const cards = host.querySelectorAll('.qi-engine-field .qi-engine-card');
    expect(cards).toHaveLength(1);
    expect(cards[0]?.textContent).toContain('Flink');
    props.task = { ...validTask(), mode: 'REALTIME', engine: 'SPARK' };
    await flush();
    button('仅保存').click(); await flush();
    expect(host.textContent).toContain('实时任务只能使用Flink执行引擎');
    expect(updateIntegration).not.toHaveBeenCalled();
    expect(props.task.engine).toBe('SPARK');
  });
  it('创建成功后用服务返回的无损编号进入配置，保留仅保存语义', async () => {
    input('任务类目', '9007199254740997');
    input('任务名称', '新增同步任务');
    input('责任人', '9007199254740999');
    await flush();
    button('保存并配置流程').click();
    await flush();
    expect(createIntegration).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryId: '9007199254740997',
        ownerId: '9007199254740999',
        name: '新增同步任务',
      }),
    );
    expect(configured).toHaveBeenCalledExactlyOnceWith('9007199254740993');
    expect(saved).toHaveBeenCalledOnce();
    expect(props.open).toBe(false);
  });

  it('编辑后仅保存不跳配置，配置保存沿用任务编号与修订号', async () => {
    props.task = validTask();
    await flush();
    button('仅保存').click();
    await flush();
    expect(updateIntegration).toHaveBeenCalledWith(
      expect.objectContaining({ id: '9007199254740995', revision: 8 }),
    );
    expect(configured).not.toHaveBeenCalled();
    props.open = true;
    await flush();
    button('保存并配置流程').click();
    await flush();
    expect(configured).toHaveBeenCalledExactlyOnceWith('9007199254740995');
  });

  it('换负责人后不会回显上一位负责人的联系电话', async () => {
    props.task = validTask();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="联系电话"]')?.value,
    ).toBe('原负责人联系电话');
    input('责任人', '9007199254741001');
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="联系电话"]')?.value,
    ).toBe('');
  });

  it('关闭并重开后忽略旧保存结果，不将旧任务跳转到新表单', async () => {
    let complete: (id: Api.Id) => void = () => {};
    vi.mocked(createIntegration).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    input('任务类目', '9007199254740997');
    input('任务名称', '旧表单');
    input('责任人', '9007199254740999');
    await flush();
    button('保存并配置流程').click();
    await flush();
    props.open = false;
    await flush();
    props.open = true;
    await flush();
    complete('9007199254740993');
    await flush();
    expect(configured).not.toHaveBeenCalled();
    expect(saved).not.toHaveBeenCalled();
    expect(props.open).toBe(true);
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')?.value,
    ).toBe('');
  });
});
