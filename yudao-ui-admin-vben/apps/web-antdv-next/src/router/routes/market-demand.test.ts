import type { Component } from 'vue';

import { createMemoryHistory, createRouter } from 'vue-router';

import { generateRoutesByBackend } from '@vben/utils';

import { describe, expect, it } from 'vitest';

const pageMap = import.meta.glob<{ default: Component }>(
  '../../views/**/*.vue',
);

describe('数据需求菜单', () => {
  it.each([
    ['hall', 'MarketDemandHall'],
    ['mine', 'MarketDemandMine'],
    ['match', 'MarketDemandMatch'],
  ])('将 demand-%s 菜单解析为实际页面', async (page, name) => {
    const path = `/asset/market/demand-${page}`;
    const component = `market/demand/${page}/index`;
    const router = createRouter({ history: createMemoryHistory(), routes: [] });
    const routes = await generateRoutesByBackend({
      router,
      routes: [],
      pageMap,
      fetchMenuListAsync: async () => [{ path, name, component }],
    });
    routes.forEach((route) => router.addRoute(route));

    const view = pageMap[`../../views/${component}.vue`];
    expect(view).toBeTypeOf('function');
    expect(router.resolve(path).matched.at(-1)?.components?.default).toBe(view);
  });
});
