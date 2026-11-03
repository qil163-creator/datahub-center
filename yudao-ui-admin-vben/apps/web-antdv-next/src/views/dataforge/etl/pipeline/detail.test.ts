import type { App, PropType } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import Detail from './detail.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  options: {} as { onOpenChange: (open: boolean) => Promise<void> },
  id: '9007199254740993',
  locked: false,
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      defineComponent({
        props: ['title'],
        setup:
          (props, { slots }) =>
          () =>
            h('section', [props.title, slots.default?.()]),
      }),
      {
        getData: () => ({ id: harness.id }),
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        close: harness.close,
      },
    ];
  },
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: {
        type: Array as PropType<{ value?: unknown }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'div',
        props.fields.map((field) => String(field.value ?? '')).join(' '),
      ),
  }),
}));
vi.mock('#/api/dataforge/etl', () => ({ getEtlPipeline: vi.fn() }));
let app: App;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.locked = false;
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.id = '9007199254740993';
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('etl detail lifecycle', () => {
  it('does not let a closed request replace or unlock a newer detail load', async () => {
    let oldFinish!: (value: DataforgeEtlApi.Pipeline) => void;
    let nextFinish!: (value: DataforgeEtlApi.Pipeline) => void;
    vi.mocked(api.getEtlPipeline)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          oldFinish = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise((resolve) => {
          nextFinish = resolve;
        }),
      );
    const first = harness.options.onOpenChange(true);
    await harness.options.onOpenChange(false);
    harness.id = '9007199254740995';
    const next = harness.options.onOpenChange(true);
    oldFinish({ id: '9007199254740993', name: 'Old detail' });
    await first;
    await flush();
    expect(host.textContent).not.toContain('Old detail');
    expect(harness.locked).toBe(true);
    nextFinish({ id: '9007199254740995', name: 'Current detail' });
    await next;
    await flush();
    expect(host.textContent).toContain('Current detail');
    expect(harness.locked).toBe(false);
  });

  it('closes and discards an old session detail response', async () => {
    let finish!: (value: DataforgeEtlApi.Pipeline) => void;
    vi.mocked(api.getEtlPipeline).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const pending = harness.options.onOpenChange(true);
    harness.access.refreshToken = '2';
    await flush();
    finish({ name: 'Previous session data' });
    await pending;
    await flush();
    expect(harness.close).toHaveBeenCalled();
    expect(host.textContent).not.toContain('Previous session data');
  });
});
