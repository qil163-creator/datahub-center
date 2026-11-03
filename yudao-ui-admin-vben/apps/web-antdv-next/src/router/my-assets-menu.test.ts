import type { Component } from 'vue';

import type { AppRouteRecordRaw } from '@vben/types';

import { defineComponent, h } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';

import {
  convertServerMenuToRouteRecordStringComponent,
  generateMenus,
  generateRoutesByBackend,
} from '@vben/utils';

import { describe, expect, it, vi } from 'vitest';

import assetRoutes from './routes/modules/asset';

// 本组验证真实页面模块的路由装配，不挂载界面；组件交互由页面及浏览器回归覆盖。
vi.mock('@vben/common-ui', () => ({ Page: {}, useVbenModal: vi.fn(), useVbenDrawer: vi.fn() }));
vi.mock('@vben/hooks', () => ({ useIsMobile: vi.fn() }));
vi.mock('@vben/access', () => ({ useAccess: vi.fn() }));
vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));
vi.mock('#/views/market/data', () => ({ PROCESS_ACTION_OPTIONS: [] }));
vi.mock('antdv-next', () => Object.fromEntries([
  'Alert', 'Button', 'Empty', 'Input', 'InputNumber', 'Pagination', 'Select', 'Spin',
  'Table', 'Tag', 'Timeline', 'TimelineItem', 'Tooltip', 'TreeSelect',
].map((name) => [name, {}])));

const pageMap = import.meta.glob<{ default: Component }>('../views/**/*.vue');
const layout = defineComponent({ setup: () => () => h('main') });

/** 模拟权限服务仅下发数据资产导航及本人消费菜单，不包含数据集市管理菜单。 */
function ownAssetsMenu(): AppRouteRecordRaw[] {
  return [{
    id: 1, parentId: 0, name: '数据资产', componentName: 'DataAssetRoot',
    component: '', path: 'asset', visible: true, sort: 1, meta: { title: '数据资产' },
    children: [{
      id: '9223372036854775807', parentId: 1, name: '我的资产', componentName: 'AssetMarketMyAssets',
      path: 'my-assets', component: 'asset/market/my-assets/index', visible: true, keepAlive: false,
      sort: 20, meta: { title: '我的资产' },
    }],
  }];
}

describe('我的资产根级动态菜单', () => {
  it('将后端根目录菜单转换为真实页面加载器，页面不需要旧数据集市路径', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [] });
    const menuRoutes = convertServerMenuToRouteRecordStringComponent(ownAssetsMenu());
    const routes = await generateRoutesByBackend({
      router, routes: assetRoutes, pageMap, layoutMap: { BasicLayout: async () => layout },
      fetchMenuListAsync: async () => menuRoutes,
    });
    routes.forEach((route) => router.addRoute(route));
    const resolved = router.resolve('/asset/my-assets');
    expect(resolved.name).toBe('AssetMarketMyAssets');
    expect(resolved.meta).toMatchObject({ title: '我的资产', hideInMenu: false, keepAlive: false });
    expect(resolved.meta.activePath).toBeUndefined();
    expect(resolved.matched.at(-1)?.components?.default).toBe(pageMap['../views/asset/market/my-assets/index.vue']);
    expect(router.resolve({ name: 'AssetMarketMyAssets' }).path).toBe('/asset/my-assets');

    const menus = generateMenus(routes, router);
    const root = menus.find((menu) => menu.path === '/asset' && menu.name === '数据资产');
    expect(root?.children?.find((menu) => menu.name === '我的资产')).toMatchObject({
      path: '/asset/my-assets', parent: '/asset', show: true,
    });
    expect(router.getRoutes().filter((route) => route.name === 'AssetMarketMyAssets')).toHaveLength(1);
  });

  it('真实 views 页面可由后端 component 字符串加载，辅助静态路由不重复注册页面', async () => {
    const loader = pageMap['../views/asset/market/my-assets/index.vue'];
    expect(loader).toBeTypeOf('function');
    const module = await loader!();
    expect(module.default).toMatchObject({ name: 'AssetMarketMyAssets' });
    expect(assetRoutes.flatMap((route) => route.children ?? []).some((route) => route.name === 'AssetMarketMyAssets')).toBe(false);
  });
});
