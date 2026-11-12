import type { App, Component, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { message } from 'antdv-next';
import { afterEach, describe, expect, it, vi } from 'vitest';

import * as databaseTypeApi from '#/api/dataforge/databasetype';
import * as api from '#/api/supply/dataapi';

import ApiPage from './index.vue';
import Wizard from './wizard.vue';

const { query } = vi.hoisted(() => ({ query: vi.fn() }));

vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: vi.fn().mockResolvedValue([]),
}));
vi.mock('./modules/database-select.vue', () => ({
  default: defineComponent({
    props: {
      value: { type: [String, Number], default: undefined },
      databases: {
        type: Array as PropType<api.SupplyDataApiApi.DatabaseSimple[]>,
        default: () => [],
      },
      catalog: { type: Array, default: () => [] },
      loading: Boolean,
      error: { type: String, default: '' },
      disabled: Boolean,
    },
    emits: ['update:value', 'change', 'retry'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          { 'data-testid': 'database-select', 'data-loading': props.loading },
          [
            h('span', props.error),
            h(
              'span',
              { 'data-testid': 'database-value' },
              String(props.value ?? ''),
            ),
            ...props.databases.map(
              (database: api.SupplyDataApiApi.DatabaseSimple) =>
                h(
                  'button',
                  {
                    type: 'button',
                    onClick: () => {
                      emit('update:value', database.id);
                      emit('change', database.id, database);
                    },
                  },
                  database.name,
                ),
            ),
            h(
              'button',
              { type: 'button', onClick: () => emit('retry') },
              '重试数据源',
            ),
            h(
              'button',
              {
                type: 'button',
                onClick: () => {
                  emit('update:value', undefined);
                  emit('change', undefined);
                },
              },
              '清除数据源',
            ),
          ],
        ),
  }),
}));
vi.mock('#/components/etl-target-batch-picker/index.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
// 指标配置编辑器依赖建模接口，由 indicator-config 的单测覆盖。
vi.mock('./indicator-config-editor.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}));
vi.mock('./doc.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./modules/category-form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));

vi.mock('@vben/common-ui', () => {
  const hidden = defineComponent({ render: () => null });
  const api = { open: vi.fn(), close: vi.fn(), setData: vi.fn() };
  return {
    confirm: vi.fn(),
    Page: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('main', slots.default?.()),
    }),
    useVbenModal: () => [hidden, api],
    useVbenDrawer: () => [hidden, api],
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: {
        type: Array as PropType<{ label: string; onClick: () => void }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'div',
        props.actions.map((action) =>
          h('button', { onClick: action.onClick }, action.label),
        ),
      ),
  }),
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', { 'data-testid': 'api-list' }, slots['toolbar-tools']?.()),
    }),
    { query },
  ],
}));
vi.mock('#/locales', () => ({
  $t: (key: string) => (key === 'common.create' ? '新增' : key),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ render: () => null }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: '1', refreshToken: undefined }),
}));
vi.mock('@vben/utils', () => ({ handleTree: (value: unknown) => value }));
vi.mock('#/api/supply/apicategory', () => ({
  deleteApiCategory: vi.fn(),
  getApiCategoryList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/api/supply/dataapi', () => ({
  deleteDataApi: vi.fn(),
  getDataApi: vi.fn(),
  getDataApiPage: vi.fn(),
  getDataApiReleaseList: vi.fn(),
  getDataApiDatabaseList: vi.fn().mockResolvedValue([]),
  getDataApiTableColumns: vi.fn(),
  getDataApiTableList: vi.fn().mockResolvedValue([]),
  testDataApi: vi.fn(),
  createDataApi: vi.fn(),
  publishDataApi: vi.fn(),
  rollbackDataApi: vi.fn(),
  updateDataApi: vi.fn(),
  updateDataApiStatus: vi.fn(),
}));
vi.mock('./data', () => ({
  API_TYPE_OPTIONS: [],
  AUTH_TYPE_OPTIONS: [],
  CONFIG_TYPE_OPTIONS: [
    { label: '数据表', value: 'TABLE' },
    { label: 'SQL 脚本', value: 'SQL' },
    { label: '接口转发', value: 'FORWARD' },
    { label: '受控 ETL 批次', value: 'ETL_BATCH' },
  ],
  FIELD_MODEL_OPTIONS: [],
  FIELD_MODEL_RULE_PLACEHOLDER: {},
  IP_RULE_TYPE_OPTIONS: [],
  METHOD_OPTIONS: [],
  PARAM_TYPE_OPTIONS: [],
  RESULT_FORMAT_OPTIONS: [],
  optionColor: vi.fn(),
  optionLabel: vi.fn(),
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const input = defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const choice = defineComponent({
    props: {
      value: [String, Number],
      options: {
        type: Array as PropType<{ label: string; value: number | string }[]>,
        default: () => [],
      },
    },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h(
          'select',
          {
            ...attrs,
            value: props.value,
            onChange: (event: Event) => {
              const value = (event.target as HTMLSelectElement).value;
              emit(
                'update:value',
                props.options.find((item) => String(item.value) === value)
                  ?.value,
              );
            },
          },
          props.options.map((option) =>
            h('option', { value: option.value }, option.label),
          ),
        ),
  });
  const modal = defineComponent({
    props: { open: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', slots.default?.()) : null,
  });
  return {
    Alert: slot('aside'),
    Button: slot('button'),
    Card: slot('section'),
    Checkbox: slot('span'),
    Dropdown: slot('div'),
    Input: input,
    InputNumber: input,
    Menu: Object.assign(slot('div'), { Item: slot('div') }),
    Modal: modal,
    RadioGroup: choice,
    Select: choice,
    Switch: slot('button'),
    Table: slot('table'),
    Tag: slot('span'),
    TextArea: input,
    Tooltip: slot('span'),
    Tree: slot('div'),
    TreeSelect: input,
    message: { warning: vi.fn(), success: vi.fn() },
  };
});
let app: App | undefined;
let host: HTMLDivElement;
function mount(component: Component) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(component);
  app.directive('access', {});
  return app.mount(host);
}
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
  expect(result, `应显示 ${label} 按钮`).toBeDefined();
  return result!;
}
function input(placeholder: string) {
  const result = [...host.querySelectorAll('input')].find(
    (item) => item.placeholder === placeholder,
  );
  expect(result, `应显示 ${placeholder} 输入框`).toBeDefined();
  return result!;
}
async function enter(placeholder: string, value: string) {
  const field = input(placeholder);
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
function stepButton(index: number) {
  return host.querySelectorAll<HTMLButtonElement>('nav button')[index]!;
}
function currentStep() {
  return host.querySelector('nav button[aria-current="step"]')?.textContent;
}
function listVisible() {
  const list = host.querySelector('[data-testid="api-list"]');
  for (
    let parent = list?.parentElement;
    parent;
    parent = parent.parentElement
  ) {
    if (parent.style.display === 'none') return false;
  }
  return !!list;
}
async function fillBasicInformation() {
  await enter('请选择 API 类目', '9007199254740993');
  await enter('如：站点流量实时数据服务', '站点服务');
  await enter('如：/api/water/level/hourly/list', '/station');
}
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.clearAllMocks();
  vi.mocked(api.testDataApi).mockReset();
});

