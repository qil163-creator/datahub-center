import { describe, expect, it, vi } from 'vitest';

const captured = vi.hoisted(() => ({ paths: [] as string[] }));

vi.mock('@vben/utils', () => ({
  mergeRouteModules: (files: Record<string, unknown>) => {
    // dynamic 与 external 路由分别调用一次，需累计全部路径
    captured.paths.push(...Object.keys(files));
    return [];
  },
  traverseTreeValues: () => [],
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./core', () => ({
  coreRoutes: [],
  fallbackNotFoundRoute: { path: '/:path(.*)*' },
}));

await import('./index');

describe('runtime route discovery', () => {
  it('loads route modules without importing colocated tests into the browser', () => {
    expect(captured.paths).toContain('./modules/modeling.ts');
    expect(captured.paths.length).toBeGreaterThan(0);
    expect(
      captured.paths.some((path) =>
        /\.(test|spec)\.ts$|\/__tests__\//.test(path),
      ),
    ).toBe(false);
  });
});
