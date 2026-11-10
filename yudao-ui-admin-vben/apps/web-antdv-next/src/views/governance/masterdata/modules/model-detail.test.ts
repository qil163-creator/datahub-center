/* eslint-disable vue/one-component-per-file -- 隔离原生分页适配器，验证真实详情表格与页签。 */
import type { App, PropType } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/masterdata';

import Detail from './model-detail.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    loginExpired: boolean;
    refreshToken: string;
    userInfo?: { userId: string };
  },
  errors: [] as unknown[],
  props: {} as { modelId?: GovernanceMasterDataApi.Id },
  back: vi.fn(),
  formatDateTime: vi.fn((value: unknown) => `时间戳：${value}`),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.access,
}));
vi.mock('@vben/utils', () => ({ formatDateTime: harness.formatDateTime }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/governance/masterdata', () => ({
  getMasterModel: vi.fn(),
  createMasterModel: vi.fn(),
  updateMasterModel: vi.fn(),
  releaseMasterModel: vi.fn(),
}));
vi.mock('#/adapter/vxe-table', () => ({
  NativeVxePager: shallowRef(
    defineComponent({
      props: {
        currentPage: { type: Number, default: 1 },
        pageSize: { type: Number, default: 10 },
        total: { type: Number, default: 0 },
        pageSizes: { type: Array as PropType<number[]>, default: () => [] },
        layouts: { type: Array as PropType<string[]>, default: () => [] },
      },
      emits: ['page-change'],
      setup(props, { emit }) {
        const change = (currentPage: number, pageSize = props.pageSize) =>
          emit('page-change', { currentPage, pageSize });
        return () =>
          h(
            'div',
            {
              'data-native-pager': '',
              'data-layouts': props.layouts.join(','),
              'data-current': props.currentPage,
              'data-size': props.pageSize,
              'data-total': props.total,
            },
            [
              h('span', `共 ${props.total} 条记录`),
              h(
                'select',
                {
                  'aria-label': '每页条数',
                  value: props.pageSize,
                  onChange: (event: Event) =>
                    change(
                      props.currentPage,
                      Number((event.target as HTMLSelectElement).value),
                    ),
                },
                props.pageSizes.map((size) =>
                  h('option', { value: size }, `${size}条/页`),
                ),
              ),
              h(
                'button',
                {
                  'data-page': 'prev',
                  disabled: props.currentPage <= 1,
                  onClick: () => change(props.currentPage - 1),
                },
                '上一页',
              ),
              h(
                'button',
                {
                  'data-page': 'next',
                  disabled:
                    props.currentPage >=
                    Math.max(1, Math.ceil(props.total / props.pageSize)),
                  onClick: () => change(props.currentPage + 1),
                },
                '下一页',
              ),
            ],
          );
      },
    }),
  ),
}));

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

async function flush() {
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}

async function mount(modelId: GovernanceMasterDataApi.Id | undefined = model.id) {
  harness.props = reactive({ modelId });
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(Detail, { modelId: harness.props.modelId, onBack: harness.back }),
    }),
  );
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  await flush();
}

function rows() {
  return [...host.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row-key]')];
}

function pager() {
  const node = host.querySelector<HTMLElement>('[data-native-pager]');
  expect(node).not.toBeNull();
  return node!;
}

function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (node) => node.textContent?.replaceAll(/\s/gu, '') === text,
  );
}

async function selectTab(label: string) {
  const tab = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(
    (node) => node.textContent?.includes(label),
  );
  expect(tab).toBeDefined();
  tab!.click();
  await flush();
}

async function nextPage() {
  pager().querySelector<HTMLButtonElement>('[data-page="next"]')!.click();
  await flush();
}

async function selectPageSize(size: number) {
  const select = pager().querySelector<HTMLSelectElement>('select')!;
  select.value = String(size);
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}

function paginatedModel(): GovernanceMasterDataApi.Model {
  return {
    ...model,
    columns: Array.from({ length: 25 }, (_, index) => ({
      id: `9007199254750${String(index).padStart(3, '0')}`,
      name: `field_${String(index + 1).padStart(3, '0')}`,
      commentStr: `字段${index + 1}`,
      fieldType: 5,
    })),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['governance:master-model:query'],
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
    loginExpired: false,
    userInfo: { userId: '1' },
  });
  harness.errors = [];
  vi.mocked(api.getMasterModel).mockReset().mockResolvedValue(model);
  host = document.createElement('div');
  document.body.append(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  if (harness.errors.length > 0) {
    throw new Error('详情组件发生未捕获错误', { cause: harness.errors[0] });
  }
});

