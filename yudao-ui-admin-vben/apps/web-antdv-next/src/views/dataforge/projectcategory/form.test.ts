/* eslint-disable vue/one-component-per-file -- 隔离控件以验证弹窗的身份、并发和提交契约。 */
import type { App, PropType } from 'vue';

import type { ParentOption } from './model';

import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryForm from './form.vue';

interface Model {
  parentId: string;
  name: string;
  status: number;
  sort: null | number;
  description: string;
}

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
  model: undefined as Model | undefined,
  parents: [] as ParentOption[],
  list: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  validate: vi.fn(),
  saved: vi.fn(),
  success: vi.fn(),
}));

vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessToken: 'one',
    refreshToken: '',
    accessCodes: ['integration', 'dev'].flatMap((kind) =>
      ['query', 'create', 'update'].map(
        (action) => `dataforge:${kind}-category:${action}`,
      ),
    ),
  });
  return {
    useAccessStore: () => harness.access,
    useUserStore: () => ({ userInfo: { userId: '7' }, userRoles: ['editor'] }),
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access!.accessCodes.includes(code)),
  }),
}));
vi.mock('#/api/dataforge/projectcategory', () => ({
  getProjectCategoryList: harness.list,
  getProjectCategory: harness.get,
  createProjectCategory: harness.create,
  updateProjectCategory: harness.update,
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Modal: defineComponent({
      props: {
        open: Boolean,
        title: { type: String, default: '' },
        confirmLoading: Boolean,
        okButtonProps: { type: Object, default: () => ({}) },
      },
      emits: ['ok', 'cancel'],
      setup:
        (props, { slots, emit }) =>
        () =>
          props.open
            ? h('section', { role: 'dialog' }, [
                h('h2', props.title),
                slots.default?.(),
                h(
                  'button',
                  { 'data-action': 'cancel', onClick: () => emit('cancel') },
                  '取消',
                ),
                h(
                  'button',
                  {
                    'data-action': 'save',
                    'data-saving': String(props.confirmLoading),
                    disabled: props.okButtonProps.disabled,
                    onClick: () => emit('ok'),
                  },
                  '确定',
                ),
              ])
            : null,
    }),
    Form: defineComponent({
      props: { model: { type: Object as PropType<Model>, required: true } },
      setup: (props, { slots, expose }) => {
        harness.model = props.model;
        expose({ validate: harness.validate, clearValidate: vi.fn() });
        return () => h('div', slots.default?.());
      },
    }),
    TreeSelect: defineComponent({
      props: {
        treeData: {
          type: Array as PropType<ParentOption[]>,
          default: () => [],
        },
      },
      setup: (props) => () => {
        harness.parents = props.treeData;
        return h('div');
      },
    }),
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', { role: 'alert' }, props.message),
    }),
    FormItem: Container,
    Input: Container,
    InputNumber: Container,
    RadioGroup: Container,
    Spin: Container,
    TextArea: Container,
    message: { success: harness.success },
  };
});

