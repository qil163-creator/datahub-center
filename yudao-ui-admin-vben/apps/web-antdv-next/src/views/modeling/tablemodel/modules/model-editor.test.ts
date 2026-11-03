/* eslint-disable vue/one-component-per-file -- 控件和当前设计替身共同验证编辑器边界接线。 */
import type { App, PropType } from 'vue';

import type { TableModelApi } from '#/api/modeling/tablemodel';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/tablemodel';

import ModelEditor from './model-editor.vue';

const state = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
  designRevision: '9007199254740997',
  saveResults: [] as unknown[],
  confirmLeave: vi.fn(),
  openHeader: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
vi.mock('#/api/modeling/tablemodel', () => ({
  discardTableModel: vi.fn(),
  getTableModel: vi.fn(),
  getTableModelDesign: vi.fn(),
  modelSaveData: (value: unknown) => value,
  publishTableModel: vi.fn(),
  saveTableModelDraft: vi.fn(),
}));

vi.mock('../../modelcommon/data', () => ({
  TABLE_TYPE_OPTIONS: [],
  optionLabel: () => '明细表',
}));
vi.mock('./form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: state.openHeader });
      return () => h('div');
    },
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: plain,
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Card: plain,
    Spin: plain,
    Table: plain,
    Tabs: plain,
    Tag: plain,
    message: { warning: vi.fn() },
    Modal: { confirm: vi.fn() },
  };
});
vi.mock('../../modelcommon/current-design-editor.vue', () => ({
  default: defineComponent({
    props: {
      api: {
        type: Object as PropType<{
          getDesign: (id: string) => Promise<TableModelApi.Design>;
          publish: (id: string, expectedRevision: string) => Promise<unknown>;
          saveDraft: (data: unknown) => Promise<unknown>;
        }>,
        required: true,
      },
      modelId: { type: String, required: true },
      readonly: Boolean,
    },
    setup: (props, { expose }) => {
      expose({ confirmLeave: state.confirmLeave, reload: vi.fn() });
      return () =>
        h('div', [
          h(
            'button',
            {
              class: 'save-draft',
              disabled: props.readonly,
              onClick: async () =>
                state.saveResults.push(
                  await props.api.saveDraft({
                    expectedRevision: state.designRevision,
                    fields: [
                      {
                        fieldUid: 'stable-1',
                        fieldName: 'amount',
                        defaultValue: '9007199254740993.01',
                      },
                    ],
                    typeConfig: { detail: { grainFields: ['amount'] } },
                  }),
                ),
            },
            '保存草稿',
          ),
          h(
            'button',
            {
              class: 'publish',
              disabled: props.readonly,
              onClick: () =>
                props.api.publish(props.modelId, state.designRevision),
            },
            '发布',
          ),
        ]);
    },
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
const model = (
  id = '9007199254740993',
  revision: number | string = '9007199254740997',
): TableModelApi.Model => ({
  id,
  revision,
  layoutRevision: 1,
  name: `模型${id}`,
  code: 'dwd_amount',
  tableType: 'DETAIL',
  status: 'DRAFT',
});
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
function mount(id = '9007199254740993') {
  const props = reactive({ modelId: id });
  app = createApp({ setup: () => () => h(ModelEditor, props) });
  app.directive('access', {});
  app.mount(host);
  return props;
}
beforeEach(() => {
  vi.clearAllMocks();
  state.designRevision = '9007199254740997';
  state.saveResults = [];
  state.confirmLeave.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getTableModel).mockResolvedValue(model());
  vi.mocked(api.saveTableModelDraft).mockResolvedValue(true);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('unified model editor', () => {
  it('拒绝将其他修订号的头信息与当前设计拼接保存', async () => {
    mount();
    await flush();
    state.designRevision = '9007199254740998';
    host.querySelector<HTMLButtonElement>('.save-draft')!.click();
    await flush();
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
    expect(api.getTableModel).toHaveBeenCalledTimes(1);
  });
  it('saves fields and type configuration together with the loaded revision, preserving large IDs and exact decimal strings', async () => {
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.save-draft')!.click();
    await flush();
    expect(api.saveTableModelDraft).toHaveBeenCalledTimes(1);
    expect(api.saveTableModelDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        modelId: '9007199254740993',
        expectedRevision: '9007199254740997',
        fields: [
          {
            fieldUid: 'stable-1',
            fieldName: 'amount',
            defaultValue: '9007199254740993.01',
          },
        ],
        typeConfig: { detail: { grainFields: ['amount'] } },
      }),
    );
    expect(api.getTableModel).toHaveBeenCalledTimes(2);
  });
  it('does not let completion of a previous model save refresh or overwrite a newly selected model', async () => {
    let complete!: (value: boolean) => void;
    vi.mocked(api.saveTableModelDraft).mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve;
      }),
    );
    const props = mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.save-draft')!.click();
    vi.mocked(api.getTableModel).mockResolvedValue(
      model('9007199254740999', 2),
    );
    props.modelId = '9007199254740999';
    await flush();
    complete(true);
    await flush();
    expect(api.getTableModel).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('模型9007199254740999');
  });
  it('does not refresh an unmounted editor when a save finishes', async () => {
    let complete!: (value: boolean) => void;
    vi.mocked(api.saveTableModelDraft).mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve;
      }),
    );
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.save-draft')!.click();
    app!.unmount();
    app = undefined;
    complete(true);
    await flush();
    expect(api.getTableModel).toHaveBeenCalledTimes(1);
  });
  it('发布提交模型编号与CAS，卸载后完成不得刷新编辑器', async () => {
    let complete!: () => void;
    const pending = new Promise<never>((resolve) => {
      complete = () => resolve(undefined as never);
    });
    vi.mocked(api.publishTableModel).mockReturnValueOnce(pending);
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.publish')!.click();
    expect(api.publishTableModel).toHaveBeenCalledWith(
      '9007199254740993',
      state.designRevision,
    );
    app!.unmount();
    app = undefined;
    complete();
    await flush();
    expect(api.getTableModel).toHaveBeenCalledTimes(1);
  });

  it('接口拒绝保存时向当前设计编辑器返回false，保留其未保存状态', async () => {
    vi.mocked(api.saveTableModelDraft).mockResolvedValueOnce(false);
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.save-draft')!.click();
    await flush();
    expect(state.saveResults).toEqual([false]);
    expect(api.getTableModel).toHaveBeenCalledTimes(1);
  });

  it('当前设计拒绝离开时不能切换到基本信息编辑', async () => {
    state.confirmLeave.mockResolvedValueOnce(false);
    mount();
    await flush();
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (item) => item.textContent?.trim() === '基本信息',
    )!;
    button.click();
    await flush();
    expect(state.confirmLeave).toHaveBeenCalledOnce();
    expect(state.openHeader).not.toHaveBeenCalled();
    button.click();
    await flush();
    expect(state.openHeader).toHaveBeenCalledWith(
      'FORWARD',
      expect.objectContaining({ id: '9007199254740993' }),
    );
  });

  it.each(['DRAFT', 'PUBLISHED', 'DISCARDED'])(
    '%s 按当前模型状态控制设计只读',
    async (status) => {
      vi.mocked(api.getTableModel).mockResolvedValueOnce({
        ...model(),
        status,
      });
      mount();
      await flush();
      const save = host.querySelector<HTMLButtonElement>('.save-draft')!;
      expect(save.disabled).toBe(status === 'DISCARDED');
      save.click();
      await flush();
      expect(api.saveTableModelDraft).toHaveBeenCalledTimes(status === 'DISCARDED' ? 0 : 1);
    },
  );
});
