import { createMemoryHistory, createRouter } from 'vue-router';

import { preferences } from '@vben/preferences';

import { describe, expect, it, vi } from 'vitest';

import routes from './dashboard';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

describe('dashboard routes', () => {
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

  it('resolves the configured default home to Analytics', () => {
    expect(router.resolve(preferences.app.defaultHomePath).name).toBe(
      'Analytics',
    );
  });

  it('resolves the dashboard workspace URL', () => {
    expect(router.resolve('/dashboard/workspace').name).toBe('Workspace');
  });

  it.each([
    ['/analytics', 'Analytics'],
    ['/workspace', 'Workspace'],
  ])('preserves the existing bookmark %s', (path, name) => {
    expect(router.resolve(path).name).toBe(name);
  });
});
