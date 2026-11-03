import type { App } from 'vue';

import { createApp, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken: string },
  lifecycle: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (open: boolean) => Promise<void>;
      },
  data: { id: '1' },
  get: vi.fn(),
  save: vi.fn(),
  values: {} as Record<string, unknown>,
  setValues: vi.fn(),
  success: vi.fn(),
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof harness.lifecycle>) => {
    harness.lifecycle = options;
    return [
      () => null,
      {
        getData: () => harness.data,
        lock: vi.fn(),
        unlock: vi.fn(),
        close: harness.close,
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Table: () => null,
  Tag: () => null,
  message: { success: harness.success },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    {
      resetForm: vi.fn().mockResolvedValue(undefined),
      setValues: harness.setValues,
      validate: vi.fn().mockResolvedValue({ valid: true }),
      getValues: () => Promise.resolve({ ...harness.values }),
    },
  ],
}));
vi.mock('#/api/modeling/dwlayer', () => ({
  getDwLayer: (...args: unknown[]) => harness.get(...args),
  createDwLayer: (...args: unknown[]) => harness.save(...args),
  updateDwLayer: (...args: unknown[]) => harness.save(...args),
  getDwLayerPrefixPreview: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (text: string) => text }));
vi.mock('../data', () => ({
  DW_LAYER_NODE_TYPE: { CATEGORY: 'CATEGORY', LAYER: 'LAYER' },
  getCategoryCreateDefaults: () => ({}),
  useFormSchema: () => [],
}));

let app: App | undefined;
function deferred() {
  let resolve!: (value: Record<string, unknown>) => void;
  const promise = new Promise<Record<string, unknown>>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await nextTick();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'session-a', refreshToken: '' });
  harness.data = { id: '1' };
  harness.values = {};
  harness.setValues.mockImplementation(
    async (values: Record<string, unknown>) => {
      harness.values = { ...values };
    },
  );
  app = createApp(Form);
  app.mount(document.createElement('div'));
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('数仓分层编辑弹窗请求隔离', () => {
  it('关闭后打开其他分层，先前详情不能回填新弹窗', async () => {
    const previous = deferred();
    harness.get
      .mockReturnValueOnce(previous.promise)
      .mockResolvedValueOnce({ id: '2', layerName: '新分层' });
    const oldOpen = harness.lifecycle!.onOpenChange(true);
    await flush();
    await harness.lifecycle!.onOpenChange(false);
    harness.data = { id: '2' };
    await harness.lifecycle!.onOpenChange(true);
    previous.resolve({ id: '1', layerName: '旧分层' });
    await oldOpen;
    expect(harness.values).toEqual({ id: '2', layerName: '新分层' });
    expect(harness.setValues).toHaveBeenCalledTimes(1);
  });

  it('保存过程中切换会话再返回，旧保存结果不关闭新会话弹窗或显示成功', async () => {
    harness.get.mockResolvedValue({
      id: '1',
      nodeType: 'LAYER',
      layerName: '分层',
    });
    await harness.lifecycle!.onOpenChange(true);
    const saving = deferred();
    harness.save.mockReturnValue(saving.promise);
    const save = harness.lifecycle!.onConfirm();
    await flush();
    harness.access.accessToken = 'session-b';
    harness.access.accessToken = 'session-a';
    saving.resolve({});
    await save;
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.close).not.toHaveBeenCalled();
  });
});
