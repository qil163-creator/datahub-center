/* eslint-disable vue/one-component-per-file -- 导入回归使用隔离控件模拟真实文件选择与移除事件。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { importMetaDict } from '#/api/governance/metaimport';

import ImportForm from './import-form.vue';

const state = vi.hoisted(() => ({
  options: undefined as undefined | {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => Promise<void>;
  },
  files: [] as File[],
  locked: false,
  success: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof state.options) => {
    state.options = options;
    return [defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }), {
      lock: () => { state.locked = true; },
      unlock: () => { state.locked = false; },
    }];
  },
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent(() => () => null) }));
vi.mock('@vben/utils', () => ({ downloadFileFromBlobPart: vi.fn() }));
vi.mock('#/api/dataforge/database', () => ({ getDatabaseSimpleList: async () => [{ id: '9007199254740993', name: '逻辑数据源' }] }));
vi.mock('#/api/dataforge/systemsource', () => ({ getSystemSourcePage: async () => ({ list: [], total: 0 }) }));
vi.mock('#/api/governance/metaimport', () => ({ downloadMetaImportTemplate: vi.fn(), importMetaDict: vi.fn() }));
vi.mock('antdv-next', () => {
  const plain = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.message?.()]) });
  return {
    Alert: plain,
    Button: plain,
    message: { warning: vi.fn(), success: vi.fn() },
    Select: defineComponent({
      props: { value: { type: [String, Number], default: undefined }, options: { type: Array as PropType<{ label: string; value: number | string }[]>, default: () => [] } },
      emits: ['update:value'],
      setup: (props, { emit }) => () => h('select', { value: props.value, onChange: (event: Event) => emit('update:value', (event.target as HTMLSelectElement).value) }, props.options?.map((option) => h('option', { value: option.value }, option.label))),
    }),
    UploadDragger: defineComponent({
      props: { beforeUpload: { type: Function, default: undefined }, disabled: Boolean },
      emits: ['change'],
      setup: (props, { emit }) => () => h('div', [
        ...state.files.map((file, index) => h('button', { disabled: props.disabled, onClick: () => {
          props.beforeUpload?.(file);
          const item = { uid: String(index), name: file.name, originFileObj: file };
          emit('change', { file: item, fileList: [item] });
        } }, `选择${index + 1}`)),
        h('button', { disabled: props.disabled, onClick: () => emit('change', { file: { status: 'removed' }, fileList: [] }) }, '移除文件'),
      ]),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
async function flush() { await nextTick(); await Promise.resolve(); await nextTick(); }
function click(label: string) {
  const button = [...host.querySelectorAll('button')].find((item) => item.textContent === label);
  expect(button).toBeDefined();
  button!.click();
}
beforeEach(async () => {
  vi.clearAllMocks();
  state.files = [new File(['a'], 'first.xlsx'), new File(['b'], 'second.xlsx')];
  state.locked = false;
  vi.mocked(importMetaDict).mockResolvedValue({ instanceId: 17, tableCount: 1 });
  host = document.createElement('div'); document.body.append(host);
  app = createApp(ImportForm, { onSuccess: state.success }); app.mount(host);
  await state.options!.onOpenChange(true); await flush();
  const select = host.querySelector('select')!;
  select.value = '9007199254740993'; select.dispatchEvent(new Event('change')); await flush();
});
afterEach(() => { app?.unmount(); host.remove(); });
describe('数据字典导入文件状态', () => {
  it('移除已选文件后不再提交该文件', async () => {
    click('选择1'); await flush();
    click('移除文件'); await flush();
    await state.options!.onConfirm();
    expect(importMetaDict).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('已选择：');
  });
  it('替换文件时清空旧结果并只提交当前文件', async () => {
    click('选择1'); await flush();
    await state.options!.onConfirm(); await flush();
    expect(host.textContent).toContain('实例 #17');
    click('选择2'); await flush();
    expect(host.textContent).not.toContain('实例 #17');
    await state.options!.onConfirm();
    expect(importMetaDict).toHaveBeenLastCalledWith('9007199254740993', state.files[1], undefined, undefined);
    expect(state.locked).toBe(false);
  });
  it('关闭重开后没有残留文件', async () => {
    click('选择1'); await flush();
    await state.options!.onOpenChange(false);
    await state.options!.onOpenChange(true); await flush();
    const select = host.querySelector('select')!; select.value = '9007199254740993'; select.dispatchEvent(new Event('change')); await flush();
    await state.options!.onConfirm();
    expect(importMetaDict).not.toHaveBeenCalled();
  });
});
