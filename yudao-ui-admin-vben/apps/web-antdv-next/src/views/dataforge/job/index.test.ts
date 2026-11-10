/* eslint-disable vue/one-component-per-file -- 使用页面替身验证作业列表工具栏。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { Modal } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/job';

import Job from './index.vue';

const harness = vi.hoisted(() => ({ options: undefined as any, push: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'a', refreshToken: 'b', accessCodes: [] }) }));
vi.mock('@vben/common-ui', () => ({ Page: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent({ props: { icon: { type: String, required: true } }, setup: (props) => () => h('svg', { 'data-icon': props.icon }) }) }));
vi.mock('#/api/dataforge/job', () => ({
  getJobPage: vi.fn(), getJobCategories: vi.fn(async () => []), getJobOwners: vi.fn(async () => []),
  getJob: vi.fn(), createJobCategory: vi.fn(), updateJobCategory: vi.fn(), deleteJobCategory: vi.fn(),
  cloneJob: vi.fn(), deleteJob: vi.fn(), runJob: vi.fn(), stopJob: vi.fn(), updateJobStatus: vi.fn(), updateJobSchedule: vi.fn(),
}));
vi.mock('#/api/dataforge/ops', () => ({ getOpsInstanceStatistics: vi.fn(async () => ({ running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0 })) }));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.popupRender?.()]) });
  const Button = defineComponent({ setup: (_, { slots, attrs }) => () => h('button', attrs, slots.default?.()) });
  const ModalStub = defineComponent({ props: { open: Boolean, title: { type: String, default: '' } }, emits: ['ok'], setup: (props, { slots, emit }) => () => props.open ? h('section', { 'data-modal': props.title }, [slots.default?.(), h('button', { 'data-confirm': props.title, onClick: () => emit('ok') }, '保存')]) : null });
  return { Alert: Container, Button, ConfigProvider: Container, Dropdown: Container, Input: Container, Select: Container, Switch: Container, Tree: Container, Modal: Object.assign(ModalStub, { confirm: vi.fn() }), message: { info: vi.fn(), success: vi.fn() }, theme: { defaultAlgorithm: {} } };
});
vi.mock('#/adapter/vxe-table', () => ({ NativeVxeButton: ref(defineComponent({ setup: (_, { attrs }) => () => h('button', attrs) })) }));
vi.mock('#/adapter/business-vxe-table', () => ({ useVbenVxeGrid: (options: any) => {
  harness.options = options.gridOptions;
  const Grid = defineComponent({ props: { tableData: { type: Array, default: () => [] }, tableTitle: { type: String, default: '' } }, setup: (props, { slots }) => () => h('div', { class: 'grid-stub' }, [h('h2', props.tableTitle), slots['toolbar-tools']?.(), ...props.tableData.map((row) => h('div', [slots.info?.({ row }), slots.actions?.({ row })])), slots.pager?.()]) });
  return [Grid, { setLoading: vi.fn() }];
} }));
vi.mock('#/components/list-pagination/index.vue', () => ({ default: defineComponent({ emits: ['change'], setup: (_, { emit }) => () => h('button', { 'data-page': 'next', onClick: () => emit('change', 2, 10) }) }) }));
vi.mock('#/components/sidebar-collapse/index.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('#/components/tree-toolbar/index.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('#/components/cron-tab', () => ({ CronTab: defineComponent({ props: { modelValue: { type: String, default: '' } }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { 'data-cron': '', value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }) }));
vi.mock('../ops/modules/log-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('../ops/modules/reference-icon.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('../ops/modules/status-badge.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/job-form.vue', () => ({ default: defineComponent({ props: { open: Boolean, jobId: { type: [String, Number], default: undefined } }, setup: (props) => () => h('div', { 'data-job-form': '', 'data-open': String(props.open), 'data-job-id': String(props.jobId ?? '') }) }) }));

let app: App;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getJobPage).mockResolvedValue({ list: [{ id: '9007199254740993', name: '测试作业', strategy: 'PARALLEL', status: 0, scheduleStatus: 0 } as any], total: 11 });
});
afterEach(() => { app?.unmount(); host.remove(); });

describe('作业列表工具栏', () => {
  it('为更多菜单的每个可用操作显示对应图标', async () => {
    vi.mocked(api.getJobPage).mockResolvedValue({ list: [{ id: '1', name: '测试作业', strategy: 'PARALLEL', status: 0, scheduleStatus: 0, hasActiveRuns: true, latestRecord: { id: '2' } } as any], total: 1 });
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const actions = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')];
    expect(actions.map((button) => [button.textContent?.trim(), button.querySelector('svg')?.dataset.icon])).toEqual([
      ['修改', 'lucide:pencil'],
      ['复制', 'lucide:copy'],
      ['调度周期', 'lucide:calendar-clock'],
      ['运行实例', 'lucide:history'],
      ['查看日志', 'lucide:file-text'],
      ['执行一次', 'lucide:play'],
      ['停止运行', 'lucide:square'],
      ['删除', 'lucide:trash-2'],
    ]);
    const more = [...host.querySelectorAll<HTMLButtonElement>('.qj-actions button')].find((button) => button.textContent?.trim() === '更多');
    expect(more?.querySelector('svg')?.getAttribute('data-icon')).toBe('lucide:ellipsis-vertical');
  });

  it('使用真实网格配置列设置并刷新当前分页，保留搜索显隐', async () => {
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    expect(host.textContent).toContain('作业列表');
    expect(host.textContent).toContain('测试作业');
    expect(harness.options.toolbarConfig).toMatchObject({ custom: true, refresh: true, zoom: false });
    expect(harness.options.columns.map((column: { field: string }) => column.field)).toContain('actions');
    const idColumn = harness.options.columns[0];
    expect(idColumn.sortType).toBe('string');
    expect(idColumn.sortBy({ row: { id: '9007199254740992' } }) < idColumn.sortBy({ row: { id: '9007199254740993' } })).toBe(true);
    host.querySelector<HTMLButtonElement>('[data-page="next"]')!.click();
    await nextTick();
    await harness.options.toolbarConfig.refreshOptions.queryMethod();
    expect(vi.mocked(api.getJobPage).mock.lastCall?.[0]).toMatchObject({ pageNo: 2, pageSize: 10 });
    const toggle = host.querySelector<HTMLButtonElement>('[aria-label="显示或隐藏搜索"]')!;
    expect(host.querySelector('.qj-filters')).not.toBeNull();
    toggle.click();
    await nextTick();
    expect(host.querySelector('.qj-filters')).toBeNull();
  });

  it('点击修改时打开对应作业的编辑表单', async () => {
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const edit = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')].find((button) => button.textContent?.trim() === '修改');
    edit!.click();
    await nextTick();
    const form = host.querySelector('[data-job-form]');
    expect(form?.getAttribute('data-open')).toBe('true');
    expect(form?.getAttribute('data-job-id')).toBe('9007199254740993');
  });

  it('点击复制时读取最新作业并按原始编号复制', async () => {
    vi.mocked(api.getJob).mockResolvedValue({ id: '9007199254740993', revision: 7 } as any);
    vi.mocked(api.cloneJob).mockResolvedValue('10');
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const clone = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')].find((button) => button.textContent?.trim() === '复制');
    clone!.click();
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    expect(api.getJob).toHaveBeenCalledWith('9007199254740993');
    expect(api.cloneJob).toHaveBeenCalledWith('9007199254740993');
  });

  it('点击调度周期后读取最新配置，保存输入的周期', async () => {
    vi.mocked(api.getJob).mockResolvedValue({ id: '9007199254740993', revision: 7, cronExpression: '0 0 2 * * ?', scheduleStatus: 0 } as any);
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const cronButton = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')].find((button) => button.textContent?.trim() === '调度周期');
    cronButton!.click();
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    expect(api.getJob).toHaveBeenCalledWith('9007199254740993');
    const input = host.querySelector<HTMLInputElement>('[data-modal="调度周期"] [data-cron]')!;
    expect(input.value).toBe('0 0 2 * * ?');
    input.value = '0 0 3 * * ?';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    host.querySelector<HTMLButtonElement>('[data-confirm="调度周期"]')!.click();
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    expect(api.updateJobSchedule).toHaveBeenCalledWith({ id: '9007199254740993', revision: 7, cronExpression: '0 0 3 * * ?', scheduleStatus: 0 });
    expect(host.querySelector('[data-modal="调度周期"]')).toBeNull();
  });

  it('运行实例携带当前作业编号跳转', async () => {
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const instances = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')].find((button) => button.textContent?.trim() === '运行实例');
    instances!.click();
    expect(harness.push).toHaveBeenCalledWith({ path: '/develop/operations/job', query: { jobId: '9007199254740993' } });
  });

  it.each([
    ['执行一次', '确认执行一次？', 'run'],
    ['删除', '确认删除作业？', 'delete'],
  ] as const)('点击%s需确认后使用最新版本执行', async (label, title, operation) => {
    vi.mocked(api.getJob).mockResolvedValue({ id: '9007199254740993', revision: 7 } as any);
    app = createApp(Job);
    app.mount(host);
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); }
    const button = [...host.querySelectorAll<HTMLButtonElement>('.qj-popup > button')].find((item) => item.textContent?.trim() === label);
    button!.click();
    expect(Modal.confirm).toHaveBeenCalledWith(expect.objectContaining({ title }));
    expect(api.getJob).not.toHaveBeenCalled();
    const confirmation = vi.mocked(Modal.confirm).mock.lastCall![0] as { onOk: () => Promise<void> };
    await confirmation.onOk();
    expect(api.getJob).toHaveBeenCalledWith('9007199254740993');
    if (operation === 'run') {
      expect(api.runJob).toHaveBeenCalledWith({ id: '9007199254740993', revision: 7, requestId: expect.any(String) });
    } else {
      expect(api.deleteJob).toHaveBeenCalledWith('9007199254740993', 7);
    }
  });
});
