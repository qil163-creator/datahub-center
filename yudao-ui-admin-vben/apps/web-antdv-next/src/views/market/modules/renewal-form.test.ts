import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createRenewalRequest } from '#/api/asset/market';

import RenewalForm from './renewal-form.vue';

interface ModalControls {
  close: () => Promise<void>;
  confirm: () => Promise<void>;
  open: (apply: { applyEndTime?: string; id: string }) => void;
}

const harness = vi.hoisted(() => ({
  lock: vi.fn(),
  modal: undefined as ModalControls | undefined,
  success: vi.fn(),
  unlock: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
  }) => {
    const visible = ref(false);
    let data: unknown;
    const api = {
      close: async () => {
        visible.value = false;
        options.onOpenChange(false);
      },
      getData: () => data,
      lock: harness.lock,
      unlock: harness.unlock,
    };
    harness.modal = {
      close: api.close,
      confirm: options.onConfirm,
      open: (apply) => {
        data = { apply };
        visible.value = true;
        options.onOpenChange(true);
      },
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('section', slots.default?.()) : null,
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => ({
  DatePicker: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (_, { emit }) =>
      () =>
        h('input', {
          'data-renewal-date': '',
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  }),
  message: { success: vi.fn(), warning: vi.fn() },
}));
vi.mock('#/api/asset/market', () => ({ createRenewalRequest: vi.fn() }));

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 5; index++) await Promise.resolve();
  await nextTick();
}

async function date(value: string) {
  const input = host.querySelector<HTMLInputElement>('[data-renewal-date]')!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RenewalForm, { onSuccess: harness.success });
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('market renewal form component', () => {
  it('submits a lossless original ID and reuses one operation identity after an uncertain response', async () => {
    const originalId = '9223372036854775807';
    harness.modal!.open({ id: originalId, applyEndTime: '2030-01-01' });
    await flush();
    await date('4102444800000');
    vi.mocked(createRenewalRequest)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce('9223372036854775806');
    await expect(harness.modal!.confirm()).rejects.toThrow('response lost');
    await harness.modal!.confirm();
    expect(createRenewalRequest).toHaveBeenCalledTimes(2);
    const first = vi.mocked(createRenewalRequest).mock.calls[0]![0];
    expect(first).toMatchObject({
      applyEndTime: '4102444800000',
      id: originalId,
      operationId: expect.any(String),
    });
    expect(vi.mocked(createRenewalRequest).mock.calls[1]![0]).toEqual(first);
    expect(harness.success).toHaveBeenCalledWith('9223372036854775806');
  });

  it('ignores an old completion after closing and opening another application', async () => {
    const old = deferred<string>();
    const current = deferred<string>();
    vi.mocked(createRenewalRequest)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    harness.modal!.open({ id: '1' });
    await flush();
    await date('4102444800000');
    const pending = harness.modal!.confirm();
    await harness.modal!.close();
    harness.modal!.open({ id: '2' });
    await flush();
    await date('4102444800000');
    const nextPending = harness.modal!.confirm();
    expect(harness.unlock).toHaveBeenCalledOnce();
    old.resolve('3');
    await pending;
    await flush();
    expect(host.textContent).toContain('2');
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.unlock).toHaveBeenCalledOnce();
    current.resolve('4');
    await nextPending;
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.success).toHaveBeenCalledWith('4');
  });
});
