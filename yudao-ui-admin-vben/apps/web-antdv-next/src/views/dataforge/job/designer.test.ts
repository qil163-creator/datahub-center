/* eslint-disable vue/one-component-per-file -- 使用画布与布局替身测试作业设计器的离开确认。 */
import { createApp, defineComponent, h, ref } from 'vue';

import { clearAllAlerts } from '@vben/common-ui';

import { afterEach, expect, it, vi } from 'vitest';

import Designer from './designer.vue';

const { registerGuard } = vi.hoisted(() => ({
  registerGuard: vi.fn<(guard: () => Promise<boolean>) => void>(),
}));

vi.mock('vue-router', () => ({
  onBeforeRouteLeave: registerGuard,
  useRoute: () => ({ params: { id: '1' } }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/common-ui', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/common-ui')>()),
  Page: defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  }),
}));
vi.mock('@vben/stores', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/stores')>()),
  useAccessStore: () => ({ accessToken: 'session' }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  });
  return {
    Alert: Container,
    Button: Container,
    ConfigProvider: Container,
    Input: Container,
    Modal: { confirm: vi.fn() },
    Spin: Container,
    message: {},
  };
});
vi.mock('#/api/dataforge/dev', () => ({
  getDevDirList: async () => [],
  getDevTask: vi.fn(),
  updateDevTaskStatus: vi.fn(),
}));
vi.mock('#/api/dataforge/integration', () => ({
  getIntegration: vi.fn(),
  getIntegrationCategories: async () => [],
  setIntegrationStatus: vi.fn(),
}));
vi.mock('#/api/dataforge/job', () => ({
  checkJob: vi.fn(),
  getJob: async () => ({ id: '1', name: '作业', status: 0 }),
  getJobTaskOptions: async () => [],
  updateJob: vi.fn(),
}));
vi.mock('../fullsync/use-scope', () => ({
  useFullSyncScope: () => ({ active: ref(true), scope: ref('1') }),
}));
vi.mock('../integration/modules/reference-icon.vue', () => ({
  default: () => null,
}));
vi.mock('./modules/job-form.vue', () => ({ default: () => null }));
vi.mock('./modules/job-task-editor.vue', () => ({ default: () => null }));
vi.mock('./modules/job-flow.vue', () => ({
  default: defineComponent({
    emits: ['update:flowJson'],
    setup: (_, { emit }) => () =>
      h(
        'button',
        {
          onClick: () => emit('update:flowJson', '{"nodes":[{}],"edges":[]}'),
        },
        '修改画布',
      ),
  }),
}));

afterEach(() => {
  clearAllAlerts();
  registerGuard.mockClear();
});

function findButton(label: string) {
  return [...document.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === label,
  );
}

it('作业有未保存修改时显示明确的离开按钮并按选择放行', async () => {
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp(Designer);
  app.mount(host);
  try {
    await vi.waitFor(() => expect(findButton('修改画布')).toBeDefined());
    const discard = registerGuard.mock.calls[0]![0];
    await expect(discard()).resolves.toBe(true);
    expect(findButton('确认离开')).toBeUndefined();

    findButton('修改画布')!.click();
    const cancelled = discard();
    await vi.waitFor(() => expect(findButton('继续编辑')).toBeDefined());
    expect(findButton('确认离开')).toBeDefined();
    findButton('继续编辑')!.click();
    await expect(cancelled).resolves.toBe(false);
    await vi.waitFor(() => expect(findButton('继续编辑')).toBeUndefined());

    const confirmed = discard();
    await vi.waitFor(() => expect(findButton('确认离开')).toBeDefined());
    findButton('确认离开')!.click();
    await expect(confirmed).resolves.toBe(true);
  } finally {
    app.unmount();
    host.remove();
  }
});
