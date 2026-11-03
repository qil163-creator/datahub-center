/* eslint-disable vue/one-component-per-file -- 隔离基础控件，验证真实关联表单的远程分页和会话边界。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getStandardCodeSet, getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { bindStandardElementCodeSet } from '#/api/governance/standardelement';

import RelateForm from './relate-form.vue';

interface SelectProps {
  options: { label: string; value: number | string }[];
  loading: boolean;
  onSearch: (value: string) => void;
  onChange: (value: string) => void;
  popupRender: (menu: ReturnType<typeof h>) => ReturnType<typeof h>;
}
const state = vi.hoisted(() => ({
  options: undefined as undefined | {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => Promise<void>;
  },
  props: undefined as (() => SelectProps) | undefined,
  values: {} as Record<string, unknown>,
  data: {} as { codeSetId?: string; id: string; },
  access: {} as { accessToken: string; refreshToken: string },
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof state.options) => {
    state.options = options;
    return [defineComponent({ setup: (_, { slots }) => () => h('section', slots.default?.()) }), {
      lock: vi.fn(), unlock: vi.fn(), getData: () => state.data,
      close: () => { state.close(); return options!.onOpenChange(false); },
    }];
  },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: { schema: { componentProps: () => SelectProps }[] }) => {
    state.props = options.schema[0]!.componentProps;
    return [defineComponent({ setup: () => () => {
      const props = state.props!();
      return h('div', [
        h('input', { 'aria-label': '码表搜索', onInput: (event: Event) => props.onSearch((event.target as HTMLInputElement).value) }),
        h('span', { 'data-loading': '' }, String(props.loading)),
        h('select', { value: state.values.codeSetId, onChange: (event: Event) => {
          const value = (event.target as HTMLSelectElement).value;
          state.values.codeSetId = value; props.onChange(value);
        } }, props.options.map((option) => h('option', { value: option.value }, option.label))),
        props.popupRender(h('div', '选项列表')),
      ]);
    } }), {
      validate: async () => ({ valid: state.values.codeSetId !== undefined }),
      getValues: async () => ({ ...state.values }),
      setValues: async (values: object) => { Object.assign(state.values, values); },
      resetForm: async () => { state.values = {}; },
    }];
  },
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({ setup: (_, { attrs, slots }) => () => h('div', [attrs.message as string, slots.default?.(), slots.action?.()]) });
  return {
    Alert: plain,
    Button: defineComponent({ setup: (_, { attrs, slots }) => () => h('button', attrs, slots.default?.()) }),
    Pagination: defineComponent({
      props: { current: { type: Number, default: 1 }, total: { type: Number, default: 0 } }, emits: ['change'],
      setup: (props, { emit }) => () => h('div', [h('span', `第${props.current}页/共${props.total}条`), h('button', { onClick: () => emit('change', 12) }, '第12页')]),
    }),
    message: { success: vi.fn() },
  };
});
vi.mock('#/locales', () => ({ $t: (value: string) => value }));
vi.mock('#/api/governance/standardcodeset', () => ({ getStandardCodeSet: vi.fn(), getStandardCodeSetPage: vi.fn() }));
vi.mock('#/api/governance/standardelement', () => ({ bindStandardElementCodeSet: vi.fn() }));
let app: App;
let host: HTMLDivElement;
const selectedId = '2147483647';
async function flush() { for (let i = 0; i < 4; i++) { await Promise.resolve(); await nextTick(); } }
function button(text: string) { return [...host.querySelectorAll('button')].find((item) => item.textContent === text)!; }
function search(value: string) { const input = host.querySelector('input')!; input.value = value; input.dispatchEvent(new Event('input')); }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'session-one', refreshToken: '' });
  state.data = { id: '2147483646' }; state.values = {};
  vi.mocked(getStandardCodeSetPage).mockResolvedValue({ list: [{ id: '1', name: '第一页码表' }], total: 240 });
  vi.mocked(bindStandardElementCodeSet).mockResolvedValue(true);
  host = document.createElement('div'); document.body.append(host);
  app = createApp(RelateForm); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });
describe('数据元关联码表远程选择', () => {
  it('超过200项仍可翻到后续页并无损提交所选ID', async () => {
    await state.options!.onOpenChange(true); await flush();
    expect(getStandardCodeSetPage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20, name: undefined });
    vi.mocked(getStandardCodeSetPage).mockResolvedValueOnce({ list: [{ id: selectedId, name: '第221个码表' }], total: 240 });
    button('第12页').click(); await flush();
    expect(getStandardCodeSetPage).toHaveBeenLastCalledWith({ pageNo: 12, pageSize: 20, name: undefined });
    const select = host.querySelector('select')!; select.value = selectedId; select.dispatchEvent(new Event('change')); await flush();
    await state.options!.onConfirm();
    expect(bindStandardElementCodeSet).toHaveBeenCalledWith('2147483646', selectedId);
  });
  it('搜索回第一页且旧搜索不能覆盖新选项或提前清loading', async () => {
    await state.options!.onOpenChange(true); await flush();
    const old = deferred<Awaited<ReturnType<typeof getStandardCodeSetPage>>>();
    const current = deferred<Awaited<ReturnType<typeof getStandardCodeSetPage>>>();
    vi.mocked(getStandardCodeSetPage).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    search('旧'); search(' 当前 '); await flush();
    expect(getStandardCodeSetPage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20, name: '当前' });
    old.resolve({ list: [{ id: '2', name: '迟到旧码表' }], total: 99 }); await flush();
    expect(host.textContent).not.toContain('迟到旧码表');
    expect(host.querySelector('[data-loading]')!.textContent).toBe('true');
    current.resolve({ list: [{ id: selectedId, name: '当前码表' }], total: 1 }); await flush();
    expect(host.textContent).toContain('当前码表');
    expect(host.querySelector('[data-loading]')!.textContent).toBe('false');
  });
  it('当前关联不在第一页时独立回显，翻页和搜索都不清空原值', async () => {
    state.data.codeSetId = selectedId;
    vi.mocked(getStandardCodeSet).mockResolvedValue({ id: selectedId, name: '已有绑定码表' });
    await state.options!.onOpenChange(true); await flush();
    expect(getStandardCodeSet).toHaveBeenCalledWith(selectedId);
    expect(host.textContent).toContain('已有绑定码表');
    search('无匹配'); await flush();
    expect(state.values.codeSetId).toBe(selectedId);
    expect(host.textContent).toContain('已有绑定码表');
    button('第12页').click(); await flush();
    expect(state.values.codeSetId).toBe(selectedId);
    expect(host.textContent).toContain('已有绑定码表');
  });
  it('绑定详情晚到不能覆盖用户重新选择的码表', async () => {
    state.data.codeSetId = selectedId;
    const pending = deferred<Awaited<ReturnType<typeof getStandardCodeSet>>>();
    vi.mocked(getStandardCodeSet).mockReturnValueOnce(pending.promise);
    const opening = state.options!.onOpenChange(true); await flush();
    const select = host.querySelector('select')!; select.value = '1'; select.dispatchEvent(new Event('change')); await flush();
    pending.resolve({ id: selectedId, name: '迟到原绑定' }); await opening; await flush();
    expect(state.values.codeSetId).toBe('1');
    expect(host.textContent).not.toContain('迟到原绑定');
    search('新搜索'); await flush();
    expect(host.textContent).toContain('第一页码表');
  });
  it('请求失败可重试，关闭后迟到结果不回填下一次表单', async () => {
    vi.mocked(getStandardCodeSetPage).mockRejectedValueOnce(new Error('读取失败'));
    await state.options!.onOpenChange(true); await flush();
    expect(host.textContent).toContain('标准码表加载失败');
    button('重试').click(); await flush();
    expect(host.textContent).not.toContain('标准码表加载失败');
    const pending = deferred<Awaited<ReturnType<typeof getStandardCodeSetPage>>>();
    vi.mocked(getStandardCodeSetPage).mockReturnValueOnce(pending.promise);
    search('关闭前'); await state.options!.onOpenChange(false);
    pending.resolve({ list: [{ id: '9', name: '旧弹窗码表' }], total: 1 }); await flush();
    expect(host.textContent).not.toContain('旧弹窗码表');
    expect(state.values.codeSetId).toBeUndefined();
  });
});
