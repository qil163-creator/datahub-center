/* eslint-disable vue/one-component-per-file -- 使用页面替身验证作业列表工具栏。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/job';

import Job from './index.vue';

const harness = vi.hoisted(() => ({ options: undefined as any }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'a', refreshToken: 'b', accessCodes: [] }) }));
vi.mock('@vben/common-ui', () => ({ Page: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) }));
vi.mock('#/api/dataforge/job', () => ({
  getJobPage: vi.fn(), getJobCategories: vi.fn(async () => []), getJobOwners: vi.fn(async () => []),
  getJob: vi.fn(), createJobCategory: vi.fn(), updateJobCategory: vi.fn(), deleteJobCategory: vi.fn(),
  cloneJob: vi.fn(), deleteJob: vi.fn(), runJob: vi.fn(), stopJob: vi.fn(), updateJobStatus: vi.fn(), updateJobSchedule: vi.fn(),
}));
vi.mock('#/api/dataforge/ops', () => ({ getOpsInstanceStatistics: vi.fn(async () => ({ running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0 })) }));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.popupRender?.()]) });
  const Button = defineComponent({ setup: (_, { slots, attrs }) => () => h('button', attrs, slots.default?.()) });
  return { Alert: Container, Button, ConfigProvider: Container, Dropdown: Container, Input: Container, Select: Container, Switch: Container, Tree: Container, Modal: Object.assign(Container, { confirm: vi.fn() }), message: { info: vi.fn(), success: vi.fn() }, theme: { defaultAlgorithm: {} } };
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
vi.mock('#/components/cron-tab', () => ({ CronTab: defineComponent({ setup: () => () => null }) }));
vi.mock('../ops/modules/log-modal.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('../ops/modules/reference-icon.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('../ops/modules/status-badge.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('./modules/job-form.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));

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
});
