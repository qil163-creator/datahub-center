import type { RouteLocationNormalized } from 'vue-router';

import { beforeEach, expect, it, vi } from 'vitest';

import routes from './market-apply';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'token',
    loginExpired: false,
    accessCodes: ['asset:market:query'],
  },
  user: { userInfo: { userId: '9' }, userRoles: ['user'] },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
const enter = routes[0]!.beforeEnter as (
  to: RouteLocationNormalized,
) => unknown;
const location = (id: unknown) =>
  ({ params: { id } } as unknown as RouteLocationNormalized);
beforeEach(() => {
  mock.access = {
    accessToken: 'token',
    loginExpired: false,
    accessCodes: ['asset:market:query'],
  };
});
it('allows an ordinary department user without an administrator role', () => {
  expect(enter(location('9007199254740993'))).toBe(true);
});
it('rejects a source-origin marker rather than interpreting it as a native application', () => {
  expect(enter(location('QDATA:21'))).toEqual({ path: '/404', replace: true });
});
it('requires the unchanged applications permission and preserves navigation identity', () => {
  mock.access.accessCodes = [];
  expect(enter(location('1'))).toEqual({ path: '/403', replace: true });
  expect(routes[0]?.meta).toMatchObject({
    activePath: '/asset/my-assets',
    title: '申请资产详情',
    hideInMenu: true,
  });
});
