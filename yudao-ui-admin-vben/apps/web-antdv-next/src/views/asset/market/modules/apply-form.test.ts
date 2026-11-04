import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createMarketApply,
  getMarketAvailableApis,
  getMarketAvailableApps,
  resubmitMarketApply,
  submitMarketApply,
  updateMarketApply,
} from '#/api/asset/market';

import ApplyForm from './apply-form.vue';

const harness = vi.hoisted(() => ({
  confirm: undefined as (() => Promise<void>) | undefined,
  open: undefined as ((data: object) => void) | undefined,
  saved: vi.fn(),
}));

vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
  }) => {
    const visible = ref(false);
    let data: object;
    harness.confirm = options.onConfirm;
    harness.open = (nextData) => {
      data = nextData;
      visible.value = true;
      options.onOpenChange(true);
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('section', slots.default?.()) : null,
      }),
      {
        close: () => {
          visible.value = false;
          options.onOpenChange(false);
        },
        getData: () => data,
        lock: () => {},
        unlock: () => {},
      },
    ];
  },
}));

vi.mock('antdv-next', () => {
  const Input = defineComponent({
    props: ['value', 'placeholder'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          placeholder: props.placeholder,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Button: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    DatePicker: Input,
    Input,
    InputNumber: Input,
    Select: defineComponent({
      props: ['options', 'placeholder', 'value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              'aria-label': props.placeholder,
              value: props.value,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            (props.options ?? []).map(
              (option: { label: string; value: string }) =>
                h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    TextArea: Input,
    message: { success: vi.fn(), warning: vi.fn() },
  };
});

vi.mock('#/api/asset/market', () => ({
  createMarketApply: vi.fn(),
  getMarketAvailableApis: vi.fn(),
  getMarketAvailableApps: vi.fn(),
  resubmitMarketApply: vi.fn(),
  submitMarketApply: vi.fn(),
  updateMarketApply: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 5; index++) await Promise.resolve();
  await nextTick();
}

async function choose(selector: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(selector)!;
  element.value = value;
  element.dispatchEvent(
    new Event(element.tagName === 'SELECT' ? 'change' : 'input', {
      bubbles: true,
    }),
  );
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getMarketAvailableApps).mockResolvedValue([
    { id: '9223372036854775806', name: '申请应用' },
  ]);
  vi.mocked(getMarketAvailableApis).mockResolvedValue([
    { apiId: '9223372036854775805', name: '字段接口' },
  ]);
  vi.mocked(createMarketApply).mockResolvedValue('9223372036854775804');
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ApplyForm, { onSuccess: harness.saved });
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('market application form component', () => {
  it.each([1, 2])(
    'submits a finite approved metric %s without replacing it with unlimited',
    async (applyUseType) => {
      harness.open!({
        assetId: '9',
        title: '政务共享申请',
        applyUseType,
        applyUseTimes: 100,
      });
      await flush();
      await harness.confirm!();
      expect(createMarketApply).toHaveBeenCalledWith(
        expect.objectContaining({ applyUseType, applyUseTimes: 100 }),
      );
    },
  );
  it('rejects zero finite quota before submission', async () => {
    harness.open!({
      assetId: '9',
      title: '政务共享申请',
      applyUseType: 1,
      applyUseTimes: 0,
    });
    await flush();
    await harness.confirm!();
    expect(createMarketApply).not.toHaveBeenCalled();
  });
  it('submits the selected field range and exact application/API identities', async () => {
    const assetId = '9223372036854775807';
    harness.open!({ assetId, assetName: '项目数据' });
    await flush();
    expect(getMarketAvailableApis).toHaveBeenCalledWith(assetId);
    await choose('input[placeholder^="可申请字段"]', 'name,org_code');
    await choose(
      'select[aria-label="选择本部门可代表的应用"]',
      '9223372036854775806',
    );
    await choose(
      'select[aria-label="选择已绑定到该资源的 API"]',
      '9223372036854775805',
    );
    await harness.confirm!();
    expect(createMarketApply).toHaveBeenCalledWith(
      expect.objectContaining({
        assetId,
        appId: '9223372036854775806',
        apiId: '9223372036854775805',
        fieldScope: 'name,org_code',
        submit: true,
      }),
    );
    expect(harness.saved).toHaveBeenCalledOnce();
  });

  it.each([
    [0, submitMarketApply, resubmitMarketApply],
    [8, resubmitMarketApply, submitMarketApply],
  ] as const)(
    'sends the seen revision with the edit and the incremented one with status %s submission',
    async (status, used, unused) => {
      const id = '9223372036854775803';
      harness.open!({ id, assetId: '9', title: '草稿', status, revision: 4 });
      await flush();
      await harness.confirm!();
      expect(updateMarketApply).toHaveBeenCalledWith(
        expect.objectContaining({ id, expectedRevision: 4, submit: true }),
      );
      expect(used).toHaveBeenCalledExactlyOnceWith(id, 5);
      expect(unused).not.toHaveBeenCalled();
      expect(createMarketApply).not.toHaveBeenCalled();
    },
  );

  it('does not send a seen revision for legacy drafts without one', async () => {
    const id = '9223372036854775803';
    harness.open!({ id, assetId: '9', title: '草稿', status: 0 });
    await flush();
    await harness.confirm!();
    expect(updateMarketApply).toHaveBeenCalledWith(
      expect.objectContaining({ id, expectedRevision: undefined }),
    );
    expect(submitMarketApply).toHaveBeenCalledExactlyOnceWith(id, undefined);
  });

  it('does not submit when the edit is rejected as stale', async () => {
    vi.mocked(updateMarketApply).mockRejectedValueOnce(
      new Error('revision conflict'),
    );
    harness.open!({
      id: '1',
      assetId: '9',
      title: '草稿',
      status: 0,
      revision: 4,
    });
    await flush();
    await expect(harness.confirm!()).rejects.toThrow('revision conflict');
    expect(submitMarketApply).not.toHaveBeenCalled();
    expect(harness.saved).not.toHaveBeenCalled();
  });
});
