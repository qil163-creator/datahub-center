import { createMemoryHistory, createRouter } from 'vue-router';

import { describe, expect, it, vi } from 'vitest';

import { overridesPreferences } from '#/preferences';

import routes from './home';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
// 导航会解析懒加载组件，这里替换为空组件，避免加载整个工作台页面
vi.mock('#/views/dashboard/workspace/index.vue', () => ({ default: {} }));

describe('home routes', () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...routes,
      {
        component: {},
        name: 'FallbackNotFound',
        path: '/:path(.*)*',
      },
    ],
  });

  it('uses the home page as the default home', () => {
    const homePath = overridesPreferences.app?.defaultHomePath;
    expect(homePath).toBe('/home');
    expect(router.resolve(homePath!).name).toBe('Home');
  });

  it('keeps the home page out of the menu and pinned as a single tab', () => {
    const pinned = router
      .getRoutes()
      .filter((record) => record.meta?.affixTab)
      .map((record) => record.path);
    expect(pinned).toEqual(['/home']);
    expect(router.resolve('/home').meta.hideInMenu).toBe(true);
  });

  it.each([
    '/dashboard',
    '/dashboard/workspace',
    '/workspace',
    '/dashboard/analytics',
    '/analytics',
  ])('redirects the legacy URL %s to the home page', async (path) => {
    await router.push(path);
    expect(router.currentRoute.value.name).toBe('Home');
    expect(router.currentRoute.value.path).toBe('/home');
  });
});
