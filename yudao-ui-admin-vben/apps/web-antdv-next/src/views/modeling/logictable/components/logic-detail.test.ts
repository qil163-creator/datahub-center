import type { App } from 'vue';

import type { TableModelApi } from '#/api/modeling/tablemodel';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/tablemodel';

import LogicDetail from './logic-detail.vue';

const shared = vi.hoisted(() => ({
  canSave: true,
  close: vi.fn(),
  open: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.includes('modeling:model:save') ? shared.canSave : true,
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: '' }),
}));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [],
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-10-22 10:48' }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('#/api/modeling/indicator-model', () => ({
  getIndicatorPage: vi.fn(async () => ({ list: [] })),
}));
vi.mock('#/api/modeling/business-condition', () => ({
  getBusinessConditionPage: vi.fn(async () => ({ list: [] })),
}));
vi.mock('../options', () => ({
  loadLogicOptionPages: async (
    fetchPage: (params: {
      pageNo: number;
      pageSize: number;
    }) => Promise<{ list: unknown[] }>,
  ) => {
    const page = await fetchPage({ pageNo: 1, pageSize: 100 });
    return page.list;
  },
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModel: vi.fn(),
  getTableModelDesign: vi.fn(),
  modelSaveData: (model: TableModelApi.Model) => ({
    id: model.id,
    code: model.code,
  }),
  saveTableModelDraft: vi.fn(async () => true),
  publishTableModel: vi.fn(),
}));
vi.mock('./field-modal.vue', () => ({
  default: defineComponent({
    emits: ['submit'],
    setup(_, { emit, expose }) {
      expose({ open: shared.open, close: shared.close });
      return () =>
        h(
          'button',
          {
            'data-test-submit': true,
            onClick: () =>
              emit('submit', {
                fieldUid: 'new-field',
                fieldName: 'new_name',
                cnName: '新增字段',
                dataType: 'VARCHAR',
                fieldCategory: 'ATTRIBUTE',
              }),
          },
          '保存测试字段',
        );
    },
  }),
}));
vi.mock('#/components/list-pagination/index.vue', () => ({ default: () => null }));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: plain,
    Checkbox: plain,
    ConfigProvider: plain,
    Input: plain,
    message: { success: vi.fn(), warning: vi.fn() },
    Modal: { confirm: vi.fn() },
    Popover: plain,
    Spin: plain,
    theme: { defaultAlgorithm: undefined },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
