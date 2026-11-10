/* eslint-disable vue/one-component-per-file -- 隔离基础控件以验证映射表单的身份与异步边界。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getStandardCodeItemPage } from '#/api/governance/standardcodeitem';
import {
  createCodeMapping,
  getCodeMapping,
  updateCodeMapping,
} from '#/api/governance/standardcodeset/mapping';

import MappingForm from './mapping-form.vue';

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
  data: {} as { codeSetId: string; id?: string; readonly?: boolean },
  model: undefined as
    | undefined
    | { codeItemId?: string; originalValue: string },
  disabled: true,
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessToken: 'one',
    refreshToken: '',
    accessCodes: ['governance:standard-code-set:update'],
  });
  return { useAccessStore: () => harness.access };
});
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
        getData: () => harness.data,
        lock: vi.fn(),
        unlock: vi.fn(),
        close: () => options?.onOpenChange(false),
        setState: (state: { confirmDisabled?: boolean }) => {
          if (state.confirmDisabled !== undefined)
            harness.disabled = state.confirmDisabled;
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: Container,
    FormItem: Container,
    Form: defineComponent({
      props: {
        model: {
          type: Object as PropType<NonNullable<typeof harness.model>>,
          required: true,
        },
      },
      setup: (props, { slots, expose }) => {
        harness.model = props.model;
        expose({ validate: async () => undefined });
        return () => h('div', slots.default?.());
      },
    }),
    Input: defineComponent({
      props: { value: { type: [String, Number], default: undefined } },
      setup: (props) => () => h('input', { value: props.value }),
    }),
    Select: defineComponent({
      props: {
        options: {
          type: Array as PropType<{ label: string; value: string }[]>,
          default: () => [],
        },
      },
      setup: (props) => () =>
        h(
          'select',
          props.options?.map((item: { label: string; value: string }) =>
            h('option', { value: item.value }, item.label),
          ),
        ),
    }),
    message: { success: vi.fn() },
  };
});
vi.mock('@vben/utils', () => ({
  formatDate: String,
}));
vi.mock('#/api/governance/standardcodeitem', () => ({
  getStandardCodeItemPage: vi.fn(),
}));
vi.mock('#/api/governance/standardcodeset/mapping', () => ({
  createCodeMapping: vi.fn(),
  getCodeMapping: vi.fn(),
  updateCodeMapping: vi.fn(),
}));

let app: App;
let host: HTMLDivElement;
const codeSetId = '9007199254740993';
const detail = {
  id: '9007199254740995',
  codeSetId,
  originalValue: '原值',
  codeItemId: '9007199254740997',
  codeName: '同名',
  codeValue: 'A',
  revision: '9007199254740999',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(harness.access!, { accessToken: 'one', refreshToken: '' });
  harness.data = { id: detail.id, codeSetId };
  harness.disabled = true;
  vi.mocked(getCodeMapping).mockResolvedValue(detail);
  vi.mocked(getStandardCodeItemPage).mockResolvedValue({
    list: [
      { id: detail.codeItemId, codeSetId, dictType: 'fixture', label: '同名', value: 'A' },
      { id: '9007199254741001', codeSetId, dictType: 'fixture', label: '同名', value: 'B' },
    ],
    total: 2,
  });
  vi.mocked(updateCodeMapping).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(MappingForm);
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('代码映射表单边界', () => {
  it('相同代码名通过无损码值ID选择并提交当前修订号', async () => {
    await harness.options!.onOpenChange(true);
    harness.model!.codeItemId = '9007199254741001';
    await nextTick();
    expect(
      [...host.querySelectorAll('input')].some((input) => input.value === 'B'),
    ).toBe(true);
    await harness.options!.onConfirm();
    expect(updateCodeMapping).toHaveBeenCalledWith({
      id: detail.id,
      codeSetId,
      codeItemId: '9007199254741001',
      originalValue: '原值',
      revision: detail.revision,
    });
  });

  it('后打开对象的结果不会被前一次慢请求覆盖', async () => {
    const old = deferred<typeof detail>();
    vi.mocked(getCodeMapping).mockReturnValueOnce(old.promise);
    const pending = harness.options!.onOpenChange(true);
    harness.data = { id: '9007199254741003', codeSetId };
    vi.mocked(getCodeMapping).mockResolvedValueOnce({
      ...detail,
      id: harness.data.id!,
      originalValue: '新对象',
    });
    await harness.options!.onOpenChange(true);
    old.resolve(detail);
    await pending;
    expect(harness.model!.originalValue).toBe('新对象');
  });

  it('账号切换清空已加载值并阻止旧请求完成后提交', async () => {
    const old = deferred<typeof detail>();
    vi.mocked(getCodeMapping).mockReturnValueOnce(old.promise);
    const pending = harness.options!.onOpenChange(true);
    harness.access!.accessToken = 'two';
    await nextTick();
    old.resolve(detail);
    await pending;
    expect(harness.model!.originalValue).toBe('');
    await harness.options!.onConfirm();
    expect(updateCodeMapping).not.toHaveBeenCalled();
    expect(createCodeMapping).not.toHaveBeenCalled();
  });

  it('详情模式不加载可编辑码值且不能触发写入', async () => {
    harness.data.readonly = true;
    await harness.options!.onOpenChange(true);
    await harness.options!.onConfirm();
    expect(getStandardCodeItemPage).not.toHaveBeenCalled();
    expect(updateCodeMapping).not.toHaveBeenCalled();
  });

  it('加载全部码值分页供映射选择', async () => {
    vi.mocked(getStandardCodeItemPage)
      .mockResolvedValueOnce({
        list: [{ id: detail.codeItemId, codeSetId, dictType: 'fixture', label: '甲', value: 'A' }],
        total: 2,
      })
      .mockResolvedValueOnce({
        list: [{ id: '9007199254741001', codeSetId, dictType: 'fixture', label: '乙', value: 'B' }],
        total: 2,
      });
    await harness.options!.onOpenChange(true);
    expect(getStandardCodeItemPage).toHaveBeenNthCalledWith(2, {
      codeSetId,
      pageNo: 2,
      pageSize: 100,
    });
    expect([...host.querySelectorAll('option')].map((option) => option.textContent)).toEqual(['甲', '乙']);
  });
});
