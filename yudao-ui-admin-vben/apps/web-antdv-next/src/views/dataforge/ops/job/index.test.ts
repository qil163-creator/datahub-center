import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { expect, it, vi } from 'vitest';

import Page from './index.vue';

const router = vi.hoisted(() => ({ replace: vi.fn() }));
const route = reactive<{ query: Record<string, string> }>({ query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }));
vi.mock('../workbench.vue', () => ({ default: defineComponent({
  props: { jobId: { type: String, default: undefined } },
  emits: ['clearJobFilter'],
  setup: (props, { emit }) => () => h('button', { onClick: () => emit('clearJobFilter') }, props.jobId || '全部'),
}) }));

it('路由作业编号变更时更新筛选并可清除', async () => {
  route.query = { jobId: '9007199254740993', other: 'keep' };
  const host = document.createElement('div');
  const app = createApp(Page);
  app.mount(host);
  expect(host.textContent).toBe('9007199254740993');
  route.query.jobId = '9007199254740995';
  await nextTick();
  expect(host.textContent).toBe('9007199254740995');
  host.querySelector('button')!.click();
  expect(router.replace).toHaveBeenCalledWith({ query: { other: 'keep' } });
  app.unmount();
});