describe('数据服务 API 的数据源选择加载与联动', () => {
  it('返回后重新打开时丢弃上一轮的选项响应', async () => {
    let resolveOld!: (value: api.SupplyDataApiApi.DatabaseSimple[]) => void;
    vi.mocked(api.getDataApiDatabaseList)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
      )
      .mockResolvedValueOnce([{ id: '9007199254740995', name: '本轮连接' }]);
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open();
    await flush();
    expect(
      host.querySelector<HTMLElement>('[data-testid="database-select"]')
        ?.dataset.loading,
    ).toBe('true');
    button('返回列表').click();
    await flush();
    await view.open();
    await flush();
    expect(host.textContent).toContain('本轮连接');
    resolveOld([{ id: '9007199254740993', name: '上轮连接' }]);
    await flush();
    expect(host.textContent).not.toContain('上轮连接');
    expect(host.textContent).toContain('本轮连接');
    expect(
      host.querySelector<HTMLElement>('[data-testid="database-select"]')
        ?.dataset.loading,
    ).toBe('false');
  });

  it('目录加载失败可重试，保留编辑中的原始连接编号', async () => {
    vi.mocked(api.getDataApi).mockResolvedValueOnce({
      id: '7',
      databaseId: '9007199254740993',
      configType: 'SQL',
    });
    vi.mocked(databaseTypeApi.getDatabaseTypeSimpleList)
      .mockRejectedValueOnce(new Error('目录请求失败'))
      .mockResolvedValueOnce([]);
    vi.mocked(api.getDataApiDatabaseList).mockResolvedValue([
      { id: '9007199254740993', name: '已有连接' },
    ]);
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open('7');
    await flush();
    expect(host.textContent).toContain('数据源列表加载失败，请重试');
    expect(
      host.querySelector('[data-testid="database-value"]')?.textContent,
    ).toBe('9007199254740993');
    button('重试数据源').click();
    await flush();
    expect(host.textContent).not.toContain('数据源列表加载失败，请重试');
    expect(host.textContent).toContain('已有连接');
  });

  it('切换与清除连接后清空表名，并丢弃清除前的表列表响应', async () => {
    let resolveTables!: (value: string[]) => void;
    vi.mocked(api.getDataApi).mockResolvedValueOnce({
      id: '7',
      categoryId: '11',
      name: '表服务',
      path: '/table',
      version: 'v1',
      databaseId: '9007199254740993',
      configType: 'TABLE',
      tableName: 'old_table',
    });
    vi.mocked(api.getDataApiDatabaseList).mockResolvedValueOnce([
      { id: '9007199254740993', name: '原连接' },
      { id: '9007199254740995', name: '另一个连接' },
    ]);
    vi.mocked(api.getDataApiTableList)
      .mockResolvedValueOnce(['old_table'])
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveTables = resolve;
        }),
      );
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open('7');
    await flush();
    button('下一步').click();
    await flush();
    expect(
      host.querySelector<HTMLSelectElement>(
        'select[placeholder="搜索并选择数据表"]',
      )?.value,
    ).toBe('old_table');
    button('另一个连接').click();
    await flush();
    expect(api.getDataApiTableList).toHaveBeenLastCalledWith(
      '9007199254740995',
    );
    expect(host.querySelector('option[value="old_table"]')).toBeNull();
    button('清除数据源').click();
    await flush();
    resolveTables(['late_table']);
    await flush();
    expect(host.querySelector('option[value="late_table"]')).toBeNull();
    expect(
      host.querySelector('[data-testid="database-value"]')?.textContent,
    ).toBe('');
    button('下一步').click();
    await flush();
    expect(message.warning).toHaveBeenLastCalledWith('请选择数据源与数据表');
  });

  it('表列表请求失败后仍能切换连接并重新加载', async () => {
    vi.mocked(api.getDataApi).mockResolvedValueOnce({
      id: '7',
      categoryId: '11',
      name: '表服务',
      path: '/table',
      version: 'v1',
      databaseId: '1',
      configType: 'TABLE',
    });
    vi.mocked(api.getDataApiDatabaseList).mockResolvedValueOnce([
      { id: '1', name: '不可达连接' },
      { id: '9007199254740995', name: '可达连接' },
    ]);
    vi.mocked(api.getDataApiTableList)
      .mockRejectedValueOnce(new Error('模拟表列表加载失败'))
      .mockResolvedValueOnce(['new_table']);
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open('7');
    await flush();
    button('下一步').click();
    await flush();
    button('可达连接').click();
    await flush();
    expect(api.getDataApiTableList).toHaveBeenLastCalledWith(
      '9007199254740995',
    );
    expect(host.querySelector('option[value="new_table"]')).not.toBeNull();
  });
});

