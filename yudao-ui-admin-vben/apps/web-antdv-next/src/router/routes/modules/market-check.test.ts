import type { Component } from 'vue';
import type { NavigationGuard, RouteLocationNormalized } from 'vue-router';

import { createMemoryHistory, createRouter } from 'vue-router';

import { generateRoutesByBackend } from '@vben/utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import routes from './market-check';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:market:check'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
const route = routes[0]!;
const enter = route.beforeEnter as NavigationGuard;
const target = (id: unknown, origin: unknown = 'QDATA') =>
  ({ params: { id }, query: { origin } }) as unknown as RouteLocationNormalized;

beforeEach(() => {
  mock.access.accessToken = 'session';
  mock.access.loginExpired = false;
  mock.access.accessCodes = ['asset:market:check'];
  mock.user.userInfo = { userId: '1' };
});

describe('审批工单菜单', () => {
  it('将后端菜单配置解析为审批工单页面', async () => {
    const pageMap = import.meta.glob<{ default: Component }>(
      '../../../views/**/*.vue',
    );
    const router = createRouter({ history: createMemoryHistory(), routes: [] });
    const menuRoutes = await generateRoutesByBackend({
      router,
      routes,
      pageMap,
      fetchMenuListAsync: async () => [
        {
          path: '/asset/check',
          name: 'MarketCheck',
          component: 'market/check/index',
        },
      ],
    });
    menuRoutes.forEach((menuRoute) => router.addRoute(menuRoute));

    const page = pageMap['../../../views/market/check/index.vue'];
    expect(page).toBeTypeOf('function');
    expect(
      router.resolve('/asset/check').matched.at(-1)?.components?.default,
    ).toBe(page);
  });
});

describe('审批工单独立详情路由', () => {
  it('保持长编号、来源和审批导航激活项', async () => {
    expect(route.meta).toMatchObject({
      hideInMenu: true,
      keepAlive: false,
      activePath: '/asset/check',
    });
    const to = target('9223372036854775807');
    expect(await enter(to, target('1'), vi.fn())).toBe(true);
    expect(to.params.id).toBe('9223372036854775807');
    expect(to.query.origin).toBe('QDATA');
  });
  it.each([
    ['1', 'REMOTE'],
    [['1', '2'], 'QDATA'],
    ['1', ['QDATA', 'NATIVE']],
    [Number.MAX_SAFE_INTEGER + 1, 'QDATA'],
  ])('拒绝非法编号或来源', async (id, origin) => {
    expect(await enter(target(id, origin), target('1'), vi.fn())).toEqual({
      path: '/404',
      replace: true,
    });
  });
  it.each(['token', 'expired', 'user', 'permission'])(
    '拒绝失效身份或权限：%s',
    async (change) => {
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      if (change === 'user') mock.user.userInfo = null;
      if (change === 'permission') mock.access.accessCodes = [];
      expect(await enter(target('1'), target('1'), vi.fn())).toEqual({
        path: '/403',
        replace: true,
      });
    },
  );
});
