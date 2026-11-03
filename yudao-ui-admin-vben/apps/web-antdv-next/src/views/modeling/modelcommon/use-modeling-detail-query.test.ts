import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useModelingDetailQuery } from './use-modeling-detail-query';

const context = vi.hoisted(() => ({ route: {} as { path: string; query: { detailId?: string | string[] } } }));
vi.mock('vue-router', () => ({ useRoute: () => context.route }));
let app: App;
let host: HTMLDivElement;
const open = vi.fn();
async function flush() { await nextTick(); await nextTick(); }
function mount() {
  host = document.createElement('div');
  app = createApp(defineComponent({ setup() { useModelingDetailQuery(open); return () => h('div'); } }));
  app.mount(host);
}
beforeEach(() => { open.mockClear(); context.route = reactive({ path: '/configured/process', query: {} }); });
afterEach(() => { app?.unmount(); host?.remove(); });

describe('planning reference detail query', () => {
  it('opens the existing destination detail with an exact Long identifier', async () => {
    context.route.query.detailId = '9007199254740993123';
    mount();
    await flush();
    expect(open).toHaveBeenCalledWith('9007199254740993123');
  });
  it('handles reused tabs and does not open another route query on a retained page', async () => {
    mount();
    context.route.query.detailId = '12';
    await flush();
    expect(open).toHaveBeenLastCalledWith('12');
    context.route.path = '/different/page';
    context.route.query.detailId = '24';
    await flush();
    expect(open).toHaveBeenCalledTimes(1);
    context.route.path = '/configured/process';
    await flush();
    expect(open).toHaveBeenLastCalledWith('24');
  });
  it('rejects invalid and repeated identifier query parameters', async () => {
    context.route.query.detailId = ['1', '2'];
    mount();
    await flush();
    context.route.query.detailId = '1.5';
    await flush();
    expect(open).not.toHaveBeenCalled();
  });
});
