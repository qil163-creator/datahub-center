/* eslint-disable vue/one-component-per-file -- 用轻量组件桩验证抽屉的状态和操作栏。 */
import { createApp, h, nextTick } from 'vue';

import { expect, it, vi } from 'vitest';

import ExportPanel from './export-panel.vue';

const { getPage } = vi.hoisted(() => ({ getPage: vi.fn() }));
vi.mock('#/api/asset/dataexport', () => ({ getMyDataExportPage: getPage }));
vi.mock('#/views/governance/accessalert/data', () => ({ formatTime: String }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('antdv-next', async () => {
  const { defineComponent } = await import('vue');
  return {
    Alert: () => null,
    Button: defineComponent(
      (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    ),
    Drawer: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open && slots.default?.(),
    }),
    InputNumber: () => null,
    Modal: () => null,
    Table: defineComponent({
      props: { dataSource: { required: true, type: Array } },
      setup:
        (props, { slots }) =>
        () =>
          props.dataSource?.map((record) =>
            ['status', 'actions'].map((key) =>
              slots.bodyCell?.({ column: { key }, record }),
            ),
          ),
    }),
    Tag: defineComponent(
      (_, { slots }) =>
        () =>
          h('span', slots.default?.()),
    ),
    TextArea: () => null,
    message: {},
  };
});

it('updates the label and download action together at each deadline in an open drawer', async () => {
  vi.useFakeTimers();
  const start = Date.now();
  getPage.mockResolvedValue({
    list: [10, 20].map((delay) => ({
      expireTime: start + delay,
      id: delay,
      status: 'APPROVED',
    })),
    total: 2,
  });
  const host = document.createElement('div');
  const app = createApp(ExportPanel, {
    limit: 1,
    sqlProvider: () => 'SELECT 1',
  });
  const buttons = (label: string) =>
    [...host.querySelectorAll('button')].filter(
      (btn) => btn.textContent?.trim() === label,
    );
  try {
    app.mount(host);
    const [open] = buttons('我的导出');
    expect(open).toBeDefined();
    open!.click();
    await Promise.resolve();
    await nextTick();
    await vi.advanceTimersByTimeAsync(9);
    expect(buttons('下载')).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(buttons('下载')).toHaveLength(1);
    expect(host.textContent).toContain('下载已过期');
    await vi.advanceTimersByTimeAsync(10);
    expect(buttons('下载')).toHaveLength(0);
    expect(host.textContent).not.toContain('可下载');
    expect(getPage).toHaveBeenCalledTimes(1);
  } finally {
    app.unmount();
    vi.useRealTimers();
  }
});
