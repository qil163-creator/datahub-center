import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDataPushConfig,
  getDataPushConfig,
  testDataPushConfigConnection,
} from '#/api/dataforge/datapush/config';
import {
  createDataPushTask,
  getDataPushTask,
  previewDataPushTask,
  updateDataPushTask,
} from '#/api/dataforge/datapush/task';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: {} as any,
  currentStep: {} as any,
  options: {} as any,
  values: {} as any,
  setValues: vi.fn(),
  setFieldValue: vi.fn(),
  reset: vi.fn(),
  validateAll: vi.fn(),
  validateField: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('antdv-next', () => ({
  Button: (_: unknown, { slots }: SetupContext) =>
    h('button', slots.default?.()),
  message: { success: vi.fn(), warning: vi.fn() },
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
        setFieldValue: harness.setFieldValue,
        getValues: async () => harness.values,
        validateField: harness.validateField,
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
vi.mock('../data', () => ({
  PUSH_MODE: { FULL: 1, INCREMENT: 2 },
  PUSH_TYPE: { TABLE: 1, SQL: 2 },
}));
// 这里测试页面编排；真实字段转换和 Schema 由 form-model.test.ts 覆盖。
vi.mock('../form-model', () => ({
  RECEIVER_FIELDS: ['channelType', 'httpUrl'],
  UNIFIED_TASK_STEPS: [
    { title: '基本信息', fields: ['name'] },
    { title: '接收端配置', fields: ['channelType', 'httpUrl'] },
    { title: '数据来源', fields: ['sourceDatabaseId', 'tableName'] },
    { title: '推送与调度', fields: ['pushMode'] },
    { title: '确认提交', fields: [] },
  ],
  useUnifiedFormSchema: () => [],
  getDefaultUnifiedFormValues: () => ({ channelType: 1, pushType: 1 }),
  toUnifiedFormValues: (task: any, config: any) => ({
    ...task,
    channelType: config.channelType,
    httpUrl: config.config?.http?.url,
    httpHeaders: config.config?.http?.headers,
  }),
  toUnifiedSaveReq: (values: any) => ({
    id: values.id,
    name: values.name,
    sourceDatabaseId: values.sourceDatabaseId,
    tableName: values.tableName,
    pushConfig: {
      id: values.id ? values.pushConfigId : undefined,
      name: values.name,
      channelType: values.channelType,
      config: {
        http: { url: values.httpUrl, headers: values.httpHeaders },
      },
    },
  }),
}));
vi.mock('#/api/dataforge/datapush/config', () => ({
  createDataPushConfig: vi.fn(),
  getDataPushConfig: vi.fn(),
  testDataPushConfigConnection: vi.fn(),
}));
vi.mock('#/api/dataforge/datapush/task', () => ({
  createDataPushTask: vi.fn(),
  getDataPushTask: vi.fn(),
  previewDataPushTask: vi.fn(),
  updateDataPushTask: vi.fn(),
}));

let app: App | undefined;
let state: any;
const close = vi.fn();
const success = vi.fn();

async function flush() {
  for (let count = 0; count < 6; count++) {
    await Promise.resolve();
    await nextTick();
  }
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

function fillTask() {
  Object.assign(harness.values, {
    name: '客户推送',
    channelType: 1,
    httpUrl: 'https://consumer.example.com/push',
    sourceDatabaseId: '9007199254740995',
    pushType: 1,
    tableName: 'customer',
    pushMode: 1,
  });
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
  harness.validateField.mockResolvedValue({ valid: true });
});

afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('完整推送任务页面向导', () => {
  it('新建使用五步页面向导，不需要预先加载已有配置', async () => {
    mount();
    await flush();
    expect(harness.options.layout).toBe('horizontal');
    expect(harness.options.steps).toHaveLength(5);
    expect(harness.options.steps[1].title).toBe('接收端配置');
    expect(getDataPushConfig).not.toHaveBeenCalled();
    state.handleClose();
    expect(close).toHaveBeenCalledOnce();
  });

  it('接收端和任务通过一次任务请求保存', async () => {
    mount();
    await flush();
    fillTask();
    harness.currentStep.value = 4;
    await state.handleSubmit();
    expect(createDataPushTask).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '客户推送',
        sourceDatabaseId: '9007199254740995',
        pushConfig: expect.objectContaining({
          config: expect.objectContaining({
            http: expect.objectContaining({
              url: 'https://consumer.example.com/push',
            }),
          }),
        }),
      }),
    );
    expect(createDataPushConfig).not.toHaveBeenCalled();
    expect(success).toHaveBeenCalledOnce();
  });

  it('编辑顺序载入任务和接收端，保持长 ID 及密钥掩码', async () => {
    const id = '9007199254740993';
    const configId = '9007199254740994';
    vi.mocked(getDataPushTask).mockResolvedValue({
      id,
      name: '客户推送',
      pushConfigId: configId,
    });
    vi.mocked(getDataPushConfig).mockResolvedValue({
      id: configId,
      channelType: 1,
      config: {
        http: {
          url: 'https://consumer.example.com/push',
          headers: [{ key: 'Authorization', value: '******' }],
        },
      },
    });
    mount({ id });
    await flush();
    expect(getDataPushTask).toHaveBeenCalledWith(id);
    expect(getDataPushConfig).toHaveBeenCalledWith(configId);
    harness.currentStep.value = 4;
    await state.handleSubmit();
    expect(updateDataPushTask).toHaveBeenCalledWith(
      expect.objectContaining({
        id,
        pushConfig: expect.objectContaining({
          id: configId,
          config: expect.objectContaining({
            http: expect.objectContaining({
              headers: [{ key: 'Authorization', value: '******' }],
            }),
          }),
        }),
      }),
    );
    expect(createDataPushTask).not.toHaveBeenCalled();
  });

  it('接收端详情失败退出向导，阻止空表单覆盖任务', async () => {
    vi.mocked(getDataPushTask).mockResolvedValue({
      id: '1',
      pushConfigId: '2',
    });
    vi.mocked(getDataPushConfig).mockRejectedValue(new Error('读取失败'));
    mount({ id: '1' });
    await flush();
    expect(close).toHaveBeenCalledOnce();
    harness.currentStep.value = 4;
    await state.handleSubmit();
    expect(updateDataPushTask).not.toHaveBeenCalled();
    expect(createDataPushTask).not.toHaveBeenCalled();
  });

  it('租户切换后不加载旧任务接收端，也不回填旧详情', async () => {
    const detail = deferred<any>();
    vi.mocked(getDataPushTask).mockReturnValue(detail.promise);
    mount({ id: '1' });
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    detail.resolve({ id: '1', pushConfigId: '2' });
    await flush();
    expect(close).toHaveBeenCalledOnce();
    expect(getDataPushConfig).not.toHaveBeenCalled();
    expect(harness.setValues).not.toHaveBeenCalled();
  });

  it('校验尚未结束就阻止重复提交，保存失败后允许重试', async () => {
    const validation = deferred<boolean>();
    harness.validateAll.mockReturnValueOnce(validation.promise);
    vi.mocked(createDataPushTask).mockRejectedValueOnce(new Error('保存失败'));
    mount();
    await flush();
    fillTask();
    harness.currentStep.value = 4;
    const first = state.handleSubmit();
    await state.handleSubmit();
    expect(harness.validateAll).toHaveBeenCalledOnce();
    validation.resolve(true);
    await expect(first).rejects.toThrow('保存失败');
    expect(success).not.toHaveBeenCalled();
    await state.handleSubmit();
    expect(createDataPushTask).toHaveBeenCalledTimes(2);
    expect(success).toHaveBeenCalledOnce();
  });

  it('测试连接只校验接收端，尚未填写数据来源也可测试', async () => {
    mount();
    await flush();
    Object.assign(harness.values, {
      name: '客户推送',
      httpUrl: 'https://consumer.example.com/push',
    });
    harness.currentStep.value = 1;
    await state.handleTestConnection();
    expect(testDataPushConfigConnection).toHaveBeenCalledOnce();
    expect(harness.validateAll).not.toHaveBeenCalled();
    expect(createDataPushTask).not.toHaveBeenCalled();
    expect(createDataPushConfig).not.toHaveBeenCalled();
  });

  it('接收端改变后忽略旧连接测试的成功结果', async () => {
    const connection = deferred<boolean>();
    vi.mocked(testDataPushConfigConnection).mockReturnValue(connection.promise);
    mount();
    await flush();
    fillTask();
    harness.currentStep.value = 1;
    const pending = state.handleTestConnection();
    await flush();
    harness.values.httpUrl = 'https://consumer.example.com/changed';
    connection.resolve(true);
    await pending;
    expect(message.success).not.toHaveBeenCalled();
  });

  it('预览提交未保存的完整接收端，卸载后忽略响应', async () => {
    const preview = deferred<any>();
    vi.mocked(previewDataPushTask).mockReturnValue(preview.promise);
    mount();
    await flush();
    fillTask();
    harness.currentStep.value = 2;
    const pending = state.handlePreview();
    await flush();
    expect(previewDataPushTask).toHaveBeenCalledWith(
      expect.objectContaining({ pushConfig: expect.any(Object), limit: 10 }),
    );
    app?.unmount();
    app = undefined;
    preview.resolve({ rowCount: 10 });
    await pending;
    expect(message.success).not.toHaveBeenCalled();
    expect(createDataPushTask).not.toHaveBeenCalled();
    expect(createDataPushConfig).not.toHaveBeenCalled();
  });
});
