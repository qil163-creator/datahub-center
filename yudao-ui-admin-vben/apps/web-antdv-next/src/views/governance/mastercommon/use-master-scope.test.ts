import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { expect, it, vi } from 'vitest';

import { useMasterScope } from './use-master-scope';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  user: { userInfo: { userId: '1' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));

it('新查询、登录会话切换和卸载均使旧响应失效，往返切换也不复活旧响应', async () => {
  harness.access = reactive({
    accessToken: '9007199254740995',
    refreshToken: undefined as string | undefined,
  });
  let scope!: ReturnType<typeof useMasterScope>;
  const changed = vi.fn();
  const app = createApp(
    defineComponent({
      setup() {
        scope = useMasterScope(changed);
        return () => h('div');
      },
    }),
  );
  const root = document.createElement('div');
  app.mount(root);
  const old = scope.begin('list');
  const newest = scope.begin('list');
  expect(old()).toBe(false);
  expect(newest()).toBe(true);
  harness.access.refreshToken = '9007199254740997';
  await nextTick();
  expect(newest()).toBe(false);
  expect(changed).toHaveBeenCalledOnce();
  harness.access.refreshToken = undefined;
  await nextTick();
  const returned = scope.begin('list');
  expect(newest()).toBe(false);
  expect(returned()).toBe(true);
  app.unmount();
  expect(returned()).toBe(false);
});
