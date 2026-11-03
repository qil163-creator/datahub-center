import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDataPushConfig,
  getDataPushConfig,
  testDataPushConfigConnection,
  updateDataPushConfig,
} from '#/api/dataforge/datapush/config';

import { CHANNEL_TYPE } from '../model';
import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: {} as any,
  currentStep: {} as any,
  options: {} as any,
  values: {} as any,
  setValues: vi.fn(),
  reset: vi.fn(),
  validateAll: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { slots }: SetupContext) =>
    h('button', slots.default?.()),
  message: { success: vi.fn() },
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/views/dataforge/common/form-wizard-page.vue', () => ({
  default: (_: unknown, { slots }: SetupContext) =>
    h('main', [slots.default?.(), slots.actions?.()]),
}));
vi.mock('#/views/dataforge/common/form-wizard', () => ({
  useDataforgeWizardForm: (options: any) => {
    harness.options = options;
    return {
      Form: () => h('form'),
      formApi: {
        reset: harness.reset,
        setValues: harness.setValues,
        getValues: async () => harness.values,
      },
      currentStep: harness.currentStep,
      steps: options.steps,
      busy: ref(false),
      resetSteps: () => {
        harness.currentStep.value = 0;
      },
      goToStep: async (step: number) => {
        harness.currentStep.value = step;
      },
      validateAll: harness.validateAll,
    };
  },
}));
vi.mock('../data', async () => ({
  ...(await import('../model')),
  useFormSchema: () => [],
}));
vi.mock('#/api/dataforge/datapush/config', () => ({
  createDataPushConfig: vi.fn(),
  getDataPushConfig: vi.fn(),
  testDataPushConfigConnection: vi.fn(),
  updateDataPushConfig: vi.fn(),
}));

let app: App | undefined;
let state: any;
const close = vi.fn();
const success = vi.fn();

async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function mount(initialData?: { id?: number | string }) {
  app = createApp(Form, { initialData, onClose: close, onSuccess: success });
  state = (app.mount(document.createElement('div')) as any).$.setupState;
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.currentStep = ref(0);
  harness.values = {};
  harness.reset.mockResolvedValue(undefined);
  harness.setValues.mockImplementation(async (values) => {
    harness.values = values;
  });
  harness.validateAll.mockResolvedValue(true);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('推送配置页面向导生命周期', () => {
  it('挂载新建表单使用横向布局，返回列表发出关闭事件', async () => {
    mount();
    await flush();
    expect(harness.options.layout).toBe('horizontal');
    expect(harness.options.steps[0].title).toBe('配置基础信息');
    expect(harness.values.channelType).toBe(CHANNEL_TYPE.HTTP);
    expect(getDataPushConfig).not.toHaveBeenCalled();
    state.handleClose();
    expect(close).toHaveBeenCalledOnce();
  });

  it('编辑按无损 ID 读取详情，保存成功后发出成功事件', async () => {
    const id = '9007199254740993';
    vi.mocked(getDataPushConfig).mockResolvedValue({
      id,
      name: '推送配置',
      channelType: CHANNEL_TYPE.HTTP,
      config: { http: { url: 'https://consumer.example.com/push' } },
    });
    mount({ id });
    await flush();
    expect(getDataPushConfig).toHaveBeenCalledWith(id);
    harness.currentStep.value = 2;
    await state.handleSubmit();
    expect(updateDataPushConfig).toHaveBeenCalledWith(
      expect.objectContaining({ id, name: '推送配置' }),
    );
    expect(createDataPushConfig).not.toHaveBeenCalled();
    expect(success).toHaveBeenCalledOnce();
  });

  it('卸载后详情响应不再回填表单', async () => {
    const detail = deferred<any>();
    vi.mocked(getDataPushConfig).mockReturnValue(detail.promise);
    mount({ id: '9007199254740993' });
    await flush();
    app?.unmount();
    app = undefined;
    detail.resolve({ id: '9007199254740993', name: '旧配置' });
    await flush();
    expect(harness.setValues).not.toHaveBeenCalled();
  });

  it('详情加载失败关闭向导，空表单无法提交更新', async () => {
    vi.mocked(getDataPushConfig).mockRejectedValue(new Error('详情请求失败'));
    mount({ id: '9007199254740993' });
    await flush();
    expect(close).toHaveBeenCalledOnce();
    expect(harness.setValues).not.toHaveBeenCalled();
    harness.currentStep.value = 2;
    await state.handleSubmit();
    expect(updateDataPushConfig).not.toHaveBeenCalled();
    expect(createDataPushConfig).not.toHaveBeenCalled();
    expect(success).not.toHaveBeenCalled();
    expect(message.success).not.toHaveBeenCalled();
  });

  it('切换登录会话关闭向导并忽略旧会话详情', async () => {
    const detail = deferred<any>();
    vi.mocked(getDataPushConfig).mockReturnValue(detail.promise);
    mount({ id: '9007199254740993' });
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    expect(close).toHaveBeenCalledOnce();
    detail.resolve({ id: '9007199254740993', name: '旧会话配置' });
    await flush();
    expect(harness.setValues).not.toHaveBeenCalled();
  });

  it('连接测试期间配置改变时不显示旧测试成功结果', async () => {
    const connection = deferred<boolean>();
    vi.mocked(testDataPushConfigConnection).mockReturnValue(connection.promise);
    mount();
    await flush();
    harness.currentStep.value = 1;
    Object.assign(harness.values, {
      name: '推送配置',
      httpUrl: 'https://consumer.example.com/push',
    });
    const pending = state.handleTestConnection();
    await flush();
    harness.values.httpUrl = 'https://consumer.example.com/changed';
    connection.resolve(true);
    await pending;
    expect(message.success).not.toHaveBeenCalled();
  });

  it('保存期间拦截重复提交，卸载后不发出成功事件', async () => {
    const save = deferred<number>();
    vi.mocked(createDataPushConfig).mockReturnValue(save.promise);
    mount();
    await flush();
    harness.currentStep.value = 2;
    Object.assign(harness.values, {
      name: '推送配置',
      httpUrl: 'https://consumer.example.com/push',
    });
    const pending = state.handleSubmit();
    await flush();
    await state.handleSubmit();
    expect(createDataPushConfig).toHaveBeenCalledOnce();
    app?.unmount();
    app = undefined;
    save.resolve(1);
    await pending;
    expect(success).not.toHaveBeenCalled();
    expect(message.success).not.toHaveBeenCalled();
  });
});
