/* eslint-disable vue/one-component-per-file -- 表单测试隔离基础控件与异步接口。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDoc } from '#/api/governance/doc';
import {
  getStandardElement,
  updateStandardElement,
} from '#/api/governance/standardelement';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (open: boolean) => Promise<void>;
      },
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
  values: {} as Record<string, unknown>,
  locked: false,
  disabled: true,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      {
        getData: () => ({ id: 12 }),
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        setState: (state: { confirmDisabled?: boolean }) => {
          if (state.confirmDisabled !== undefined)
            harness.disabled = state.confirmDisabled;
        },
        close: () => options?.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessCodes: ['governance:standard-element:update'],
    accessToken: 'session-1',
    refreshToken: '',
  });
  return { useAccessStore: () => harness.access };
});
vi.mock('@vben/constants', () => ({ CommonStatusEnum: { ENABLE: 0 } }));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    defineComponent({ setup: () => () => h('div') }),
    {
      validate: async () => ({ valid: true }),
      getValues: async () => ({ ...harness.values }),
      setValues: async (values: object) => {
        Object.assign(harness.values, values);
      },
      resetForm: async () => {
        harness.values = {};
      },
      setFieldValue: vi.fn(),
    },
  ],
}));
vi.mock('../data', () => ({ useFormSchema: () => [] }));
vi.mock('#/locales', () => ({ $t: (value: string) => value }));
vi.mock('#/api/governance/doc', () => ({ getDoc: vi.fn() }));
vi.mock('#/api/governance/standardelement', () => ({
  getStandardElement: vi.fn(),
  createStandardElement: vi.fn(),
  updateStandardElement: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  message: { success: vi.fn() },
}));
let app: App | undefined;
let host: HTMLDivElement;
const detail = {
  id: 12,
  code: 'stationCode',
  name: '站点编码',
  engName: 'stationCode',
  categoryId: 1,
  columnType: 'VARCHAR',
  description: '业务定义',
  remark: '',
  documentId: '9007199254740993',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  Object.assign(harness.access!, {
    accessToken: 'session-1',
    accessCodes: ['governance:standard-element:update'],
    refreshToken: '',
  });
  harness.values = {};
  harness.locked = false;
  vi.mocked(getStandardElement).mockResolvedValue(detail);
  vi.mocked(getDoc).mockResolvedValue({
    id: '9007199254740993',
    stdType: 'NATIONAL',
  } as Awaited<ReturnType<typeof getDoc>>);
  vi.mocked(updateStandardElement).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Form);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('标准登记Long桥接', () => {
  it('负责人、类目与revision保持原字符串且保留新定义字段和历史扩展属性', async () => {
    vi.mocked(getStandardElement).mockResolvedValueOnce({
      ...detail,
      categoryId: '9007199254740995',
      personCharge: '9007199254740997',
      revision: '9007199254740999',
      standardType: 'NATIONAL',
      contactNumber: '联系电话',
      valueRange: '[0, 100]',
    });
    await harness.options!.onOpenChange(true);
    await harness.options!.onConfirm();
    expect(updateStandardElement).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryId: '9007199254740995',
        personCharge: '9007199254740997',
        revision: '9007199254740999',
        standardType: 'NATIONAL',
        contactNumber: '联系电话',
        valueRange: '[0, 100]',
      }),
    );
  });
  it('登记详情查询、回显和保存全程保留原十进制字符串', async () => {
    await harness.options!.onOpenChange(true);
    expect(getDoc).toHaveBeenCalledExactlyOnceWith('9007199254740993');
    expect(harness.values.documentId).toBe('9007199254740993');
    expect(harness.disabled).toBe(false);
    await harness.options!.onConfirm();
    expect(updateStandardElement).toHaveBeenCalledWith(
      expect.objectContaining({ id: 12, documentId: '9007199254740993' }),
    );
    expect(
      JSON.stringify(vi.mocked(updateStandardElement).mock.calls[0]?.[0]),
    ).toContain('"9007199254740993"');
    expect(harness.locked).toBe(false);
  });
  it('登记详情不可用时保留原documentId', async () => {
    vi.mocked(getDoc).mockRejectedValueOnce(new Error('登记只读权限不可用'));
    await harness.options!.onOpenChange(true);
    await harness.options!.onConfirm();
    expect(updateStandardElement).toHaveBeenCalledWith(
      expect.objectContaining({ documentId: '9007199254740993' }),
    );
  });
  it.each(['close', 'session', 'permission', 'unmount'] as const)(
    '%s后不回填迟到数据元详情',
    async (action) => {
      const pending = deferred<typeof detail>();
      vi.mocked(getStandardElement).mockReturnValueOnce(pending.promise);
      const opening = harness.options!.onOpenChange(true);
      if (action === 'close') await harness.options!.onOpenChange(false);
      if (action === 'session') {
        harness.access!.refreshToken = 'session-2';
        await nextTick();
      }
      if (action === 'permission') {
        harness.access!.accessCodes = [];
        await nextTick();
      }
      if (action === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(detail);
      await opening;
      await harness.options!.onConfirm();
      expect(harness.values.documentId).toBeUndefined();
      expect(updateStandardElement).not.toHaveBeenCalled();
    },
  );
});
