/* eslint-disable vue/one-component-per-file -- 隔离通用控件，验证真实向导校验、字段提交和会话切换。 */
import type { App } from 'vue';

import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createBatchFile,
  getBatchFile,
  getBatchFileOptions,
  updateBatchFile,
} from '#/api/dataforge/batchfile';

import Wizard from './wizard.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'actor-a', refreshToken: '', accessCodes: ['all'] },
  route: { name: 'DataforgeBatchFileEdit', params: { id: '9007199254740993' } },
  allowed: true,
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: [] }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: () =>
      harness.allowed && harness.access.accessCodes.length > 0,
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('./modules/system-file-picker.vue', () => ({ default: () => null }));
vi.mock('#/api/dataforge/batchfile', () => ({
  getBatchFile: vi.fn(),
  getBatchFileOptions: vi.fn(),
  createBatchFile: vi.fn(),
  updateBatchFile: vi.fn(async () => true),
  deleteBatchFileUpload: vi.fn(async () => true),
  importSystemFile: vi.fn(),
  uploadBatchFile: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const modal = Object.assign(
    defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('div', slots.default?.()) : null,
    }),
    { confirm: vi.fn((data: { onOk: () => unknown }) => data.onOk()) },
  );
  const input = defineComponent({
    props: { value: { type: [String, Number], default: '' } },
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: defineComponent({
      props: { title: { type: String, default: '' } },
      setup: (props) => () => h('div', { role: 'alert' }, props.title),
    }),
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Checkbox: box,
    Input: input,
    Modal: modal,
    Radio: box,
    RadioGroup: box,
    Select: box,
    Spin: box,
    TextArea: input,
    message: { success: vi.fn() },
  };
});

const options: Api.Options = {
  databases: [{ id: '7', name: '目标数据库', productId: 'MYSQL' }],
  users: [{ id: '8', nickname: '负责人', mobile: '示例电话' }],
  limits: {
    maxFiles: 20,
    maxFileBytes: 20 * 1024 * 1024,
    maxTotalBytes: 100 * 1024 * 1024,
    maxRows: 100_000,
    maxColumns: 256,
  },
  fieldTypes: ['string', 'int'],
};
const detail: Api.Detail = {
  id: '9007199254740993',
  revision: '9007199254740995',
  taskName: '导入任务',
  responsibleUserId: '8',
  responsibleName: '负责人',
  responsiblePhone: '示例电话',
  targetDatabaseId: '7',
  targetDatabaseName: '目标数据库',
  description: '',
  remark: '',
  online: false,
  fileCount: 1,
  targetCount: 1,
  lastStatus: 'NOT_EXECUTED',
  createTime: 1,
  updateTime: 1,
  creator: '创建人',
  successFiles: 0,
  failedFiles: 0,
  files: [
    {
      id: '9007199254740997',
      name: '文件.csv',
      type: 'csv',
      size: 10,
      sha256: 'test',
      parseStatus: 'PARSED',
      rowCount: 1,
      source: 'LOCAL',
      fields: [
        { sourceIndex: 0, name: '删除列', type: 'string' },
        { sourceIndex: 2, name: '保留列', type: 'string' },
      ],
    },
  ],
  targets: [{ name: 'import_001', fileIds: ['9007199254740997'] }],
};
let app: App | undefined;
let host: HTMLDivElement;
async function settle() {
  for (let index = 0; index < 10; index++) await nextTick();
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (node) => node.textContent?.trim() === label,
  );
  if (!result) throw new Error(`缺少按钮 ${label}`);
  return result;
}
async function mount() {
  app = createApp(Wizard);
  app.mount(host);
  await settle();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.allowed = true;
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['all'],
  });
  harness.route = reactive({
    name: 'DataforgeBatchFileEdit',
    params: { id: '9007199254740993' },
  });
  vi.mocked(getBatchFile).mockResolvedValue(JSON.parse(JSON.stringify(detail)));
  vi.mocked(getBatchFileOptions).mockResolvedValue(options);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('批量文件入库真实向导', () => {
  it('逐步确认后提交配置，删除字段仍保持源列号与无损 ID', async () => {
    await mount();
    button('下一步').click();
    await settle();
    button('下一步').click();
    await settle();
    button('删除').click();
    await settle();
    button('下一步').click();
    await settle();
    expect(updateBatchFile).not.toHaveBeenCalled();
    button('确定并退出').click();
    await settle();
    expect(updateBatchFile).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        revision: '9007199254740995',
        files: [
          {
            id: '9007199254740997',
            fields: [{ sourceIndex: 2, name: '保留列', type: 'string' }],
          },
        ],
        targets: detail.targets,
      }),
    );
    expect(harness.push).toHaveBeenCalledWith('/develop/integrate/batch-file');
  });
  it('空名称停留在基本信息，不发出保存请求', async () => {
    await mount();
    const name = host.querySelector<HTMLInputElement>(
      '[aria-label="任务名称"]',
    )!;
    name.value = ' ';
    name.dispatchEvent(new Event('input'));
    await settle();
    button('下一步').click();
    await settle();
    expect(host.textContent).toContain('请输入任务名称');
    expect(host.textContent).toContain('基础信息');
    expect(updateBatchFile).not.toHaveBeenCalled();
    expect(createBatchFile).not.toHaveBeenCalled();
  });
  it('上线任务不可继续编辑', async () => {
    vi.mocked(getBatchFile).mockResolvedValue({ ...detail, online: true });
    await mount();
    expect(button('下一步').disabled).toBe(true);
    expect(host.textContent).toContain('不可修改配置');
    expect(updateBatchFile).not.toHaveBeenCalled();
  });
  it('失去权限时清空旧文件与表单，不再请求新详情', async () => {
    await mount();
    harness.allowed = false;
    harness.access.accessCodes = [];
    await settle();
    expect(host.textContent).toContain('没有配置该任务的权限');
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')!.value,
    ).toBe('');
    expect(getBatchFile).toHaveBeenCalledTimes(1);
  });
  it('切换会话后旧详情和选项响应不得覆盖新会话', async () => {
    let release: (value: Api.Detail) => void = () => {};
    vi.mocked(getBatchFile).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    await mount();
    vi.mocked(getBatchFile).mockResolvedValue({
      ...detail,
      taskName: '新会话任务',
    });
    harness.access.accessToken = 'actor-b';
    await settle();
    release({ ...detail, taskName: '旧会话任务' });
    await settle();
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')!.value,
    ).toBe('新会话任务');
  });
});