describe('受控 ETL 批次服务向导', () => {
  it('只提交字符串游标与受支持模式，下一批不使用页码或总数', async () => {
    vi.mocked(api.getDataApi).mockResolvedValue({
      id: '7',
      categoryId: '11',
      name: '受控批次',
      path: '/batch',
      version: 'v1',
      configType: 'ETL_BATCH',
      etlBatchId: '9007199254740993',
      authType: 'NONE',
      apiType: 'INSERT',
      resultFormat: 'PAGE',
      cacheEnabled: 1,
      requestParams: [{ name: 'oldFilter' }],
      responseFields: [{ name: 'masked' }],
    });
    vi.mocked(api.testDataApi).mockResolvedValue({
      rows: [{ masked: '***' }],
      nextAfterRow: '9007199254740995',
      hasMore: true,
    });
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open('7');
    await flush();
    button('下一步').click();
    await flush();
    button('下一步').click();
    await flush();
    const cursor = host.querySelector(
      'input[placeholder="0"]',
    ) as HTMLInputElement;
    cursor.value = '9007199254740993';
    cursor.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    button('只读试调').click();
    await flush();
    const request = vi.mocked(api.testDataApi).mock.calls[0]![0];
    expect(request.params).toEqual({ afterRow: '9007199254740993' });
    expect(request.api).toMatchObject({
      etlBatchId: '9007199254740993',
      apiType: 'QUERY',
      resultFormat: 'LIST',
      authType: 'APPKEY',
      cacheEnabled: 0,
      responseFields: [{ name: 'masked' }],
    });
    expect(request.api.requestParams?.map((item) => item.name)).toEqual([
      'afterRow',
    ]);
    button('读取下一批').click();
    await flush();
    expect(vi.mocked(api.testDataApi).mock.calls[1]![0].params).toEqual({
      afterRow: '9007199254740995',
    });
    expect(host.textContent).toContain('本次');
  });
});

