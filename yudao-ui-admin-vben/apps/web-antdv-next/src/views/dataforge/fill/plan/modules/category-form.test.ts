import type { App, SetupContext } from 'vue';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryForm from './category-form.vue';

interface ModalOptions {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => Promise<void>;
}

interface ParentNode {
  children?: ParentNode[];
  id: string;
}

interface FormOptions {
  schema: {
    componentProps?: (() => Record<string, unknown>) | Record<string, unknown>;
    fieldName: string;
  }[];
}

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  user: {} as { userInfo: { userId: string } },
  initialData: {} as { id?: number | string; parentId?: number | string },
  modalOptions: {} as ModalOptions,
  formOptions: {} as FormOptions,
  values: {} as DataforgeFillApi.PlanCategorySaveReq,
  getCategory: vi.fn(),
  getCategories: vi.fn(),
  createCategory: vi.fn(),
  updateCategory: vi.fn(),
  setValues: vi.fn(),
  validate: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
  close: vi.fn(),
  success: vi.fn(),
  saved: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    harness.modalOptions = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
      {
        getData: () => harness.initialData,
        lock: harness.lock,
        unlock: harness.unlock,
        close: async () => {
          harness.close();
          await options.onOpenChange(false);
        },
      },
    ];
  },
}));
vi.mock('#/adapter/form', () => {
  const rule = {
    int: () => rule,
    max: () => rule,
    min: () => rule,
    trim: () => rule,
  };
  return {
    z: { number: () => rule, string: () => rule },
    useVbenForm: (options: FormOptions) => {
      harness.formOptions = options;
      return [
        () => null,
        {
          reset: vi.fn(async () => {
            harness.values = {} as DataforgeFillApi.PlanCategorySaveReq;
          }),
          validate: harness.validate,
          getValues: async () => harness.values,
          setValues: async (values: DataforgeFillApi.PlanCategorySaveReq) => {
            harness.values = values;
            harness.setValues(values);
          },
        },
      ];
    },
  };
});
vi.mock('#/api/dataforge/fill', () => ({
  createPlanCategory: harness.createCategory,
  getPlanCategory: harness.getCategory,
  getPlanCategoryList: harness.getCategories,
  updatePlanCategory: harness.updateCategory,
}));
vi.mock('antdv-next', () => ({ message: { success: harness.success } }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));

const LARGE_ID = '9223372036854775807';
const CHILD_ID = '9223372036854775806';
const categories: DataforgeFillApi.PlanCategory[] = [
  { id: '8', parentId: 0, name: '教育', sort: 0 },
  { id: LARGE_ID, parentId: '8', name: '学生信息', sort: 0 },
  { id: CHILD_ID, parentId: LARGE_ID, name: '学生明细', sort: 0 },
  { id: '9', parentId: 0, name: '财务', sort: 1 },
];

let app: App | undefined;
let host: HTMLDivElement;

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function settle() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}

function parentKeys() {
  const field = harness.formOptions.schema.find(
    (item) => item.fieldName === 'parentId',
  )!;
  const props =
    typeof field.componentProps === 'function'
      ? field.componentProps()
      : field.componentProps!;
  const nodes = props.treeData as ParentNode[];
  const flatten = (items: ParentNode[]): string[] =>
    items.flatMap((item) => [item.id, ...flatten(item.children ?? [])]);
  return flatten(nodes);
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '1' } });
  harness.initialData = { id: LARGE_ID };
  harness.getCategories.mockResolvedValue(categories);
  harness.getCategory.mockResolvedValue(categories[1]);
  harness.validate.mockResolvedValue({ valid: true });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(CategoryForm, { onSuccess: harness.saved });
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('采集计划分类弹窗', () => {
  it('按无损编号重新获取详情，上级选项排除自身和下级分类', async () => {
    await harness.modalOptions.onOpenChange(true);
    expect(harness.getCategory).toHaveBeenCalledExactlyOnceWith(LARGE_ID);
    expect(parentKeys()).toEqual(['0', '8', '9']);
    expect(harness.values).toEqual({ parentId: '8', name: '学生信息', sort: 0 });

    await harness.modalOptions.onConfirm();
    expect(harness.updateCategory).toHaveBeenCalledExactlyOnceWith({
      id: LARGE_ID,
      parentId: '8',
      name: '学生信息',
      sort: 0,
    });
    expect(harness.createCategory).not.toHaveBeenCalled();
    expect(harness.saved).toHaveBeenCalledOnce();
    expect(harness.unlock).toHaveBeenCalled();
  });

  it('详情加载失败后关闭弹窗，不能继续提交空表单', async () => {
    harness.getCategory.mockRejectedValueOnce(new Error('详情加载失败'));
    await harness.modalOptions.onOpenChange(true);
    await harness.modalOptions.onConfirm();
    expect(harness.close).toHaveBeenCalledOnce();
    expect(harness.setValues).not.toHaveBeenCalled();
    expect(harness.validate).not.toHaveBeenCalled();
    expect(harness.createCategory).not.toHaveBeenCalled();
    expect(harness.updateCategory).not.toHaveBeenCalled();
  });

  it('切换登录会话后忽略旧详情，不覆盖新弹窗和上级选项', async () => {
    const oldLoad = deferred<DataforgeFillApi.PlanCategory>();
    harness.getCategory.mockReturnValueOnce(oldLoad.promise);
    const pending = harness.modalOptions.onOpenChange(true);
    await settle();
    harness.access.refreshToken = '2';
    await settle();
    expect(harness.close).toHaveBeenCalledOnce();

    harness.initialData = { parentId: '0' };
    harness.getCategories.mockResolvedValueOnce([
      { id: '10', parentId: 0, name: '新会话分类', sort: 0 },
    ]);
    await harness.modalOptions.onOpenChange(true);
    oldLoad.resolve(categories[1]!);
    await pending;
    expect(harness.setValues).toHaveBeenCalledOnce();
    expect(harness.values).toEqual({ parentId: '0', name: '', sort: 0 });
    expect(parentKeys()).toEqual(['0', '10']);
  });

  it('提交期间切换身份后不关闭新弹窗，也不报告旧提交成功', async () => {
    const save = deferred<boolean>();
    harness.updateCategory.mockReturnValueOnce(save.promise);
    await harness.modalOptions.onOpenChange(true);
    const pending = harness.modalOptions.onConfirm();
    await settle();
    expect(harness.updateCategory).toHaveBeenCalledOnce();

    harness.user.userInfo.userId = '2';
    await settle();
    harness.initialData = { parentId: '0' };
    await harness.modalOptions.onOpenChange(true);
    harness.close.mockClear();
    save.resolve(true);
    await pending;
    expect(harness.close).not.toHaveBeenCalled();
    expect(harness.saved).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
  });
});