const id = '9007199254740993';
const revision = '9007199254740997';
function model(modelId = id): TableModelApi.Model {
  return {
    id: modelId,
    code: `DIM_${modelId}`,
    name: `模型${modelId}`,
    tableType: 'DIMENSION',
    revision,
    layoutRevision: 1,
    enabled: 0,
  };
}
function design(modelId = id): TableModelApi.Design {
  return {
    modelId,
    revision,
    status: 'DRAFT',
    fields: [{ fieldUid: 'existing', fieldName: 'id', cnName: '编号', dataType: 'BIGINT', pkFlag: true, nullableFlag: false }],
  };
}
async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  const props = reactive({ modelId: id });
  app = createApp(
    defineComponent({ setup: () => () => h(LogicDetail, props) }),
  );
  app.mount(host);
  await flush();
  return props;
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.saveTableModelDraft).mockReset().mockResolvedValue(true);
  shared.canSave = true;
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getTableModel).mockImplementation(async (modelId) =>
    model(String(modelId)),
  );
  vi.mocked(api.getTableModelDesign).mockImplementation(async (modelId) => design(String(modelId)));
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('逻辑模型详情', () => {
  it('字段保存成功先刷新头信息，第二次编辑使用下一份快照修订号', async () => {
    let serverRevision = revision;
    vi.mocked(api.getTableModel).mockImplementation(async () => ({ ...model(), revision: serverRevision }));
    vi.mocked(api.getTableModelDesign).mockImplementation(async (modelId) => ({ ...design(String(modelId)), revision: serverRevision }));
    vi.mocked(api.saveTableModelDraft).mockImplementation(async (data) => {
      if (data.expectedRevision !== serverRevision) return false;
      serverRevision = serverRevision === revision ? '9007199254740998' : '9007199254740999';
      return true;
    });
    await mount();
    host.querySelector<HTMLButtonElement>('[data-test-submit]')!.click();
    await flush();
    host.querySelector<HTMLButtonElement>('[data-test-submit]')!.click();
    await flush();

    expect(api.saveTableModelDraft).toHaveBeenCalledTimes(2);
    expect(api.saveTableModelDraft).toHaveBeenNthCalledWith(1, expect.objectContaining({ expectedRevision: revision }));
    expect(api.saveTableModelDraft).toHaveBeenNthCalledWith(2, expect.objectContaining({ expectedRevision: '9007199254740998' }));
    expect(api.getTableModel).toHaveBeenCalledTimes(3);
    expect(shared.close).toHaveBeenCalledTimes(2);
  });

  it.each(['SOURCE', 'DIMENSION', 'DETAIL', 'SUMMARY', 'APPLICATION'] as const)(
    '%s 表详情版本页签仅显示占位，不提供发布或修订入口',
    async (tableType) => {
      vi.mocked(api.getTableModel).mockResolvedValue({ ...model(), tableType });
      await mount();
      expect(host.textContent).not.toContain('配置与发布');
      const tab = [...host.querySelectorAll('button')].find((button) =>
        button.textContent?.includes(tableType === 'SOURCE' ? '历史版本' : '版本与变更'),
      );
      expect(tab).toBeDefined();
      tab!.click();
      await flush();
      expect(host.textContent).toContain('暂未实现');
      expect(api.getTableModelDesign).toHaveBeenCalledTimes(1);
      expect(api.saveTableModelDraft).not.toHaveBeenCalled();
      expect(api.publishTableModel).not.toHaveBeenCalled();
    },
  );

  it('模型切换后迟到的字段保存不关闭新模型弹窗或刷新旧对象', async () => {
    let resolve!: (value: boolean) => void;
    vi.mocked(api.saveTableModelDraft).mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const props = await mount();
    host.querySelector<HTMLButtonElement>('[data-test-submit]')!.click();
    await flush();
    props.modelId = '9007199254741001';
    await flush();
    resolve(true);
    await flush();
    expect(shared.close).not.toHaveBeenCalled();
    expect(api.getTableModel).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('模型9007199254741001');
  });

  it('保存字段保留无损模型版本编号及乐观锁', async () => {
    await mount();
    host.querySelector<HTMLButtonElement>('[data-test-submit]')?.click();
    await flush();
    expect(api.saveTableModelDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        modelId: id,
        expectedRevision: revision,
        fields: [
          expect.objectContaining({ fieldUid: 'existing' }),
          expect.objectContaining({
            fieldUid: 'new-field',
            fieldName: 'new_name',
          }),
        ],
      }),
    );
    expect(shared.close).toHaveBeenCalledOnce();
  });

  it('已废弃模型拒绝字段写入', async () => {
    vi.mocked(api.getTableModelDesign).mockResolvedValue({ ...design(), status: 'DISCARDED', fields: [] });
    await mount();
    host.querySelector<HTMLButtonElement>('[data-test-submit]')?.click();
    await flush();
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
    const add = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('新增字段'),
    );
    expect(add?.disabled).toBe(true);
  });

  it('路由对象变化后丢弃先前详情请求的迟到响应', async () => {
    let resolve: (value: TableModelApi.Model) => void = () => {};
    vi.mocked(api.getTableModel).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const props = await mount();
    props.modelId = '9007199254741001';
    await flush();
    resolve(model(id));
    await flush();
    expect(host.textContent).toContain('模型9007199254741001');
    expect(host.textContent).not.toContain(`模型${id}`);
  });
});