let app: App;
let host: HTMLDivElement;
let instance: {
  close: () => void;
  open: (data?: { id?: Api.Id; parentId?: Api.Id }) => Promise<void>;
};
const parentId = '9007199254740993';
const editId = '9007199254740995';
const detail: Api.Category = {
  taskCount: 0,
  childCount: 0,
  id: editId,
  parentId,
  name: '服务器最新类目',
  description: '最新说明',
  status: 1,
  sort: 3,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function saveButton() {
  return host.querySelector<HTMLButtonElement>('[data-action="save"]')!;
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(harness.access!, { accessToken: 'one', refreshToken: '' });
  harness.list.mockResolvedValue([]);
  harness.get.mockResolvedValue(detail);
  harness.create.mockResolvedValue('9007199254740997');
  harness.update.mockResolvedValue(true);
  harness.validate.mockResolvedValue({});
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

function mount(kind: Api.Kind = 'integration') {
  app = createApp(CategoryForm, { kind, onSaved: harness.saved });
  instance = app.mount(host) as unknown as typeof instance;
}

describe('项目类目表单', () => {
  it.each(['integration', 'development'] as const)(
    '%s 新建下级默认启用和零排序，父ID无损且提交仅含白名单',
    async (kind) => {
      mount(kind);
      await instance.open({ parentId });
      expect(harness.model).toMatchObject({
        parentId,
        name: '',
        status: 0,
        sort: 0,
        description: '',
      });
      expect(host.textContent).toContain(
        kind === 'integration' ? '添加数据集成类目' : '添加数据开发类目',
      );
      Object.assign(harness.model!, {
        name: '  新类目  ',
        description: '说明',
        creatorName: '不应提交',
      });
      const pending = deferred<string>();
      harness.create.mockReturnValueOnce(pending.promise);
      saveButton().click();
      await flush();
      saveButton().click();
      host.querySelector<HTMLButtonElement>('[data-action="cancel"]')!.click();
      await flush();
      expect(harness.create).toHaveBeenCalledExactlyOnceWith({
        kind,
        id: undefined,
        parentId,
        name: '新类目',
        status: 0,
        sort: 0,
        description: '说明',
      });
      expect(host.querySelector('[role="dialog"]')).not.toBeNull();
      pending.resolve('9007199254740997');
      await flush();
      expect(harness.saved).toHaveBeenCalledOnce();
      expect(host.querySelector('[role="dialog"]')).toBeNull();
    },
  );

  it('编辑按ID获取最新详情，并从父级选项中排除自身和所有下级', async () => {
    mount();
    harness.list.mockResolvedValue([
      { ...detail, id: parentId, parentId: '0', name: '上级' },
      detail,
      { ...detail, id: '9007199254740997', parentId: editId, name: '下级' },
    ]);
    await instance.open({ id: editId });
    await flush();
    expect(harness.get).toHaveBeenCalledExactlyOnceWith('integration', editId);
    expect(harness.model).toMatchObject({
      parentId,
      name: detail.name,
      status: 1,
      sort: 3,
    });
    expect(harness.parents[0]?.children).toEqual([
      { value: parentId, title: '上级', children: undefined },
    ]);
    saveButton().click();
    await flush();
    expect(harness.update).toHaveBeenCalledWith({
      kind: 'integration',
      id: editId,
      parentId,
      name: detail.name,
      description: detail.description,
      status: 1,
      sort: 3,
    });
  });

  it('后打开对象的详情不能被前一次慢请求覆盖', async () => {
    mount();
    const old = deferred<Api.Category>();
    harness.get.mockReturnValueOnce(old.promise);
    const first = instance.open({ id: editId });
    const nextId = '9007199254740999';
    harness.get.mockResolvedValueOnce({
      ...detail,
      id: nextId,
      name: '新对象',
    });
    await instance.open({ id: nextId });
    old.resolve(detail);
    await first;
    expect(harness.model!.name).toBe('新对象');
    saveButton().click();
    await flush();
    expect(harness.update).toHaveBeenCalledWith(
      expect.objectContaining({ id: nextId, name: '新对象' }),
    );
  });

  it('身份切换关闭弹窗，旧加载完成后不能继续展示或提交', async () => {
    mount();
    const old = deferred<Api.Category[]>();
    harness.list.mockReturnValueOnce(old.promise);
    const pending = instance.open({ parentId });
    harness.access!.accessToken = 'two';
    await flush();
    old.resolve([]);
    await pending;
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(harness.create).not.toHaveBeenCalled();
    expect(harness.update).not.toHaveBeenCalled();
  });

  it('保存失败保留内容并解锁，可重试成功', async () => {
    mount();
    await instance.open();
    harness.model!.name = '保留修改';
    harness.create.mockRejectedValueOnce(new Error('同名类目'));
    saveButton().click();
    await flush();
    expect(harness.model!.name).toBe('保留修改');
    expect(saveButton().dataset.saving).toBe('false');
    expect(harness.saved).not.toHaveBeenCalled();
    saveButton().click();
    await flush();
    expect(harness.create).toHaveBeenCalledTimes(2);
    expect(harness.saved).toHaveBeenCalledOnce();
  });

  it('已被任务引用的类目更名先确认，取消保留表单且不提交', async () => {
    mount();
    harness.get.mockResolvedValueOnce({ ...detail, taskCount: 3 });
    await instance.open({ id: editId });
    harness.model!.name = '变更后的名称';
    saveButton().click();
    await flush();
    expect(host.textContent).toContain('该类目已被3个任务使用，修改名称后');
    expect(harness.update).not.toHaveBeenCalled();
    const cancels = host.querySelectorAll<HTMLButtonElement>(
      '[data-action="cancel"]',
    );
    cancels[cancels.length - 1]!.click();
    await flush();
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(harness.model!.name).toBe('变更后的名称');
    expect(harness.update).not.toHaveBeenCalled();
  });

  it('修改上级类目提示任务归属路径变化，确认后才提交', async () => {
    mount();
    await instance.open({ id: editId });
    harness.model!.parentId = '0';
    saveButton().click();
    await flush();
    expect(host.textContent).toContain(
      '修改上级类目会影响该类目下任务的归属路径，请确认。',
    );
    expect(harness.update).not.toHaveBeenCalled();
    const confirmations = host.querySelectorAll<HTMLButtonElement>(
      '[data-action="save"]',
    );
    confirmations[confirmations.length - 1]!.click();
    await flush();
    expect(harness.update).toHaveBeenCalledWith(
      expect.objectContaining({ id: editId, parentId: '0' }),
    );
    expect(harness.saved).toHaveBeenCalledOnce();
  });
});
