import { createApp, defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import StandardToolbar from './standard-toolbar.vue';

vi.mock('antdv-next', () => {
  const Wrapper = defineComponent({
    setup(_, { slots }) {
      return () => h('div', [slots.default?.(), slots.content?.()]);
    },
  });
  const Button = defineComponent({
    setup(_, { attrs, slots }) {
      return () => h('button', attrs, slots.icon?.());
    },
  });
  return { Button, Checkbox: Wrapper, Popover: Wrapper, Tooltip: Wrapper };
});

describe('标准详情工具栏', () => {
  it('使用本地图标并保持刷新事件', () => {
    const refresh = vi.fn();
    const host = document.createElement('div');
    const app = createApp({
      render: () => h(StandardToolbar, {
        columns: [],
        visibleColumns: [],
        settingsIcon: 'lucide:settings',
        onRefresh: refresh,
      }),
    });
    app.mount(host);

    for (const label of ['显示/隐藏查询', '刷新', '列设置']) {
      expect(host.querySelector(`[aria-label="${label}"] svg path`)).not.toBeNull();
    }
    host.querySelector<HTMLButtonElement>('[aria-label="刷新"]')!.click();
    expect(refresh).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('未指定设置图标时仍显示本地列设置图标', () => {
    const host = document.createElement('div');
    const app = createApp({
      render: () => h(StandardToolbar, { columns: [], visibleColumns: [] }),
    });
    app.mount(host);
    expect(host.querySelector('[aria-label="列设置"] svg')).not.toBeNull();
    app.unmount();
  });
});