describe('接口试调结果与编辑配置一致', () => {
  const sqlPlaceholder = '如：SELECT * FROM hyd_discharge <where><if test="station_code != null"> AND station_code = #{station_code}</if></where>';

  async function openSqlWizard() {
    vi.mocked(api.getDataApi).mockResolvedValueOnce({
      id: '7',
      categoryId: '11',
      name: 'SQL 服务',
      path: '/sql',
      version: 'v1',
      configType: 'SQL',
      databaseId: '9007199254740993',
      sqlText: 'SELECT old_column FROM station',
      requestParams: [{ name: 'station', exampleValue: '测站编号' }],
      responseFields: [],
    });
    const view = mount(Wizard) as InstanceType<typeof Wizard>;
    await view.open('7');
    await flush();
    stepButton(2).click();
    await flush();
    return view;
  }

  it('修改 SQL 后旧试调不得显示成功或补入旧返回字段', async () => {
    let resolveOld!: (value: api.SupplyDataApiApi.ExecuteResult) => void;
    vi.mocked(api.testDataApi).mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }));
    await openSqlWizard();
    button('只读试调').click();
    await flush();
    button('上一步').click();
    await flush();
    await enter(sqlPlaceholder, 'SELECT new_column FROM station');
    resolveOld({ rows: [{ old_column: '旧数据' }], durationMs: 20 });
    await flush();
    expect(message.success).not.toHaveBeenCalled();
    button('保存').click();
    await flush();
    expect(api.updateDataApi).toHaveBeenCalledWith(expect.objectContaining({
      sqlText: 'SELECT new_column FROM station', responseFields: [],
    }));
  });

  it.each(['旧请求先返回', '新请求先返回'] as const)('修改参数后可重新试调并隔离响应：%s', async (order) => {
    let resolveOld!: (value: api.SupplyDataApiApi.ExecuteResult) => void;
    let resolveNew!: (value: api.SupplyDataApiApi.ExecuteResult) => void;
    vi.mocked(api.testDataApi)
      .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve; }));
    await openSqlWizard();
    await enter('测站编号', 'A');
    button('只读试调').click();
    await flush();
    await enter('测站编号', 'B');
    button('只读试调').click();
    await flush();
    expect(api.testDataApi).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.testDataApi).mock.calls[0]![0].params).toEqual({ station: 'A' });
    expect(vi.mocked(api.testDataApi).mock.calls[1]![0].params).toEqual({ station: 'B' });
    if (order === '旧请求先返回') {
      resolveOld({ raw: '旧结果', durationMs: 1 });
      await flush();
    }
    expect(button('只读试调').getAttribute('loading')).toBe('true');
    expect(host.textContent).not.toContain('旧结果');
    resolveNew({ raw: '新结果', durationMs: 2 });
    await flush();
    if (order === '新请求先返回') {
      resolveOld({ raw: '旧结果', durationMs: 1 });
      await flush();
    }
    expect(button('只读试调').getAttribute('loading')).toBe('false');
    expect(host.textContent).toContain('新结果');
    expect(host.textContent).not.toContain('旧结果');
    await enter('测站编号', 'C');
    expect(host.textContent).not.toContain('新结果');
  });

  it('当前配置试调自动补字段后仍展示结果，并允许保存字段', async () => {
    vi.mocked(api.testDataApi).mockResolvedValueOnce({ rows: [{ station_id: '9007199254740993' }], durationMs: 17 });
    await openSqlWizard();
    button('只读试调').click();
    await flush();
    expect(host.textContent).toContain('17 毫秒');
    button('保存').click();
    await flush();
    expect(api.updateDataApi).toHaveBeenCalledWith(expect.objectContaining({
      responseFields: [expect.objectContaining({ name: 'station_id' })],
    }));
  });

  it('关闭后重新打开向导，上一轮试调不能填入本轮返回字段', async () => {
    let resolveOld!: (value: api.SupplyDataApiApi.ExecuteResult) => void;
    vi.mocked(api.testDataApi).mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }));
    const view = await openSqlWizard();
    button('只读试调').click();
    await flush();
    button('返回列表').click();
    await flush();
    await view.open();
    await flush();
    resolveOld({ rows: [{ old_column: '旧数据' }], durationMs: 20 });
    await flush();
    expect(message.success).not.toHaveBeenCalled();
    expect(host.textContent).toContain('暂无返回字段');
    expect(host.textContent).not.toContain('20 毫秒');
  });
});

