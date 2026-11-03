import type {
  NavigationGuard,
  RouteLocationNormalized,
  RouteRecordRaw,
} from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import routes from './asset';
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:market:query'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
const route = routes[0]!.children!.find(
  (item) => item.name === 'AssetMarketDetailPage',
) as RouteRecordRaw;
const guard = route.beforeEnter as NavigationGuard;
const to = (id: unknown) =>
  ({ params: { id } }) as unknown as RouteLocationNormalized;
beforeEach(() => {
  mock.access.accessCodes = ['asset:market:query'];
  mock.access.accessToken = 'session';
  mock.access.loginExpired = false;
  mock.user.userInfo = { userId: '1' };
});
describe('market detail static route guard', () => {
  it('has a real login gate and preserves the menu active path', async () => {
    expect(route.meta).toMatchObject({
      hideInMenu: true,
      keepAlive: false,
      activePath: '/asset/mart',
    });
    expect(await guard(to('9223372036854775807'), to('1'), vi.fn())).toBe(true);
  });
  it('opens public detail without query permissions', async () => {
    mock.access.accessCodes = [];
    expect(await guard(to('1'), to('2'), vi.fn())).toBe(true);
  });
  it.each(['user', 'token', 'expired'] as const)(
    'rejects invalid login state: %s',
    async (change) => {
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      if (change === 'user') mock.user.userInfo = null;
      expect(await guard(to('1'), to('2'), vi.fn())).toEqual({
        path: '/403',
        replace: true,
      });
    },
  );
  it('rejects invalid route ids before the protected component loads', async () => {
    expect(await guard(to(['1', '2']), to('2'), vi.fn())).toEqual({
      path: '/404',
      replace: true,
    });
    expect(
      await guard(to(Number.MAX_SAFE_INTEGER + 1), to('2'), vi.fn()),
    ).toEqual({ path: '/404', replace: true });
  });
});

describe('asset map management detail route', () => {
  const map = routes[0]!.children!.find(
    (item) => item.name === 'AssetMarketMapDetail',
  ) as RouteRecordRaw;
  const enter = map.beforeEnter as NavigationGuard;

  it('uses the map as the sole management entry and hides its auxiliary detail route', () => {
    expect(routes[0]!.redirect).toBe('/asset/map');
    expect(map.path).toBe('market/map/detail/:id');
    expect(map.meta).toMatchObject({
      hideInMenu: true,
      keepAlive: false,
      activePath: '/asset/map',
    });
  });

  it('opens map detail without query permissions and keeps the original long id and return source', async () => {
    const id = '9223372036854775807';
    const target = {
      params: { id },
      path: `/asset/market/map/detail/${id}`,
      name: 'AssetMarketMapDetail',
      meta: { activePath: '/asset/map' },
    } as unknown as RouteLocationNormalized;
    const source = {
      path: '/asset/tag/detail/12',
      name: 'AssetTagDetail',
      meta: { activePath: '/asset/tag' },
    } as RouteLocationNormalized;
    mock.access.accessCodes = [];
    expect(await enter(target, source, vi.fn())).toBe(true);
    expect(target.params.id).toBe(id);
    expect(target.meta.activePath).toBe('/asset/map');
    mock.user.userInfo = null;
    expect(await enter(target, source, vi.fn())).toEqual({
      path: '/403',
      replace: true,
    });
  });

  it.each(['user', 'token', 'expired'] as const)(
    'rejects invalid map login state: %s',
    async (change) => {
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'expired') mock.access.loginExpired = true;
      if (change === 'user') mock.user.userInfo = null;
      expect(await enter(to('1'), to('2'), vi.fn())).toEqual({
        path: '/403',
        replace: true,
      });
    },
  );

  it('rejects invalid ids before loading the management detail component', async () => {
    mock.access.accessCodes = ['asset:data-asset:query'];
    expect(await enter(to(['1', '2']), to('1'), vi.fn())).toEqual({
      path: '/404',
      replace: true,
    });
    expect(
      await enter(to(Number.MAX_SAFE_INTEGER + 1), to('1'), vi.fn()),
    ).toEqual({ path: '/404', replace: true });
  });
});
