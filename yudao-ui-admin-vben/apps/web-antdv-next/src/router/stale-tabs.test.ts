import { createMemoryHistory, createRouter } from 'vue-router';

import { useTabbarStore } from '@vben/stores';

import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import routes from './routes/modules/home';
import { removeStaleTabs } from './stale-tabs';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/views/dashboard/workspace/index.vue', () => ({ default: {} }));
vi.mock('./routes/core', () => ({
  fallbackNotFoundRoute: { name: 'FallbackNotFound' },
}));

function tab(path: string, affixTab = false): any {
  return { fullPath: path, key: path, meta: { affixTab }, name: path, path };
}

describe('removeStaleTabs', () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...routes,
      { component: {}, name: 'BasicHome', path: '/basic/home' },
      { component: {}, name: 'FallbackNotFound', path: '/:path(.*)*' },
    ],
  });

  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('removes restored tabs whose page was removed or became a redirect', async () => {
    const store = useTabbarStore();
    store.tabs = [
      tab('/dashboard/analytics', true),
      tab('/analytics', true),
      tab('/dashboard/workspace', true),
      tab('/workspace', true),
      tab('/home', true),
      tab('/basic/home'),
      tab('/not-exists'),
    ];

    await removeStaleTabs(router);

    expect(store.tabs.map((item) => item.path)).toEqual([
      '/home',
      '/basic/home',
    ]);
  });

  it('keeps every tab when none is stale', async () => {
    const store = useTabbarStore();
    store.tabs = [tab('/home', true), tab('/basic/home')];

    await removeStaleTabs(router);

    expect(store.tabs).toHaveLength(2);
  });
});