describe('服务页面向导', () => {
  it('新增占据当前页面，返回列表不提交，重新新增清空上次输入', async () => {
    mount(ApiPage);
    await flush();
    expect(listVisible()).toBe(true);
    expect(host.querySelector('nav')).toBeNull();

    button('新增').click();
    await flush();
    expect(listVisible()).toBe(false);
    expect(currentStep()).toContain('属性配置');
    await fillBasicInformation();
    button('下一步').click();
    await flush();
    expect(currentStep()).toContain('参数配置');

    button('返回列表').click();
    await flush();
    expect(listVisible()).toBe(true);
    expect(host.querySelector('nav')).toBeNull();
    expect(api.createDataApi).not.toHaveBeenCalled();
    expect(api.updateDataApi).not.toHaveBeenCalled();

    button('新增').click();
    await flush();
    expect(listVisible()).toBe(false);
    expect(currentStep()).toContain('属性配置');
    expect(input('如：站点流量实时数据服务').value).toBe('');
    expect(input('如：/api/water/level/hourly/list').value).toBe('');
    expect(input('如：v1.0.0').value).toBe('v1.0.0');
  });

  it('顶部跳步遵守两步校验，保存新服务后返回并刷新列表', async () => {
    vi.mocked(api.createDataApi).mockResolvedValue(7);
    mount(ApiPage);
    await flush();
    button('新增').click();
    await flush();

    stepButton(2).click();
    await flush();
    expect(currentStep()).toContain('属性配置');
    expect(message.warning).toHaveBeenLastCalledWith('请选择 API 类目');
    await fillBasicInformation();
    stepButton(2).click();
    await flush();
    expect(currentStep()).toContain('属性配置');
    expect(message.warning).toHaveBeenLastCalledWith('请选择数据源与数据表');

    stepButton(1).click();
    await flush();
    const config = [...host.querySelectorAll('select')].find((select) =>
      [...select.options].some((option) => option.value === 'FORWARD'),
    )!;
    config.value = 'FORWARD';
    config.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    await enter('http(s):// 开头的目标接口地址', 'https://example.com/station');
    stepButton(2).click();
    await flush();
    expect(currentStep()).toContain('测试');

    button('保存').click();
    await flush();
    expect(api.createDataApi).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        categoryId: '9007199254740993',
        name: '站点服务',
        path: '/station',
        configType: 'FORWARD',
        forwardUrl: 'https://example.com/station',
      }),
    );
    expect(api.updateDataApi).not.toHaveBeenCalled();
    expect(query).toHaveBeenCalledOnce();
    expect(listVisible()).toBe(true);
    expect(host.querySelector('nav')).toBeNull();
  });
});
