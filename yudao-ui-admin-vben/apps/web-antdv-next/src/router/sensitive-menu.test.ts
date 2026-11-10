import type { Component } from 'vue';

import type { AppRouteRecordRaw } from '@vben/types';

import { createApp, h, KeepAlive, nextTick, onActivated, onDeactivated, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';

import {
  convertServerMenuToRouteRecordStringComponent,
  generateRoutesByBackend,
} from '@vben/utils';

import { describe, expect, it, vi } from 'vitest';

const lifecycle = vi.hoisted(() => ({ activate: vi.fn(), deactivate: vi.fn() }));

vi.mock('#/views/asset/sensitive/index.vue', () => ({
  default: {
    name: 'AssetSensitiveList',
    props: { showStatusFilter: Boolean },
    setup(props: { showStatusFilter: boolean }) {
      onActivated(lifecycle.activate);
      onDeactivated(lifecycle.deactivate);
      return () => h('div', { 'data-status-filter': String(props.showStatusFilter) }, '敏感清单内容');
    },
  },
}));

const pageMap = import.meta.glob<{ default: Component }>('../views/**/*.vue');
const layout = { render: () => h('main') };

describe('敏感清单动态菜单', () => {
  it('数据资产和数据安全下的入口各自解析，治理入口复用原页面', async () => {
    const menus: AppRouteRecordRaw[] = [
      {
        id: 1, parentId: 0, name: '数据资产', componentName: 'DataAssetRoot',
        component: '', path: 'asset', visible: true, meta: { title: '数据资产' },
        children: [{
          id: 2, parentId: 1, name: '敏感清单', componentName: 'AssetSensitiveList',
          component: 'asset/sensitive/index', path: 'sensitive-list', visible: true,
          keepAlive: true, meta: { title: '敏感清单' },
        }],
      },
      {
        id: 3, parentId: 0, name: '数据治理', componentName: 'GovernanceRoot',
        component: '', path: 'govern', visible: true, meta: { title: '数据治理' },
        children: [{
          id: 4, parentId: 3, name: '数据安全', componentName: 'SafetyRoot',
          component: '', path: 'safety', visible: true, meta: { title: '数据安全' },
          children: [{
            id: 5, parentId: 4, name: '敏感清单', componentName: 'GovernanceSensitiveList',
            component: 'governance/security/sensitive/index', path: 'sensitive-list',
            visible: true, keepAlive: true, meta: { title: '敏感清单' },
          }],
        }],
      },
    ];
    const router = createRouter({ history: createMemoryHistory(), routes: [] });
    const routes = await generateRoutesByBackend({
      router, routes: [], pageMap, layoutMap: { BasicLayout: async () => layout },
      fetchMenuListAsync: async () => convertServerMenuToRouteRecordStringComponent(menus),
    });
    routes.forEach((route) => router.addRoute(route));

    const asset = router.resolve('/asset/sensitive-list');
    const governance = router.resolve('/govern/safety/sensitive-list');
    expect(asset.name).toBe('AssetSensitiveList');
    expect(governance.name).toBe('GovernanceSensitiveList');
    expect(asset.matched.at(-1)?.components?.default).toBe(pageMap['../views/asset/sensitive/index.vue']);
    expect(governance.matched.at(-1)?.components?.default).toBe(pageMap['../views/governance/security/sensitive/index.vue']);
    expect(governance.meta.keepAlive).toBe(true);
    const wrapper = await pageMap['../views/governance/security/sensitive/index.vue']!();
    expect(wrapper.default).toMatchObject({ name: 'GovernanceSensitiveList' });
    const container = document.createElement('div');
    const app = createApp(wrapper.default);
    app.mount(container);
    expect(container.textContent).toBe('敏感清单内容');
    expect(container.querySelector('div')?.dataset.statusFilter).toBe('true');
    app.unmount();
  });

  it('缓存治理入口时向复用页面传递激活和停用生命周期', async () => {
    const wrapper = await pageMap['../views/governance/security/sensitive/index.vue']!();
    const show = ref(true);
    lifecycle.activate.mockClear();
    lifecycle.deactivate.mockClear();
    const app = createApp({
      render: () => h(KeepAlive, null, {
        default: () => show.value ? h(wrapper.default) : h('p'),
      }),
    });
    app.mount(document.createElement('div'));
    await nextTick();
    show.value = false;
    await nextTick();
    expect(lifecycle.deactivate).toHaveBeenCalledTimes(1);
    show.value = true;
    await nextTick();
    expect(lifecycle.activate).toHaveBeenCalledTimes(1);
    app.unmount();
  });
});
