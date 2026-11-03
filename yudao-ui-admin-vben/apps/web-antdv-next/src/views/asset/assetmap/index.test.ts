/* eslint-disable vue/one-component-per-file -- 测试桩集中声明 */
import type { App, ComponentPublicInstance } from 'vue';

import type { AssetRegistrationApi } from '#/api/asset/dataasset/registration';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import Registration from './index.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:data-asset:create'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  route: { query: { assetType: '1' }, meta: {} },
  options: vi.fn(),
  context: vi.fn(),
  preview: vi.fn(),
  register: vi.fn(),
  generic: vi.fn(),
  push: vi.fn(),
  pickerData: vi.fn(),
  pickerOpen: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => mock.route,
  useRouter: () => ({ push: mock.push }),
}));
vi.mock('@vben/utils', () => ({ handleTree: (rows: unknown) => rows }));
vi.mock('#/api/asset/dataasset', () => ({
  ASSET_TYPE_OPTIONS: [
    { value: 1, label: '数据库表' },
    { value: 2, label: '外部 API' },
    { value: 3, label: '非结构化数据' },
  ],
  batchRegisterDataAsset: mock.register,
}));
vi.mock('#/api/asset/dataasset/registration', () => ({
  getAssetRegistrationOptions: mock.options,
  getAssetRegistrationContext: mock.context,
  previewAssetRegistrationName: mock.preview,
  registerGenericDataAssets: mock.generic,
}));
vi.mock('./modules/metadata-picker.vue', () => ({
  default: { render: () => null },
}));
vi.mock('@vben/common-ui', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const api = {
    setData: (data: unknown) => {
      mock.pickerData(data);
      return api;
    },
    setState: vi.fn(),
    open: mock.pickerOpen,
  };
  return { Page: box, useVbenModal: () => [box, api] };
});
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    ConfigProvider: box,
    Input: box,
    message: {
      success: mock.success,
      warning: mock.warning,
      error: mock.error,
    },
    Modal: box,
    RadioGroup: box,
    Select: box,
    Table: box,
    Tag: box,
    TreeSelect: box,
  };
});
interface Draft {
  assetName: string;
  categoryId?: string;
  dataDomainId?: string;
  englishName?: string;
  meta: AssetRegistrationApi.Candidate;
  nameCase?: string;
  subjectDomainId?: string;
  tableType?: string;
  entityId?: string;
  granularityEntityIds?: string[];
  context?: AssetRegistrationApi.Context;
  contextError?: string;
  customNames?: boolean;
}
interface State {
  assetType: number;
  drafts: Draft[];
  selectedRowKeys: string[];
  saving: boolean;
  options: unknown;
  batch: {
    categoryId?: string;
    dataDomainId?: string;
    open: boolean;
    subjectDomainId?: string;
    tableNameUpdateMode: number;
    tableType?: string;
  };
  onPicked(rows: AssetRegistrationApi.Candidate[]): void;
  changeRow(row: Draft, field: string, value: unknown): void;
  refreshDraftContext(row: Draft): Promise<void>;
  manualName(row: Draft): void;
  removeDraft(id: string): void;
  openPicker(): void;
  openBatch(): void;
  applyBatch(): Promise<void>;
  registerAndExit(): Promise<void>;
}
let app: App;
let host: HTMLDivElement;
let state: State;
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
function mount() {
  app = createApp(Registration);
  state = Reflect.get(
    (app.mount(host) as ComponentPublicInstance).$,
    'setupState',
  ) as State;
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:data-asset:create'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.route.query.assetType = '1';
  mock.options.mockResolvedValue({
    categories: [],
    subjectDomains: [],
    layers: [],
  });
  mock.context.mockResolvedValue({
    dataDomains: [],
    subjectDomains: [],
    layers: [],
    entities: [],
    granularities: [],
    namingRules: [],
  });
  mock.preview.mockResolvedValue({
    assetName: '规则名称',
    businessEnglishName: 'ods_real_table',
  });
  mock.register.mockResolvedValue(['11']);
  mock.generic.mockResolvedValue(['12']);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
it('preserves long IDs and ignores duplicate, registered and wrong-type metadata', async () => {
  mount();
  await flush();
  state.onPicked([
    {
      id: '9007199254740993',
      tableName: 'physical_table',
      tableType: 'BASE TABLE',
    },
    { id: '9007199254740993' },
    { id: '2', registered: true },
    { id: '3', assetType: 3 },
  ]);
  expect(state.drafts).toHaveLength(1);
  expect(state.drafts[0]?.meta.id).toBe('9007199254740993');
  state.openPicker();
  expect(mock.pickerData).toHaveBeenCalledWith({
    assetType: 1,
    excludedIds: ['9007199254740993'],
  });
});
it('submits editable business naming separately from immutable physical identity', async () => {
  mount();
  await flush();
  state.onPicked([
    {
      id: '9007199254740993',
      tableName: 'physical_table',
      businessName: '降雨',
    },
  ]);
  state.drafts[0]!.englishName = 'BusinessRainfall';
  state.drafts[0]!.categoryId = '99';
  await flush();
  await state.registerAndExit();
  expect(mock.register).toHaveBeenCalledWith([
    expect.objectContaining({
      metaTableId: '9007199254740993',
      assetName: '降雨',
      categoryId: '99',
      registrationConfig: expect.objectContaining({
        businessEnglishName: 'BusinessRainfall',
      }),
    }),
  ]);
  expect(mock.generic).not.toHaveBeenCalled();
  expect(mock.success).toHaveBeenCalledOnce();
});
it.each([2, 3])(
  'registers source type %s with provider candidate IDs through the generic API',
  async (type) => {
    mock.route.query.assetType = String(type);
    mount();
    await flush();
    state.onPicked([
      { id: '9007199254740993', assetType: type, name: 'provider item' },
    ]);
    await flush();
    await state.registerAndExit();
    expect(mock.generic).toHaveBeenCalledWith(type, [
      expect.objectContaining({
        candidateId: '9007199254740993',
        assetName: 'provider item',
      }),
    ]);
    expect(mock.register).not.toHaveBeenCalled();
  },
);
it('applies batch ownership only to selected rows and keeps long IDs intact', async () => {
  mount();
  await flush();
  state.onPicked([
    { id: '9007199254740993', tableName: 'first' },
    { id: '2', tableName: 'second' },
  ]);
  state.selectedRowKeys = ['9007199254740993'];
  state.openBatch();
  state.batch.categoryId = '9007199254740994';
  state.batch.subjectDomainId = '88';
  await state.applyBatch();
  expect(state.drafts[0]).toMatchObject({
    categoryId: '9007199254740994',
    subjectDomainId: '88',
  });
  expect(state.drafts[1]?.categoryId).toBeUndefined();
});
it('does not report successful registration for missing results or duplicate clicks', async () => {
  const work = deferred<string[]>();
  mock.register.mockReturnValue(work.promise);
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'rainfall' }]);
  await flush();
  const pending = state.registerAndExit();
  await flush();
  await state.registerAndExit();
  expect(mock.register).toHaveBeenCalledOnce();
  work.resolve([]);
  await pending;
  expect(mock.success).not.toHaveBeenCalled();
  expect(state.drafts).toHaveLength(1);
  expect(mock.error).toHaveBeenCalled();
});
it('drops pending state and ignores completion after identity changes', async () => {
  const work = deferred<string[]>();
  mock.register.mockReturnValue(work.promise);
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'rainfall' }]);
  await flush();
  const pending = state.registerAndExit();
  mock.access.accessToken = 'another-session';
  await flush();
  work.resolve(['11']);
  await pending;
  expect(mock.success).not.toHaveBeenCalled();
  expect(mock.push).not.toHaveBeenCalled();
  expect(state.drafts).toHaveLength(0);
});
it('enforces create permission on picker and registration handlers', async () => {
  mock.access.accessCodes = [];
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'rainfall' }]);
  state.openPicker();
  await flush();
  await state.registerAndExit();
  expect(mock.options).not.toHaveBeenCalled();
  expect(mock.pickerOpen).not.toHaveBeenCalled();
  expect(mock.register).not.toHaveBeenCalled();
});

