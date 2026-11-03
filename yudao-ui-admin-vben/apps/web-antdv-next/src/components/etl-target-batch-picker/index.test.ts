import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as records from '#/api/dataforge/etl';
import * as targets from '#/api/dataforge/etl/target-batch';

import Picker from './index.vue';
const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '', allowed: true },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.access.allowed }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('#/api/dataforge/etl', () => ({ getEtlRecordPage: vi.fn() }));
vi.mock('#/api/dataforge/etl/target-batch', () => ({
  getEtlTargetBatches: vi.fn(),
  getEtlTargetRows: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  return {
    Button: slot('button'),
    Input: slot('input'),
    Alert: defineComponent({
      props: ['message'],
      setup: (p) => () => h('p', p.message),
    }),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (p, { slots }) =>
        () =>
          p.open ? h('section', slots.default?.()) : null,
    }),
    Table: defineComponent({
      props: ['dataSource', 'columns'],
      setup:
        (p, { slots }) =>
        () =>
          h(
            'div',
            p.dataSource.flatMap((record: object) =>
              p.columns.map((column: object) =>
                slots.bodyCell?.({ record, column }),
              ),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const selected = vi.fn();
const batch = {
  id: '9007199254740995',
  recordId: '9007199254740993',
  commitStatus: 'COMMITTED',
  qualityStatus: 'PASSED',
};
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (e) => e.textContent?.trim() === label,
  );
}
async function click(label: string) {
  expect(button(label)).toBeDefined();
  button(label)!.click();
  await flush();
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Picker, { onSelect: selected });
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    accessToken: '1',
    refreshToken: '',
    allowed: true,
  });
  vi.mocked(records.getEtlRecordPage).mockResolvedValue({
    list: [{ id: '9007199254740993', name: '销售清洗' }],
    total: 1,
  } as any);
  vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([batch] as any);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
describe('跨域目标批次选择器', () => {
  it('先搜索成功且未归档运行，再仅加载目标元数据，大编号完整回填', async () => {
    await mount();
    await click('选择目标批次');
    expect(records.getEtlRecordPage).toHaveBeenCalledWith(
      expect.objectContaining({
        archived: false,
        runStatus: 3,
        pageNo: 1,
        pageSize: 10,
      }),
    );
    expect(targets.getEtlTargetBatches).not.toHaveBeenCalled();
    await click('查看目标');
    expect(targets.getEtlTargetBatches).toHaveBeenCalledWith(
      '9007199254740993',
    );
    expect(host.textContent).toContain('选择不等于获得准入');
    await click('选择此批次');
    expect(selected).toHaveBeenCalledWith(batch);
    expect(targets.getEtlTargetRows).not.toHaveBeenCalled();
  });
  it('未通过质量的批次不能选择，租户切换后丢弃晚到元数据', async () => {
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([
      { ...batch, qualityStatus: 'FAILED' },
    ] as any);
    await mount();
    await click('选择目标批次');
    await click('查看目标');
    expect(button('选择此批次')?.disabled).toBe(true);
    let done!: (value: any) => void;
    vi.mocked(targets.getEtlTargetBatches).mockReturnValue(
      new Promise((resolve) => {
        done = resolve;
      }),
    );
    await click('查看目标');
    harness.access.refreshToken = '2';
    await flush();
    done([batch]);
    await flush();
    expect(button('选择此批次')).toBeUndefined();
    expect(selected).not.toHaveBeenCalled();
  });
  it('缺ETL查询权限不显示选择器，不影响调用方保留手填入口', async () => {
    harness.access.allowed = false;
    await mount();
    expect(button('选择目标批次')).toBeUndefined();
    expect(records.getEtlRecordPage).not.toHaveBeenCalled();
  });
});
