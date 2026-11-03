import type { RouteLocationNormalized } from 'vue-router';

import { beforeEach, expect, it, vi } from 'vitest';

import routes from './governance';

const state = vi.hoisted(() => ({
  access: { accessToken: '', loginExpired: false, accessCodes: [] as string[] },
  user: { userInfo: { userId: '' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => state.user,
}));
const rule = routes[0]!.children!.find(
  (item) => item.name === 'GovernanceSafetyRuleDetail',
)!;
const guard = rule.beforeEnter;
function visit(id: unknown, from = '/govern/safety/rule') {
  if (typeof guard !== 'function') throw new Error('规则详情缺少真实守卫');
  const to = {
    params: { id },
    name: rule.name,
    meta: { ...rule.meta },
  } as unknown as RouteLocationNormalized;
  const result = guard.call(undefined, to, { path: from } as RouteLocationNormalized, vi.fn());
  return { result, to };
}
beforeEach(() => {
  state.access.accessToken = 'test-session';
  state.access.loginExpired = false;
  state.access.accessCodes = ['governance:rule:query'];
  state.user.userInfo.userId = '9007199254740993';
});
it('keeps the auxiliary detail hidden and activates the existing dynamic navigation', () => {
  expect(rule.path).toBe('rule/detail/:id');
  expect(rule.meta).toMatchObject({
    hideInMenu: true,
    activePath: '/govern/safety/rule',
  });
  expect(visit('9007199254740997').result).toBe(true);
});
it('rejects malformed and unsafe numeric identities without replacing them with source IDs', () => {
  expect(visit('01').result).toEqual({ path: '/404', replace: true });
  expect(visit('20/../1').result).toEqual({ path: '/404', replace: true });
  expect(visit(9_007_199_254_740_992).result).toEqual({
    path: '/404',
    replace: true,
  });
});
it('requires a current authenticated actor and the actual query permission', () => {
  state.access.loginExpired = true;
  expect(visit('1').result).toEqual({ path: '/403', replace: true });
  state.access.loginExpired = false;
  state.access.accessCodes = [];
  expect(visit('1').result).toEqual({ path: '/403', replace: true });
  state.access.accessCodes = ['governance:rule:query'];
  state.user.userInfo.userId = '';
  expect(visit('1').result).toEqual({ path: '/403', replace: true });
});
