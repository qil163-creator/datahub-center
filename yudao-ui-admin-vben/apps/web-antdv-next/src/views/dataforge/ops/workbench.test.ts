/* eslint-disable vue/one-component-per-file -- 隔离页面替身用于验证路由缓存切换后的筛选一致性。 */
import type { App } from 'vue';

import type { OpsInstanceApi } from '#/api/dataforge/ops';

import { createApp, defineComponent, h, KeepAlive, nextTick, ref } from 'vue';

import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/ops';

import Workbench from './workbench.vue';

vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/utils', () => ({ downloadFileFromBlobPart: vi.fn(), formatDate: () => '2026-10-22' }));
vi.mock('#/api/dataforge/ops', () => ({
  getOpsInstancePage: vi.fn(async () => ({ list: [], total: 0 })),
  getOpsInstanceStatistics: vi.fn(async () => ({ running: 0, todayFailed: 0, todayExecuted: 0, todaySuccessRate: 0, statisticTime: '2026-10-22 19:11:00', importedSnapshot: true })),
  getOpsInstanceCategories: vi.fn(async () => [{ id: 'source:2', parentId: '0', name: '测试类目' }]),
  getOpsInstance: vi.fn(), getOpsInstanceLog: vi.fn(), downloadOpsInstanceLog: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  return { Alert: Container, Button: Container, ConfigProvider: Container, DateRangePicker: Container, Dropdown: Container, Input: Container, Pagination: Container, Select: Container, Spin: Container, Tooltip: Container, Modal: Container, Switch: Container, TextArea: Container,
    theme: { defaultAlgorithm: {} },
    Tree: defineComponent({ emits: ['select'], setup: (_props, { emit }) => () => h('button', { class: 'select-category', onClick: () => emit('select', ['source:2']) }, '选择类目') }),
  };
});

let app: App;
let host: HTMLDivElement;
const active = ref(true);
const kind = ref<OpsInstanceApi.Kind>('INTEGRATION');
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick(); }
beforeEach(() => {
  vi.clearAllMocks(); active.value = true; kind.value = 'INTEGRATION';
  vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div'); document.body.append(host);
  app = createApp(defineComponent({ setup: () => () => h(KeepAlive, () => active.value ? h(Workbench, { kind: kind.value }) : null) }));
  app.use(createPinia()); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });

describe('实例页面缓存筛选一致性', () => {
  it('真实周期类型SCHEDULE在列表显示为定时调度', async () => {
    await flush();
    const scheduled: OpsInstanceApi.Instance = {
      id: '9007199254740993', kind: 'JOB', origin: 'PLATFORM',
      taskId: '9007199254740995', taskName: '周期作业', instanceName: '周期作业实例',
      executionType: 'SCHEDULE', status: 'SUCCESS', queueState: 'DONE',
      scheduledTime: '2026-11-02 08:42:00',
    };
    vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [scheduled], total: 1 });
    kind.value = 'JOB';
    await flush();
    expect(host.querySelector('.qo-execution-type')?.textContent).toBe('定时调度');
  });
  it.each([['DEVELOPMENT', 'PLATFORM'], ['INTEGRATION', 'MANAGED']] as const)('%s作业子实例按%s来源查询真实日志', async (childKind, childOrigin) => {
    await flush();
    const child: OpsInstanceApi.Instance = {
      id: '9007199254740993', kind: childKind, origin: childOrigin,
      taskId: '9007199254740995', taskName: '每日水位统计', instanceName: '水位统计实例',
      executionType: 'START', status: 'SUCCESS',
    };
    vi.mocked(api.getOpsInstancePage).mockResolvedValue({ list: [{
      ...child, id: '9007199254740997', kind: 'JOB', origin: 'PLATFORM', taskName: '河道作业',
      instanceName: '河道作业实例', children: [child],
    }], total: 1 });
    vi.mocked(api.getOpsInstance).mockResolvedValue(child);
    vi.mocked(api.getOpsInstanceLog).mockResolvedValue({ message: '任务完成', available: true, importedSnapshot: false });
    kind.value = 'JOB';
    await flush();
    expect(host.querySelector('.qo-child-row')).toBeNull();
    host.querySelector<HTMLButtonElement>('.qo-row-toggle')?.click();
    await flush();
    expect(host.querySelector('.qo-child-row')?.textContent).toContain('水位统计实例');
    host.querySelector<HTMLButtonElement>('.qo-child-row .qo-actions > div')?.click();
    await flush();
    expect(api.getOpsInstanceLog).toHaveBeenCalledWith({ id: child.id, kind: child.kind, origin: child.origin });
  });
  it('类目选择同时影响列表及统计，切页返回不保留不可见类目筛选', async () => {
    await flush();
    host.querySelector<HTMLButtonElement>('.select-category')?.click(); await flush();
    expect(api.getOpsInstancePage).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 'source:2' }));
    expect(api.getOpsInstanceStatistics).toHaveBeenLastCalledWith({ kind: 'INTEGRATION', categoryId: 'source:2', taskType: undefined });
    active.value = false; await flush(); active.value = true; await flush();
    expect(api.getOpsInstancePage).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: undefined }));
  });
});
