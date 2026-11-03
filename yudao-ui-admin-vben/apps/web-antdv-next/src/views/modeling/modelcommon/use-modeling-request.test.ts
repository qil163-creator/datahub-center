import { createApp, defineComponent, h, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { useModelingRequest } from './use-modeling-request';

const stores = vi.hoisted(() => ({
  current: {} as { accessToken?: string; refreshToken?: string },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => stores.current }));

let cleanup = () => {};
afterEach(() => cleanup());

function setup() {
  stores.current = reactive({ accessToken: '1', refreshToken: '' });
  const identity = ref('9007199254740993');
  const reset = vi.fn();
  let guard!: ReturnType<typeof useModelingRequest>;
  const host = document.createElement('div');
  const app = createApp(
    defineComponent({
      setup() {
        guard = useModelingRequest(() => identity.value, reset);
        return () => h('div');
      },
    }),
  );
  app.mount(host);
  cleanup = () => app.unmount();
  return { guard, identity, reset };
}

describe('modeling request isolation', () => {
  it('invalidates a previous session response synchronously, even when switching back', () => {
    const { guard, reset } = setup();
    const previous = guard.begin();
    stores.current.accessToken = '2';
    stores.current.accessToken = '1';
    expect(previous()).toBe(false);
    expect(reset).toHaveBeenCalledTimes(2);
    expect(guard.begin()()).toBe(true);
  });

  it('allows the new object request started in the same tick and rejects the old one', () => {
    const { guard, identity } = setup();
    const previous = guard.begin();
    identity.value = '9007199254740995';
    const current = guard.begin();
    expect(previous()).toBe(false);
    expect(current()).toBe(true);
  });

  it('invalidates impersonation changes and unmounted responses', () => {
    const { guard } = setup();
    const previous = guard.begin();
    stores.current.refreshToken = '2';
    expect(previous()).toBe(false);
    const current = guard.begin();
    cleanup();
    cleanup = () => {};
    expect(current()).toBe(false);
  });
});
