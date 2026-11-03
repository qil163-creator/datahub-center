import type { App } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/masterdata';

import Detail from './model-detail.vue';

interface ModalOptions {
  cancelText: string;
  onOpenChange: (open: boolean) => Promise<void>;
  showConfirmButton: boolean;
}

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  data: {} as { id?: GovernanceMasterDataApi.Id },
  errors: [] as unknown[],
  options: undefined as ModalOptions | undefined,
  state: {} as { loading?: boolean },
  formatDateTime: vi.fn((value: unknown) => `时间戳：${value}`),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: harness.formatDateTime }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      {
        getData: () => harness.data,
        close: () => options.onOpenChange(false),
        setState: (value: { loading?: boolean }) => {
          Object.assign(harness.state, value);
        },
      },
    ];
  },
}));
vi.mock('#/api/governance/masterdata', () => ({
  getMasterModel: vi.fn(),
  createMasterModel: vi.fn(),
  updateMasterModel: vi.fn(),
  releaseMasterModel: vi.fn(),
}));
vi.mock('antdv-next', async () => {
  const actual =
    await vi.importActual<typeof import('antdv-next')>('antdv-next');
  return {
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', { role: 'alert' }, props.message),
    }),
    Descriptions: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('dl', slots.default?.()),
    }),
    DescriptionsItem: defineComponent({
      props: { label: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [h('dt', props.label), h('dd', slots.default?.())]),
    }),
    Tag: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('span', slots.default?.()),
    }),
    Table: actual.Table,
  };
});

let app: App | undefined;
let host: HTMLDivElement;
const model: GovernanceMasterDataApi.Model = {
  id: '9007199254740995',
  tableCn: '人员模型',
  tableName: 'md_person',
  domainName: '组织人员',
  databaseName: '主数据源',
  description: '人员模型说明',
  status: 1,
  version: 2,
  createTime: 1_791_939_600_000,
  columns: [
    {
      id: '9007199254740997',
      name: 'person_id',
      commentStr: '人员编号',
      fieldType: 5,
      fieldLength: null,
      fieldScale: null,
      pk: 1,
      nullable: 0,
    },
    {
      name: 'amount',
      commentStr: '金额',
      fieldType: 6,
      fieldLength: 20,
      fieldScale: 0,
      pk: 0,
      nullable: 1,
    },
  ],
};

function deferred() {
  let resolve!: (value: GovernanceMasterDataApi.Model) => void;
  const promise = new Promise<GovernanceMasterDataApi.Model>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1' });
  harness.data = { id: model.id };
  harness.state = {};
  harness.errors = [];
  vi.mocked(api.getMasterModel).mockResolvedValue(model);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('主数据模型只读详情', () => {
  it('仅按无损编号读取详情，展示元信息与字段定义', async () => {
    const snapshot = structuredClone(model);
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(api.getMasterModel).toHaveBeenCalledWith('9007199254740995');
    expect(harness.options).toMatchObject({
      showConfirmButton: false,
      cancelText: '关闭',
    });
    for (const text of [
      '人员模型',
      'md_person',
      '组织人员',
      '主数据源',
      '人员模型说明',
      '已发布',
      'V2',
    ]) {
      expect(host.textContent).toContain(text);
    }
    expect(harness.formatDateTime).toHaveBeenCalledWith(model.createTime);
    expect(
      host.querySelector<HTMLTableRowElement>('tbody tr[data-row-key]')?.dataset
        .rowKey,
    ).toBe('9007199254740997');
    expect(
      [...host.querySelectorAll('tbody tr[data-row-key]')].map(
        (row) => row.textContent,
      ),
    ).toEqual(['person_id人员编号整数--是否', 'amount金额精确小数200否是']);
    expect(host.querySelector('input,select,textarea,button')).toBeNull();
    expect(api.createMasterModel).not.toHaveBeenCalled();
    expect(api.updateMasterModel).not.toHaveBeenCalled();
    expect(api.releaseMasterModel).not.toHaveBeenCalled();
    expect(model).toEqual(snapshot);
    expect(harness.state.loading).toBe(false);
    expect(harness.errors).toEqual([]);
  });

  it('旧请求不能覆盖新模型或提前关闭新请求的 loading', async () => {
    const old = deferred();
    const latest = deferred();
    vi.mocked(api.getMasterModel)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = harness.options!.onOpenChange(true);
    harness.data = { id: '9007199254740996' };
    const second = harness.options!.onOpenChange(true);
    old.resolve({ tableCn: '旧模型' });
    await first;
    await nextTick();
    expect(host.textContent).not.toContain('旧模型');
    expect(harness.state.loading).toBe(true);
    latest.resolve({ tableCn: '最新模型' });
    await second;
    await nextTick();
    expect(host.textContent).toContain('最新模型');
    expect(harness.state.loading).toBe(false);
    expect(harness.errors).toEqual([]);
  });

  it.each(['close', 'session', 'unmount'] as const)(
    '%s 后忽略未完成的详情响应',
    async (change) => {
      const request = deferred();
      vi.mocked(api.getMasterModel).mockReturnValueOnce(request.promise);
      const pending = harness.options!.onOpenChange(true);
      if (change === 'close') await harness.options!.onOpenChange(false);
      if (change === 'session') {
        harness.access.refreshToken = '2';
        await nextTick();
      }
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      request.resolve(model);
      await pending;
      await nextTick();
      expect(host.textContent).not.toContain('人员模型');
      expect(harness.state.loading).toBe(change === 'unmount');
      expect(harness.errors).toEqual([]);
    },
  );

  it('加载失败可见，重新打开清除错误并重新获取详情', async () => {
    vi.mocked(api.getMasterModel).mockRejectedValueOnce(new Error('读取失败'));
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '详情加载失败',
    );
    expect(harness.state.loading).toBe(false);
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('人员模型');
    expect(harness.errors).toEqual([]);
  });
});
