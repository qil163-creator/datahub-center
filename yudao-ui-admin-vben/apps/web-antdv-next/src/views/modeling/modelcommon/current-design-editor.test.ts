/* eslint-disable vue/one-component-per-file -- 控件替身隔离渲染，验证真实当前设计编辑器的保存与异步边界。 */
import type { App } from 'vue';

import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CurrentDesignEditor from './current-design-editor.vue';

const state = vi.hoisted(() => ({
  access: { accessToken: 'session', refreshToken: '' },
  success: vi.fn(),
  warning: vi.fn(),
  confirm: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
vi.mock('./data', () => ({
  DATA_TYPE_OPTIONS: [],
  DORIS_AGG_TYPE_OPTIONS: [],
  TABLE_TYPE_OPTIONS: [],
  optionLabel: () => '',
}));
vi.mock('./standard-picker.vue', () => ({ default: () => null }));
vi.mock('./type-config-form.vue', () => ({ default: () => null }));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = defineComponent({
    props: {
      value: { type: [String, Number], default: undefined },
      disabled: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          disabled: props.disabled,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props, { slots }) => () => h('section', [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
    Checkbox: plain,
    Input: input,
    InputNumber: input,
    Modal: { confirm: state.confirm },
    Select: plain,
    Table: plain,
    Tabs: Object.assign(plain, { TabPane: plain }),
    Tag: plain,
    Tooltip: plain,
    message: { success: state.success, warning: state.warning },
  };
});

type Draft = {
  expectedRevision: number | string;
  fields: ModelingModelApi.ModelField[];
  typeConfig: ModelingModelApi.ModelTypeConfig | null;
};
type Editor = {
  confirmLeave: () => Promise<boolean>;
  dirty: boolean;
  reload: () => Promise<void>;
};
type Api = {
  getDesign: (modelId: number | string) => Promise<TableModelApi.Design>;
  publish: (
    modelId: number | string,
    expectedRevision: number | string,
  ) => Promise<boolean>;
  saveDraft: (data: Draft) => Promise<unknown>;
};
const modelId = '9007199254740993';
const otherModelId = '9007199254740994';
const originalRevision = '9007199254740997';
const laterRevision = '9007199254740998';
let app: App | undefined;
let host: HTMLDivElement;
let caught: unknown[];
let selection: { modelId: string; readonly: boolean };
let editor: Editor;

function design(
  revision: number | string = originalRevision,
  name = '初始字段',
  id = modelId,
  status = 'DRAFT',
): TableModelApi.Design {
  return {
    modelId: id,
    revision,
    status,
    fields: [
      {
        fieldUid: 'stable',
        fieldName: 'amount',
        cnName: name,
        dataType: 'BIGINT',
      },
    ],
    typeConfig: { detail: { grainDescription: '每笔支付一行' } },
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount(api: Api) {
  app = createApp({
    setup: () => () =>
      h(CurrentDesignEditor, {
        api,
        modelId: selection.modelId,
        readonly: selection.readonly,
        tableType: 'DETAIL',
        ref: (value) => {
          editor = value as unknown as Editor;
        },
      }),
  });
  app.config.errorHandler = (error) => caught.push(error);
  app.directive('access', {});
  app.mount(host);
  await flush();
}
function nameInput() {
  return host.querySelector<HTMLInputElement>('.manage-fields__row input')!;
}
function editName(name: string) {
  const input = nameInput();
  input.value = name;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function saveButton() {
  return [
    ...host.querySelectorAll<HTMLButtonElement>('.manage-toolbar button'),
  ].find((button) => button.textContent?.includes('保存设计'))!;
}
function publishButton() {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (button) => button.textContent?.trim() === '发布',
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'session', refreshToken: '' });
  selection = reactive({ modelId, readonly: false });
  caught = [];
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('当前设计保存与发布', () => {
  it('每次保存使用所加载设计的CAS，并原子提交稳定字段与类型配置', async () => {
    let serverRevision = originalRevision;
    let serverName = '初始字段';
    const saveDraft = vi.fn(async (data: Draft) => {
      if (data.expectedRevision !== serverRevision) return false;
      serverName = data.fields[0]!.cnName!;
      serverRevision = laterRevision;
      return true;
    });
    await mount({
      getDesign: async () => design(serverRevision, serverName),
      saveDraft,
      publish: vi.fn(),
    });
    editName('第一次编辑');
    await flush();
    expect(editor.dirty).toBe(true);
    saveButton().click();
    await flush();
    expect(editor.dirty).toBe(false);
    editName('第二次编辑');
    await flush();
    saveButton().click();
    await flush();
    expect(saveDraft).toHaveBeenNthCalledWith(1, {
      expectedRevision: originalRevision,
      fields: [
        expect.objectContaining({ fieldUid: 'stable', cnName: '第一次编辑' }),
      ],
      typeConfig: { detail: { grainDescription: '每笔支付一行' } },
    });
    expect(saveDraft).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedRevision: laterRevision }),
    );
    expect(serverName).toBe('第二次编辑');
    expect(state.success).toHaveBeenCalledTimes(2);
  });

  it('保存返回false时保留原CAS和dirty，不静默刷新或丢弃输入', async () => {
    const getDesign = vi.fn(async () => design());
    const saveDraft = vi.fn(async () => false);
    await mount({ getDesign, saveDraft, publish: vi.fn() });
    editName('尚未保存字段');
    await flush();
    saveButton().click();
    await flush();
    expect(editor.dirty).toBe(true);
    expect(nameInput().value).toBe('尚未保存字段');
    expect(saveButton().disabled).toBe(false);
    expect(getDesign).toHaveBeenCalledOnce();
    expect(state.success).not.toHaveBeenCalled();
    saveButton().click();
    await flush();
    expect(saveDraft).toHaveBeenLastCalledWith(
      expect.objectContaining({ expectedRevision: originalRevision }),
    );
    const leaving = editor.confirmLeave();
    expect(state.confirm).toHaveBeenCalledOnce();
    state.confirm.mock.calls[0]![0].onCancel();
    expect(await leaving).toBe(false);
  });

  it('并发冲突保留输入，显式重新加载后才使用新修订号', async () => {
    let serverRevision = originalRevision;
    const conflict = new Error('MODEL_DESIGN_REVISION_CONFLICT');
    const saveDraft = vi.fn(async (data: Draft) => {
      if (data.expectedRevision !== serverRevision) throw conflict;
      return true;
    });
    const getDesign = vi.fn(async () => design(serverRevision));
    await mount({ getDesign, saveDraft, publish: vi.fn() });
    editName('本地未保存字段');
    await flush();
    serverRevision = laterRevision;
    saveButton().click();
    await flush();
    expect(caught).toEqual([conflict]);
    expect(editor.dirty).toBe(true);
    expect(nameInput().value).toBe('本地未保存字段');
    expect(getDesign).toHaveBeenCalledOnce();
    expect(state.success).not.toHaveBeenCalled();
    await editor.reload();
    expect(editor.dirty).toBe(false);
    editName('重新加载后的编辑');
    await flush();
    saveButton().click();
    await flush();
    expect(saveDraft).toHaveBeenLastCalledWith(
      expect.objectContaining({ expectedRevision: laterRevision }),
    );
    expect(state.success).toHaveBeenCalledOnce();
  });

  it('本地字段未保存时拦截发布，不创建确认框或调用发布接口', async () => {
    const publish = vi.fn(async () => true);
    await mount({
      getDesign: async () => design(),
      saveDraft: vi.fn(),
      publish,
    });
    editName('未提交设计');
    await flush();
    publishButton().click();
    expect(state.warning).toHaveBeenCalledWith('请先保存当前设计，再发布');
    expect(state.confirm).not.toHaveBeenCalled();
    expect(publish).not.toHaveBeenCalled();
    expect(editor.dirty).toBe(true);
  });

  it('发布已保存设计时确认前不调用接口，确认后提交模型编号和当前CAS', async () => {
    const publish = vi.fn(async () => true);
    await mount({
      getDesign: async () => design(),
      saveDraft: vi.fn(),
      publish,
    });
    publishButton().click();
    expect(publish).not.toHaveBeenCalled();
    await state.confirm.mock.calls[0]![0].onOk();
    expect(publish).toHaveBeenCalledWith(modelId, originalRevision);
    expect(state.success).toHaveBeenCalledWith('发布成功');
  });

  it.each(['DRAFT', 'PUBLISHED'])(
    '%s 当前设计均允许继续编辑',
    async (status) => {
      const saveDraft = vi.fn(async () => true);
      await mount({
        getDesign: async () =>
          design(originalRevision, '字段', modelId, status),
        saveDraft,
        publish: vi.fn(),
      });
      expect(nameInput().disabled).toBe(false);
      editName('已修改字段');
      await flush();
      expect(editor.dirty).toBe(true);
      saveButton().click();
      await flush();
      expect(saveDraft).toHaveBeenCalledWith(
        expect.objectContaining({ expectedRevision: originalRevision }),
      );
    },
  );

  it('废弃模型只读，不允许字段保存或发布', async () => {
    const saveDraft = vi.fn(async () => true);
    const publish = vi.fn(async () => true);
    await mount({
      getDesign: async () =>
        design(originalRevision, '字段', modelId, 'DISCARDED'),
      saveDraft,
      publish,
    });
    expect(nameInput().disabled).toBe(true);
    expect(saveButton().disabled).toBe(true);
    expect(publishButton().disabled).toBe(true);
    saveButton().click();
    publishButton().click();
    expect(saveDraft).not.toHaveBeenCalled();
    expect(publish).not.toHaveBeenCalled();
    expect(editor.dirty).toBe(false);
  });

  it('缺少修订号时拒绝保存，不能猜测CAS', async () => {
    const current = design();
    Object.assign(current, { revision: undefined });
    const saveDraft = vi.fn(async () => true);
    await mount({
      getDesign: async () => current,
      saveDraft,
      publish: vi.fn(),
    });
    saveButton().click();
    await flush();
    expect(saveDraft).not.toHaveBeenCalled();
    expect(state.warning).toHaveBeenCalledWith(
      '当前设计缺少修订号，请刷新后重试',
    );
  });
});

describe('当前设计异步归属', () => {
  it('读取失败保持不可写并可显式重试，不回退到旧版本数据', async () => {
    const getDesign = vi.fn().mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(design());
    const saveDraft = vi.fn();
    await mount({ getDesign, saveDraft, publish: vi.fn() });
    expect(host.textContent).toContain('当前设计读取失败');
    expect(saveButton().disabled).toBe(true);
    [...host.querySelectorAll('button')].find((button) => button.textContent === '重试')!.click();
    await flush();
    expect(host.textContent).not.toContain('当前设计读取失败');
    expect(nameInput().value).toBe('初始字段');
    expect(saveButton().disabled).toBe(false);
    expect(getDesign).toHaveBeenCalledTimes(2);
    expect(saveDraft).not.toHaveBeenCalled();
  });
  it('模型切换后忽略旧设计的晚返回，字段与修订号始终来自同一模型', async () => {
    const old = deferred<TableModelApi.Design>();
    const getDesign = vi.fn((id: number | string) =>
      id === modelId
        ? old.promise
        : Promise.resolve(design(laterRevision, '新模型字段', otherModelId)),
    );
    const saveDraft = vi.fn(async () => false);
    await mount({ getDesign, saveDraft, publish: vi.fn() });
    selection.modelId = otherModelId;
    await flush();
    expect(nameInput().value).toBe('新模型字段');
    old.resolve(design(originalRevision, '迟到旧模型字段'));
    await flush();
    expect(nameInput().value).toBe('新模型字段');
    editName('新模型编辑');
    await flush();
    saveButton().click();
    await flush();
    expect(getDesign.mock.calls).toEqual([[modelId], [otherModelId]]);
    expect(saveDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        expectedRevision: laterRevision,
        fields: [expect.objectContaining({ cnName: '新模型编辑' })],
      }),
    );
  });

  it('模型切换后旧保存返回不得刷新新模型或提示成功', async () => {
    const saved = deferred<boolean>();
    const getDesign = vi.fn(async (id: number | string) =>
      design(
        id === modelId ? originalRevision : laterRevision,
        String(id),
        String(id),
      ),
    );
    const saveDraft = vi.fn(() => saved.promise);
    await mount({ getDesign, saveDraft, publish: vi.fn() });
    editName('旧模型提交');
    await flush();
    saveButton().click();
    await flush();
    selection.modelId = otherModelId;
    await flush();
    saved.resolve(true);
    await flush();
    expect(nameInput().value).toBe(otherModelId);
    expect(getDesign.mock.calls).toEqual([[modelId], [otherModelId]]);
    expect(state.success).not.toHaveBeenCalled();
    expect(editor.dirty).toBe(false);
    expect(saveButton().disabled).toBe(false);
  });
});
