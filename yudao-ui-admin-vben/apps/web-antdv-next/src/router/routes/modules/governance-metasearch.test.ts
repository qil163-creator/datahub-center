import type { NavigationGuard, RouteLocationNormalized } from 'vue-router';

import { createMemoryHistory, createRouter } from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import routes from './governance';

const state = vi.hoisted(() => ({
  access: { accessToken: '', loginExpired: false, accessCodes: [] as string[] },
  user: { userInfo: { userId: '' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => state.user,
}));

const detail = routes[0]!.children!.find(
  (item) => item.name === 'GovernanceMetaSearchDetail',
)!;
function visit(id: unknown) {
  const guard = detail.beforeEnter;
  if (typeof guard !== 'function')
    throw new Error('元数据检索详情缺少路由守卫');
  const runGuard: NavigationGuard = guard;
  return runGuard(
    { params: { id } } as unknown as RouteLocationNormalized,
    {} as RouteLocationNormalized,
    vi.fn(),
  );
}

beforeEach(() => {
  state.access.accessToken = 'current-session';
  state.access.loginExpired = false;
  state.access.accessCodes = ['governance:meta-table:query'];
  state.user.userInfo.userId = '9007199254740993';
});

describe('元数据检索详情辅助路由', () => {
  it('无损保留表编号，隐藏菜单并激活现有元数据检索导航', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    const id = '9007199254740993123';
    const resolved = router.resolve({
      name: 'GovernanceMetaSearchDetail',
      params: { id },
    });

    expect(resolved.path).toBe(`/governance/metasearch/detail/${id}`);
    expect(resolved.params.id).toBe(id);
    expect(resolved.meta).toMatchObject({
      title: '元数据检索详情',
      activePath: '/govern/metadata/metasearch',
      hideInMenu: true,
      keepAlive: false,
    });
    expect(visit(id)).toBe(true);
  });

  it.each([
    { id: '' },
    { id: '01' },
    { id: '1.5' },
    { id: '20/../1' },
    { id: ['1'] },
    { id: 9_007_199_254_740_992 },
  ])('拒绝无效表编号 $id', ({ id }) => {
    expect(visit(id)).toEqual({ path: '/404', replace: true });
  });

  it('只有有效登录用户持有元数据查询权限时才允许访问', () => {
    state.access.accessToken = '';
    expect(visit('1')).toEqual({ path: '/403', replace: true });
    state.access.accessToken = 'current-session';
    state.access.loginExpired = true;
    expect(visit('1')).toEqual({ path: '/403', replace: true });
    state.access.loginExpired = false;
    state.access.accessCodes = ['governance:meta-tag:query'];
    expect(visit('1')).toEqual({ path: '/403', replace: true });
    state.access.accessCodes = ['governance:meta-table:query'];
    state.user.userInfo.userId = '';
    expect(visit('1')).toEqual({ path: '/403', replace: true });
  });
});