it('persists a real modeling domain independently from asset category for files', async () => {
  mock.route.query.assetType = '3';
  mount();
  await flush();
  state.onPicked([{ id: '9007199254740993', assetType: 3, name: '真实文件' }]);
  await flush();
  const row = state.drafts[0]!;
  state.changeRow(row, 'dataDomainId', '9007199254740994');
  await flush();
  state.changeRow(row, 'subjectDomainId', '9007199254740995');
  await flush();
  row.categoryId = '88';
  await state.registerAndExit();
  expect(mock.generic).toHaveBeenCalledWith(3, [
    expect.objectContaining({
      categoryId: '88',
      subjectDomainId: '9007199254740995',
      registrationConfig: expect.objectContaining({
        dataDomainId: '9007199254740994',
      }),
    }),
  ]);
});
it('uses server naming for source metadata without changing physical table identity', async () => {
  mount();
  await flush();
  state.onPicked([
    { id: '9007199254740993', tableName: 'physical_table', tableType: 'ODS' },
  ]);
  await flush();
  expect(mock.preview).toHaveBeenCalledWith(
    expect.objectContaining({
      candidateId: '9007199254740993',
      tableType: 'ODS',
      namingCase: 'LOWER',
    }),
  );
  expect(state.drafts[0]).toMatchObject({
    assetName: '规则名称',
    englishName: 'ods_real_table',
    meta: { tableName: 'physical_table' },
  });
});
it('waits for actual entity and granularity selections before requesting names', async () => {
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'physical' }]);
  await flush();
  const row = state.drafts[0]!;
  state.changeRow(row, 'tableType', 'DWD');
  await flush();
  state.changeRow(row, 'dataDomainId', '11');
  await flush();
  expect(mock.preview).not.toHaveBeenCalled();
  state.changeRow(row, 'entityId', '22');
  await flush();
  expect(mock.preview).toHaveBeenCalledWith(
    expect.objectContaining({ dataDomainId: '11', entityId: '22' }),
  );
  mock.preview.mockClear();
  state.changeRow(row, 'tableType', 'DWS');
  await flush();
  state.changeRow(row, 'subjectDomainId', '33');
  await flush();
  expect(mock.preview).not.toHaveBeenCalled();
  state.changeRow(row, 'granularityEntityIds', ['44', '9007199254740993']);
  await flush();
  expect(mock.preview).toHaveBeenCalledWith(
    expect.objectContaining({
      granularityEntityIds: ['44', '9007199254740993'],
    }),
  );
});
it('ignores earlier per-row contexts and name previews after later configuration changes', async () => {
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'physical' }]);
  await flush();
  const row = state.drafts[0]!;
  const early = deferred<AssetRegistrationApi.Context>();
  mock.context.mockReturnValueOnce(early.promise);
  state.changeRow(row, 'dataDomainId', '11');
  state.changeRow(row, 'dataDomainId', '22');
  await flush();
  early.resolve({
    dataDomains: [],
    subjectDomains: [],
    layers: [],
    entities: [],
    granularities: [],
    namingRules: [],
    selectedDataDomainId: '11',
  });
  await flush();
  expect(row.dataDomainId).toBe('22');
  expect(row.context?.selectedDataDomainId).not.toBe('11');
  const delayedName = deferred<AssetRegistrationApi.NamePreview>();
  mock.preview.mockReturnValueOnce(delayedName.promise);
  state.changeRow(row, 'tableType', 'ODS');
  await flush();
  row.assetName = '人工名称';
  state.manualName(row);
  delayedName.resolve({ assetName: '过期名称', businessEnglishName: 'old' });
  await flush();
  expect(row.assetName).toBe('人工名称');
});
it('preserves custom names in batch mode 0 and regenerates through the server in mode 1', async () => {
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'physical', tableType: 'ODS' }]);
  await flush();
  const row = state.drafts[0]!;
  row.assetName = '人工名称';
  row.englishName = 'manual_name';
  state.manualName(row);
  state.selectedRowKeys = ['1'];
  state.openBatch();
  mock.preview.mockClear();
  await state.applyBatch();
  expect(mock.preview).not.toHaveBeenCalled();
  expect(row.assetName).toBe('人工名称');
  state.openBatch();
  state.batch.tableNameUpdateMode = 1;
  await state.applyBatch();
  expect(mock.preview).toHaveBeenCalledOnce();
  expect(row.assetName).toBe('规则名称');
});
it('keeps every draft unchanged when any batch naming request fails', async () => {
  mount();
  await flush();
  state.onPicked([
    { id: '1', tableName: 'first', tableType: 'ODS' },
    { id: '2', tableName: 'second', tableType: 'ODS' },
  ]);
  await flush();
  const original = state.drafts.map((row) => row.assetName);
  state.selectedRowKeys = ['1', '2'];
  state.openBatch();
  state.batch.categoryId = '99';
  state.batch.tableNameUpdateMode = 1;
  mock.preview
    .mockResolvedValueOnce({
      assetName: '第一个新名',
      businessEnglishName: 'ods_first',
    })
    .mockRejectedValueOnce(new Error('provider unavailable'));
  await state.applyBatch();
  expect(state.drafts.map((row) => row.assetName)).toEqual(original);
  expect(state.drafts.every((row) => row.categoryId === undefined)).toBe(true);
  expect(state.batch.open).toBe(true);
  expect(mock.error).toHaveBeenCalled();
});
it('does not apply a batch after its dialog closes or the identity changes', async () => {
  mount();
  await flush();
  state.onPicked([{ id: '1', tableName: 'physical', tableType: 'ODS' }]);
  await flush();
  const row = state.drafts[0]!;
  const work = deferred<AssetRegistrationApi.NamePreview>();
  state.selectedRowKeys = ['1'];
  state.openBatch();
  state.batch.categoryId = '99';
  state.batch.tableNameUpdateMode = 1;
  mock.preview.mockReturnValueOnce(work.promise);
  const pending = state.applyBatch();
  await flush();
  state.batch.open = false;
  work.resolve({ assetName: '过期名称', businessEnglishName: 'stale' });
  await pending;
  expect(row.categoryId).toBeUndefined();
  expect(row.assetName).not.toBe('过期名称');
});
it('blocks registration when real configuration reads fail and permits a successful retry', async () => {
  mount();
  await flush();
  mock.context.mockRejectedValueOnce(new Error('unavailable'));
  state.onPicked([{ id: '1', tableName: 'physical' }]);
  await flush();
  await state.registerAndExit();
  expect(mock.register).not.toHaveBeenCalled();
  expect(state.drafts[0]?.contextError).toBeTruthy();
  await state.refreshDraftContext(state.drafts[0]!);
  await state.registerAndExit();
  expect(mock.register).toHaveBeenCalledOnce();
});