describe('主数据模型独立只读详情', () => {
  it('通过无损编号读取模型，默认字段定义页签展示真实字段且不提供写入操作', async () => {
    const snapshot = structuredClone(model);
    await mount();
    expect(api.getMasterModel).toHaveBeenCalledExactlyOnceWith(
      '9007199254740995',
    );
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent)
      .toContain('字段定义');
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(host.textContent).toContain('人员模型');
    expect(rows()[0]?.dataset.rowKey).toBe('9007199254740997');
    expect(rows().map((row) => row.textContent)).toEqual([
      'person_id人员编号整数--是否',
      'amount金额精确小数200否是',
    ]);
    expect(host.querySelector('input,textarea')).toBeNull();
    for (const action of ['保存', '编辑', '删除', '发布', '新增']) {
      expect(button(action)).toBeUndefined();
    }
    expect(api.createMasterModel).not.toHaveBeenCalled();
    expect(api.updateMasterModel).not.toHaveBeenCalled();
    expect(api.releaseMasterModel).not.toHaveBeenCalled();
    expect(model).toEqual(snapshot);
  });

  it('详细信息页签保留模型元信息和毫秒时间，返回发出页面导航事件', async () => {
    await mount();
    await selectTab('详细信息');
    for (const text of [
      '人员模型',
      'md_person',
      '组织人员',
      '主数据源',
      '人员模型说明',
      '已发布',
      'V2',
      `时间戳：${model.createTime}`,
    ]) {
      expect(host.textContent).toContain(text);
    }
    expect(harness.formatDateTime).toHaveBeenCalledWith(model.createTime);
    expect(button('返回')).toBeDefined();
    button('返回')!.click();
    await flush();
    expect(harness.back).toHaveBeenCalledOnce();
  });

  it('字段表复用项目分页，翻页与容量变化展示对应字段', async () => {
    vi.mocked(api.getMasterModel).mockResolvedValue(paginatedModel());
    await mount();
    expect(pager().classList.contains('list-pagination')).toBe(true);
    expect(pager().dataset.layouts).toBe(
      'Total,Sizes,Home,PrevJump,PrevPage,Number,NextPage,NextJump,End',
    );
    expect(host.querySelector('.ant-pagination')).toBeNull();
    expect(pager().dataset.total).toBe('25');
    expect(pager().dataset.size).toBe('10');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.textContent).toContain('field_001');
    await nextPage();
    expect(pager().dataset.current).toBe('2');
    expect(rows()[0]?.textContent).toContain('field_011');
    await selectPageSize(20);
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.size).toBe('20');
    expect(rows()).toHaveLength(20);
    expect(rows()[0]?.textContent).toContain('field_001');
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(rows()[0]?.textContent).toContain('field_021');
    expect(api.getMasterModel).toHaveBeenCalledTimes(1);
  });

  it('切换模型清除旧分页与页签，空字段仍展示零条分页', async () => {
    vi.mocked(api.getMasterModel).mockResolvedValueOnce(paginatedModel());
    await mount();
    await selectPageSize(20);
    await nextPage();
    await selectTab('详细信息');
    vi.mocked(api.getMasterModel).mockResolvedValueOnce({
      id: '9007199254740996',
      tableCn: '无字段模型',
      columns: [],
    });
    harness.props.modelId = '9007199254740996';
    await flush();
    expect(api.getMasterModel).toHaveBeenLastCalledWith('9007199254740996');
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent)
      .toContain('字段定义');
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.size).toBe('10');
    expect(pager().dataset.total).toBe('0');
    expect(rows()).toHaveLength(0);
    expect(
      pager().querySelector<HTMLButtonElement>('[data-page="prev"]')!.disabled,
    ).toBe(true);
    expect(
      pager().querySelector<HTMLButtonElement>('[data-page="next"]')!.disabled,
    ).toBe(true);
    expect(host.textContent).not.toContain('field_021');
    expect(host.textContent).toContain('无字段模型');
  });

  it('旧响应不能覆盖新模型或提前关闭新请求的加载状态', async () => {
    const old = deferred();
    const latest = deferred();
    vi.mocked(api.getMasterModel)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    await mount();
    harness.props.modelId = '9007199254740996';
    await flush();
    old.resolve({ ...model, tableCn: '旧模型' });
    await flush();
    expect(host.textContent).not.toContain('旧模型');
    expect(host.querySelector('[aria-busy="true"]')).not.toBeNull();
    latest.resolve({ ...model, id: '9007199254740996', tableCn: '最新模型' });
    await flush();
    expect(host.textContent).toContain('最新模型');
    expect(host.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it.each(['permission', 'logout', 'expired', 'session', 'user', 'unmount'] as const)(
    '%s 变化后不回写旧的未完成详情',
    async (change) => {
      const old = deferred();
      const latest = deferred();
      vi.mocked(api.getMasterModel)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(latest.promise);
      await mount();
      if (change === 'permission') harness.access.accessCodes = [];
      if (change === 'logout') {
        harness.access.accessToken = '';
        harness.access.userInfo = undefined;
      }
      if (change === 'expired') harness.access.loginExpired = true;
      if (change === 'session') harness.access.refreshToken = 'refresh-b';
      if (change === 'user') harness.access.userInfo = { userId: '2' };
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      await flush();
      old.resolve({ ...model, tableCn: '旧会话模型' });
      await flush();
      expect(host.textContent).not.toContain('旧会话模型');
      expect(rows()).toHaveLength(0);
    },
  );

  it('权限失效立即清除已展示模型，恢复查询权限后重新读取', async () => {
    await mount();
    expect(host.textContent).toContain('人员模型');
    harness.access.accessCodes = [];
    await flush();
    expect(host.textContent).not.toContain('人员模型');
    expect(rows()).toHaveLength(0);
    expect(api.getMasterModel).toHaveBeenCalledTimes(1);
    harness.access.accessCodes = ['governance:master-model:query'];
    await flush();
    expect(api.getMasterModel).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('人员模型');
  });

  it('同一 tick 切换会话后切回原会话也不会复活旧响应', async () => {
    const old = deferred();
    const second = deferred();
    const latest = deferred();
    vi.mocked(api.getMasterModel)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(latest.promise);
    await mount();
    harness.access.refreshToken = 'refresh-b';
    harness.access.refreshToken = 'refresh-a';
    old.resolve({ ...model, tableCn: '失效旧响应' });
    await flush();
    expect(host.textContent).not.toContain('失效旧响应');
    expect(host.querySelector('[aria-busy="true"]')).not.toBeNull();
    latest.resolve({ ...model, tableCn: '当前会话模型' });
    await flush();
    expect(host.textContent).toContain('当前会话模型');
  });

  it.each(['0', '-1', 'invalid', 9_007_199_254_740_996])(
    '非法或已丢失精度的编号 %s 不触发详情请求且保留返回',
    async (modelId) => {
      await mount(modelId);
      expect(api.getMasterModel).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('人员模型');
      expect(button('返回')).toBeDefined();
      button('返回')!.click();
      await flush();
      expect(harness.back).toHaveBeenCalledOnce();
    },
  );

  it('移除模型编号使未完成请求失效且不展示旧详情', async () => {
    const old = deferred();
    vi.mocked(api.getMasterModel).mockReturnValueOnce(old.promise);
    await mount();
    harness.props.modelId = undefined;
    await flush();
    old.resolve(model);
    await flush();
    expect(host.textContent).not.toContain('人员模型');
    expect(rows()).toHaveLength(0);
    expect(api.getMasterModel).toHaveBeenCalledTimes(1);
    expect(button('返回')).toBeDefined();
  });

  it('拒绝响应编号不一致的模型，重试可恢复且错误状态仍能返回', async () => {
    vi.mocked(api.getMasterModel).mockResolvedValueOnce({
      ...model,
      id: '9007199254740996',
      tableCn: '其他模型敏感信息',
    });
    await mount();
    expect(host.textContent).not.toContain('其他模型敏感信息');
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(button('返回')).toBeDefined();
    expect(button('重试')).toBeDefined();
    button('重试')!.click();
    await flush();
    expect(api.getMasterModel).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('人员模型');
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('加载失败不显示提示条并保留返回入口', async () => {
    vi.mocked(api.getMasterModel).mockRejectedValueOnce(new Error('读取失败'));
    await mount();
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('[aria-busy="true"]')).toBeNull();
    expect(button('返回')).toBeDefined();
    expect(button('重试')).toBeUndefined();
    button('返回')!.click();
    await flush();
    expect(harness.back).toHaveBeenCalledOnce();
  });
});
