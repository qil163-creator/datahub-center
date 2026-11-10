import type { App, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createAccess, getAccess, previewColumnMap, previewTableMap, updateAccess } from '#/api/dataforge/access';
import { getDatabaseSimpleList, getDatabaseTableList } from '#/api/dataforge/database';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  options: {} as any,
  access: {} as any,
  data: { id: '9007199254740993' } as any,
  lock: vi.fn(),
  unlock: vi.fn(),
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 1 } }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: unknown) => {
    harness.options = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
      {
        getData: () => harness.data,
        lock: harness.lock,
        unlock: harness.unlock,
        close: () => {
          harness.close();
          harness.options.onOpenChange(false);
        },
      },
    ];
  },
}));
vi.mock('@vben/utils', () => ({ handleTree: (data: unknown) => data }));
vi.mock('@vben/plugins/code-editor', () => ({
  CodeEditor: defineComponent({
    props: { value: { type: String, default: '' }, mode: { type: String, default: '' }, autoFormat: Boolean },
    emits: ['update:value'],
    setup(props, { emit }) {
      return () => h('textarea', {
        'data-code-editor': '', 'data-mode': props.mode, 'data-auto-format': String(props.autoFormat),
        value: props.value,
        onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value),
      });
    },
  }),
}));
vi.mock('#/components/cron-tab', () => ({ CronTab: () => null }));
vi.mock('#/views/dataforge/dataapi/modules/api-config-editor.vue', () => ({
  default: defineComponent({
    props: { modelValue: { type: Object, default: undefined }, disabled: Boolean },
    setup: () => () => h('div', { 'data-api-config-editor': '' }),
  }),
}));
vi.mock('./table-mapping.vue', () => ({ default: defineComponent({
  setup(_, { expose }) { expose({ validate: () => true }); return () => h('div'); },
}) }));
vi.mock('antdv-next', () => {
  const Slot = (_: unknown, { slots }: SetupContext) =>
    h('div', slots.default?.());
  const CheckedControl = defineComponent({
    props: { checked: Boolean },
    emits: ['update:checked', 'change'],
    setup(props, { emit, slots }) {
      return () => h('label', [h('input', {
        type: 'checkbox', checked: props.checked,
        onChange: (event: Event) => {
          const checked = (event.target as HTMLInputElement).checked;
          emit('update:checked', checked);
          emit('change', checked);
        },
      }), slots.default?.()]);
    },
  });
  return Object.fromEntries([
    ...[
      'AutoComplete',
      'Button',
      'Descriptions',
      'DescriptionsItem',
      'Input',
      'InputNumber',
      'Modal',
      'RadioGroup',
      'Select',
      'Steps',
      'Spin',
      'Table',
      'Tag',
      'TextArea',
      'TreeSelect',
      'Transfer',
    ].map((name) => [name, Slot]),
    ['Alert', (props: { message?: string }) => h('div', { role: 'alert' }, props.message)],
    ['Checkbox', CheckedControl],
    ['Switch', CheckedControl],
    ['message', { warning: vi.fn(), error: vi.fn(), success: vi.fn() }],
  ]);
});
vi.mock('#/api/dataforge/access', () => ({
  createAccess: vi.fn(),
  updateAccess: vi.fn(),
  getAccess: vi.fn(),
  previewColumnMap: vi.fn(),
  previewTableMap: vi.fn(),
  previewTypeMap: vi.fn(),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => [
    { id: '9007199254740999', name: 'Synthetic database' },
  ]),
  getDatabaseTableList: vi.fn(),
}));
vi.mock('#/api/system/dept', () => ({
  getDeptList: vi.fn(async () => [
    { id: '9007199254740995', name: 'Synthetic unit' },
  ]),
}));

let app: App;
let state: any;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.data = { id: '9007199254740993' };
  vi.mocked(getDatabaseSimpleList).mockResolvedValue([
    { id: '9007199254740999', name: 'Synthetic database' },
  ]);
  vi.mocked(getDatabaseTableList).mockResolvedValue([]);
  vi.mocked(previewTableMap).mockReset().mockResolvedValue([{ originalName: 'orders', targetName: 'orders' }]);
  vi.mocked(getAccess).mockResolvedValue({
    id: harness.data.id,
    taskName: 'Old session task',
    engineType: 3,
  });
  app = createApp(Form);
  host = document.createElement('div');
  state = (app.mount(host) as any).$.setupState;
});
afterEach(() => app.unmount());

describe('access wizard component contract', () => {
  async function openApiForm() {
    harness.data = { engineType: 3 };
    await harness.options.onOpenChange(true);
    Object.assign(state.model, { taskName: '人口采集', targetDatabaseId: '9007199254740999' });
    Object.assign(state.model.apiMappings[0].apiConfig, {
      apiName: '人口接口', requestUrl: 'https://example.invalid/population',
      requestMethod: 'POST', requestHeader: [{ name: 'X-Test', value: 'fixture' }],
      bodyType: 'raw', bodyRaw: '{"scope":"all"}', dataKey: 'data.rows',
      ifPage: 1, pageKey: 'pageNo', pageSizeKey: 'pageSize',
      dataColumns: [{ fieldName: 'id', fieldType: 4, pk: true, nullable: false }, { fieldName: 'name', fieldType: 12 }],
    });
    await flush();
    state.model.apiMappings[0].targetTable = 'ods_population';
  }

  it('接口采集使用页内四步向导，不再请求独立 API 资源列表', async () => {
    app.unmount();
    app = createApp(Form, { embedded: true, initialData: { engineType: 3 } });
    state = (app.mount(host) as any).$.setupState;
    await flush();
    expect(state.stepKeys).toEqual(['basic', 'apiconfig', 'apiaccess', 'attributes']);
    expect(host.querySelector('.wizard--sync.wizard--embedded')).not.toBeNull();
    expect(host.querySelectorAll('.sync-steps button')).toHaveLength(4);
    expect(state.getTitle).toBe('新建接口采集任务');
    state.currentStep = 1;
    await flush();
    expect(host.querySelector('[data-api-config-editor]')).not.toBeNull();
    expect(host.textContent).not.toContain('在 API 管理中维护');
  });

  it('采集保存完整接口定义与映射，不创建 API 资源或提交页面标识', async () => {
    await openApiForm();
    state.model.apiMappings[0].columnMaps[0].targetColumn = 'person_id';
    await state.handleSubmit();
    const submitted = vi.mocked(createAccess).mock.calls[0]![0];
    expect(submitted).toMatchObject({
      engineType: 3, targetDatabaseId: '9007199254740999',
      apiMappings: [{ sourceApiName: '人口接口', targetTable: 'ods_population',
        apiConfig: { requestUrl: 'https://example.invalid/population', bodyRaw: '{"scope":"all"}',
          ifPage: 1, pageKey: 'pageNo', dataKey: 'data.rows' },
        columnMaps: [{ sourceColumn: 'id', targetColumn: 'person_id', importFlg: 1 }, { sourceColumn: 'name', targetColumn: 'name', importFlg: 1 }],
      }],
    });
    expect(submitted.apiMappings![0]).not.toHaveProperty('sourceId');
    expect(submitted.apiMappings![0]).not.toHaveProperty('mappingKey');
    expect(createAccess).toHaveBeenCalledOnce();
  });

  it('重新解析字段保留同名映射与排除状态，各接口配置互相独立', async () => {
    await openApiForm();
    const mapping = state.model.apiMappings[0];
    mapping.columnMaps[0].targetColumn = 'person_id';
    mapping.columnMaps[1].importFlg = 0;
    mapping.apiConfig.dataColumns = [{ fieldName: 'id' }, { fieldName: 'name' }, { fieldName: 'age' }];
    await flush();
    expect(mapping.columnMaps).toEqual([
      { sourceColumn: 'id', targetColumn: 'person_id', importFlg: 1 },
      { sourceColumn: 'name', targetColumn: 'name', importFlg: 0 },
      { sourceColumn: 'age', targetColumn: 'age', importFlg: 1 },
    ]);
    state.addApiMapping();
    expect(state.model.apiMappings[1].apiConfig.requestHeader).toEqual([]);
    expect(state.model.apiMappings[1].mappingKey).not.toBe(mapping.mappingKey);
    state.removeApiMapping(mapping.mappingKey);
    expect(state.model.apiMappings).toHaveLength(1);
    expect(state.model.apiMappings[0].apiConfig.requestUrl).toBe('');
  });

  it('接口配置与周期调度在各自步骤校验，编辑回显完整定义', async () => {
    await openApiForm();
    state.model.taskType = 3;
    expect(state.validateStep(0)).toBe(true);
    expect(state.validateStep(3)).toBe(false);
    state.model.cron = '0 0 * * * ?';
    state.model.apiMappings[0].apiConfig.pageSizeKey = '';
    expect(state.validateStep(1)).toBe(false);
    state.model.apiMappings[0].apiConfig.pageSizeKey = 'pageSize';
    const saved = { ...state.model, id: '9007199254740993', apiMappings: [{
      apiConfig: { ...state.model.apiMappings[0].apiConfig }, targetTable: 'ods_population',
      columnMaps: [{ sourceColumn: 'id', targetColumn: 'person_id', importFlg: 1 }],
    }] };
    harness.data = { id: saved.id, engineType: 3 };
    vi.mocked(getAccess).mockResolvedValue(saved);
    await harness.options.onOpenChange(true);
    await flush();
    expect(state.getTitle).toBe('编辑接口采集任务');
    expect(state.model.apiMappings[0].apiConfig.bodyRaw).toBe('{"scope":"all"}');
    expect(state.model.apiMappings[0].columnMaps[0].targetColumn).toBe('person_id');
  });

  it('按主键增量要求保留所有主键映射，拒绝删除重建与增量同时开启', async () => {
    await openApiForm();
    state.model.targetDataSync = true;
    const mapping = state.model.apiMappings[0];
    expect(state.validateStep(2)).toBe(true);
    mapping.columnMaps[0].importFlg = 0;
    expect(state.validateStep(2)).toBe(false);
    mapping.columnMaps[0].importFlg = 1;
    mapping.apiConfig.dataColumns[0].pk = false;
    expect(state.validateStep(2)).toBe(false);
    mapping.apiConfig.dataColumns[0].pk = true;
    state.model.targetDropTable = true;
    expect(state.validateStep(2)).toBe(false);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
  });
  const seatunnelConfigs = [
    ['json', ' {\n  "env": { "job.mode": "STREAMING" },\n  "source": [{ "plugin_name": "FakeSource" }],\n  "sink": [{ "plugin_name": "Console" }]\n}\n'],
    ['hocon', '# 原生作业配置\nenv { job.mode = "STREAMING" }\nsource { FakeSource {} }\nsink { Console {} }\n'],
  ] as const;

  async function openSeatunnelForm(saved?: Record<string, unknown>) {
    if (saved) vi.mocked(getAccess).mockResolvedValue({
      id: harness.data.id, taskName: 'SeaTunnel 作业', engineType: 2, taskType: 1,
      configFormat: 'json', seatunnelConfig: seatunnelConfigs[0][1], ...saved,
    });
    else harness.data = {};
    await harness.options.onOpenChange(true);
    if (!saved) {
      state.model.engineType = 2;
      state.handleEngineChange();
    }
    await flush();
  }

  it('实时同步沿用横向箭头和固定底部操作栏，并恢复全部调度类型', async () => {
    app.unmount();
    app = createApp(Form, { embedded: true });
    state = (app.mount(host) as any).$.setupState;
    await flush();
    await openSeatunnelForm();
    expect(state.stepKeys).toEqual(['basic', 'seatunnel', 'confirm']);
    expect(host.querySelector('.wizard--sync.wizard--embedded')).not.toBeNull();
    expect([...host.querySelectorAll('.sync-steps button')].map((item) => item.textContent?.trim()))
      .toEqual(['1配置基础信息', '2Seatunnel 配置', '3确认提交']);
    expect(host.querySelector('.sync-form-page__footer')?.textContent).toContain('返回列表');
    expect(state.taskTypeOptions.map((item: { value: number }) => item.value)).toEqual([1, 2, 3]);
    expect(state.model.taskType).toBe(2);
  });

  it.each(seatunnelConfigs)('%s 作业按原文创建，无需绑定平台数据源', async (configFormat, seatunnelConfig) => {
    for (const taskType of [1, 2, 3]) {
      await openSeatunnelForm();
      Object.assign(state.model, {
        taskName: 'SeaTunnel 作业', seatunnelConfig, configFormat, taskType,
        cron: taskType === 3 ? '0 0 * * * ?' : '',
      });
      expect(state.validateStep(0)).toBe(true);
      expect(state.validateStep(1)).toBe(true);
      await state.handleSubmit();
      expect(createAccess).toHaveBeenLastCalledWith(expect.objectContaining({
        engineType: 2, taskType, configFormat, seatunnelConfig,
        sourceDatabaseId: undefined, targetDatabaseId: undefined,
        cron: taskType === 3 ? '0 0 * * * ?' : '',
      }));
    }
    expect(createAccess).toHaveBeenCalledTimes(3);
  });

  it.each(seatunnelConfigs)('%s 作业原文回显编辑器，更新不重写格式，确认页只显示摘要', async (configFormat, seatunnelConfig) => {
    await openSeatunnelForm({ configFormat, seatunnelConfig, taskType: 3, cron: '0 0 * * * ?' });
    expect(state.model.seatunnelConfig).toBe(seatunnelConfig);
    expect(state.model.configFormat).toBe(configFormat);
    expect(state.model.taskType).toBe(3);
    state.currentStep = 1;
    await flush();
    const editor = host.querySelector<HTMLTextAreaElement>('[data-code-editor]');
    expect(editor?.value).toBe(seatunnelConfig);
    expect(editor?.dataset.mode).toBe(configFormat === 'json' ? 'application/json' : 'text/plain');
    expect(editor?.dataset.autoFormat).toBe('false');
    const editedConfig = `${seatunnelConfig}\n`;
    editor!.value = editedConfig;
    editor!.dispatchEvent(new Event('input'));
    await flush();
    expect(state.model.seatunnelConfig).toBe(editedConfig);
    state.currentStep = 2;
    await flush();
    expect(host.querySelector('[data-code-editor]')).toBeNull();
    expect(host.textContent).not.toContain(editedConfig);
    expect(host.textContent).toContain(`${editedConfig.length} 字符`);
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledWith(expect.objectContaining({
      id: harness.data.id, taskType: 3, cron: '0 0 * * * ?', configFormat,
      seatunnelConfig: editedConfig,
    }));
  });

  it('空配置与非法 JSON 阻止保存，HOCON 不套用 JSON 校验，周期任务要求 cron', async () => {
    await openSeatunnelForm();
    Object.assign(state.model, { taskName: 'SeaTunnel 作业', taskType: 3 });
    expect(state.validateStep(0)).toBe(false);
    state.model.cron = '0 0 * * * ?';
    expect(state.validateStep(0)).toBe(true);
    state.model.seatunnelConfig = '  ';
    expect(state.validateStep(1)).toBe(false);
    state.model.seatunnelConfig = seatunnelConfigs[1][1];
    expect(state.validateStep(1)).toBe(false);
    state.model.configFormat = 'hocon';
    expect(state.validateStep(1)).toBe(true);
    expect(state.model.seatunnelConfig).toBe(seatunnelConfigs[1][1]);
    state.model.seatunnelConfig = '';
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
  });

  it('切出 SeaTunnel 恢复离线调度选项，切换登录会话清除作业原文', async () => {
    await openSeatunnelForm({ configFormat: 'hocon', seatunnelConfig: seatunnelConfigs[1][1] });
    state.model.engineType = 1;
    state.handleEngineChange();
    expect(state.model.taskType).toBe(2);
    expect(state.taskTypeOptions.map((item: { value: number }) => item.value)).toEqual([2, 3]);
    state.model.engineType = 2;
    state.handleEngineChange();
    expect(state.model.seatunnelConfig).toBe(seatunnelConfigs[1][1]);
    expect(state.model.configFormat).toBe('hocon');
    harness.access.refreshToken = '2';
    await flush();
    expect(state.model.seatunnelConfig).toBe('');
  });

  const mappingAcknowledgement = {
    targetProduct: 'POSTGRESQL', targetSchema: 'public', targetTable: 'orders', column: 'amount',
    mappingFingerprint: 'a'.repeat(64), lossCodes: ['SQLITE_AFFINITY'],
  };
  async function openMappingForm(edit = false, saved: Record<string, unknown> = {}) {
    vi.mocked(getDatabaseSimpleList).mockResolvedValue([
      { id: '11', name: 'source', productId: 'SQLITE3' },
      { id: '12', name: 'target', productId: 'POSTGRESQL' },
      { id: '13', name: 'other target', productId: 'POSTGRESQL' },
    ]);
    const detail = { taskName: 'mapped transfer', engineType: 1, sourceType: 1,
      sourceDatabaseId: '11', targetDatabaseId: '12', accessMode: 2, sourceSelectedTables: ['orders'],
      targetDataSync: false, typeMappingAcknowledgements: edit ? [mappingAcknowledgement] : [], ...saved };
    if (edit) vi.mocked(getAccess).mockResolvedValue({ ...detail, id: harness.data.id });
    else harness.data = {};
    await harness.options.onOpenChange(true);
    if (!edit) Object.assign(state.model, detail);
    state.currentStep = 2; await flush();
  }
  it('opens the embedded page with four arrow steps and closes back to the list', async () => {
    app.unmount();
    const onClose = vi.fn();
    app = createApp(Form, { embedded: true, initialData: { engineType: 1 }, onClose });
    state = (app.mount(host) as any).$.setupState;
    await flush();
    expect(state.stepKeys).toEqual(['basic', 'source', 'mapper', 'attributes']);
    expect(host.querySelector('.sync-form-page')).not.toBeNull();
    expect([...host.querySelectorAll('.sync-steps button')].map((item) => item.textContent?.trim()))
      .toEqual(['1配置基础信息', '2选择来源表', '3配置多表映射', '4配置任务属性']);
    expect(host.querySelector('.sync-form-page__footer')?.textContent).toContain('返回列表');
    await state.closeForm();
    expect(onClose).toHaveBeenCalledOnce();
    expect(harness.close).not.toHaveBeenCalled();
  });

  it('requires an explicit source selection and preserves physical names through the transfer', async () => {
    await openMappingForm();
    state.model.sourceSelectedTables = [];
    expect(state.validateStep(1)).toBe(false);
    state.changeSelectedTables([' edge ', 'comma,name']);
    expect(state.validateStep(1)).toBe(true);
    expect(state.model.sourceSelectedTables).toEqual([' edge ', 'comma,name']);
    state.tableScope = 'ALL'; state.changeTableScope();
    expect(state.model.sourceSelectedTables).toEqual([]);
    expect(state.model.includeOrExclude).toBe(1);
    expect(state.validateStep(1)).toBe(true);
    state.tableScope = 'EXCLUDE'; state.changeTableScope();
    expect(state.model.includeOrExclude).toBe(0);
  });

  it('removes a mapping table from selected or all-table scopes without broadening an empty selection', async () => {
    await openMappingForm();
    state.removeSourceTable('orders');
    expect(state.model.sourceSelectedTables).toEqual([]);
    expect(state.currentKey).toBe('source');
    expect(state.validateStep(1)).toBe(false);
    state.tableScope = 'ALL'; state.changeTableScope();
    state.removeSourceTable('orders');
    expect(state.tableScope).toBe('EXCLUDE');
    expect(state.model.includeOrExclude).toBe(0);
    expect(state.model.sourceSelectedTables).toEqual(['orders']);
  });

  it('retains per-table overrides while loading defaults without those overrides', async () => {
    const legacy = { regexColumnMapper: [{ fromPattern: 'a', toValue: 'b' }] };
    const saved = { orders: { ...legacy, targetTableName: 'ods_custom', targetTableComment: '说明' } };
    await openMappingForm(true, { configMap: saved });
    expect(previewTableMap).toHaveBeenCalledWith(expect.objectContaining({ configMap: { orders: legacy } }));
    expect(state.model.configMap).toEqual(saved);
    const count = vi.mocked(previewTableMap).mock.calls.length;
    state.model.configMap.orders.targetTableName = 'ods_changed'; await flush();
    expect(previewTableMap).toHaveBeenCalledTimes(count);
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledWith(expect.objectContaining({ configMap: { orders: { ...saved.orders, targetTableName: 'ods_changed' } } }));
  });

  it('submits Doris options only to the platform warehouse and preserves the local draft when switching modes', async () => {
    await openMappingForm();
    state.databaseList.push({ id: '14', name: '中台原始库', productId: 'DORIS', usageType: 2 });
    const options = { distributionType: 'HASH', distributionColumns: ['id'], bucketMode: 'FIXED', bucketCount: 3, replicationNum: 1,
      partition: { kind: 'RANGE', columns: ['id'], partitions: [{ name: 'p1', values: [['9007199254740993'], ['9007199254740999']] }] } };
    state.model.configMap = { orders: { targetTableName: 'ods_orders', dorisOptions: options } };
    Object.assign(state.model, { targetDatabaseId: '14', accessMode: 1 }); await flush();
    expect(state.dorisTarget).toBe(true);
    expect(state.buildPreviewReq().configMap.orders.dorisOptions).toEqual(options);
    state.model.accessMode = 2; await flush();
    expect(state.dorisTarget).toBe(false);
    expect(state.buildPreviewReq().configMap.orders).toEqual({ targetTableName: 'ods_orders' });
    expect(state.model.configMap.orders.dorisOptions).toEqual(options);
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledWith(expect.objectContaining({ configMap: { orders: { targetTableName: 'ods_orders' } } }));
  });

  it('keeps table drafts out of SQL-source requests while retaining legacy column mappings', async () => {
    await openMappingForm();
    const legacy = [{ fromPattern: 'amount', toValue: 'total' }];
    const draft = { targetTableName: 'ods_orders', targetTableComment: '说明', regexColumnMapper: legacy };
    state.model.configMap = { orders: draft };
    state.model.sourceType = 2;
    await flush();
    expect(state.buildPreviewReq().configMap.orders).toEqual({ regexColumnMapper: legacy });
    expect(state.model.configMap.orders).toEqual(draft);
  });

  it('validates scheduling on the final step before saving', async () => {
    await openMappingForm(); state.currentStep = 3;
    Object.assign(state.model, { taskType: 3, cron: '' }); await flush();
    await state.handleSubmit();
    expect(state.currentKey).toBe('attributes');
    expect(createAccess).not.toHaveBeenCalled();
    state.model.cron = '0 0 2 * * ?';
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledOnce();
  });

  it('reloads affected mapping defaults on the last step and ignores an old closed-page response', async () => {
    await openMappingForm(); state.currentStep = 3; await flush();
    const pending = deferred<{ originalName: string; targetName: string }[]>();
    vi.mocked(previewTableMap).mockReturnValueOnce(pending.promise);
    state.model.syncDefaultVal = true; await flush();
    expect(state.tableMappingLoading).toBe(true);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
    await harness.options.onOpenChange(false);
    pending.resolve([{ originalName: 'orders', targetName: 'stale' }]); await flush();
    expect(state.mappingRows).toEqual([]);
    expect(state.tableMappingLoading).toBe(false);
  });

  it.each([
    ['the backend business message', { code: 1, msg: '  数据库或 Schema 不存在：warehouse  ' }, '数据库或 Schema 不存在：warehouse'],
    ['an Error message', new Error('  连接源数据库超时  '), '连接源数据库超时'],
    ['an empty business message', { code: 1, msg: '  ' }, '目标表映射加载失败，请重试'],
    ['a non-string business message', { code: 1, msg: { detail: 'invalid' } }, '目标表映射加载失败，请重试'],
    ['an empty Error message', new Error('  '), '目标表映射加载失败，请重试'],
    ['an unknown rejection', null, '目标表映射加载失败，请重试'],
  ])('retains %s in mapping state without a banner or another toast', async (_label, error, expected) => {
    await openMappingForm();
    vi.mocked(previewTableMap).mockRejectedValueOnce(error);
    await state.loadTableMappings(); await flush();
    expect(state.tableMappingError).toBe(expected);
    expect([...host.querySelectorAll('[role="alert"]')].map((alert) => alert.textContent))
      .not.toContain(expected);
    expect(state.mappingRows).toEqual([]);
    expect(state.tableMappingLoading).toBe(false);
    expect(message.error).not.toHaveBeenCalled();
  });

  it.each(['before', 'after'])('ignores an old mapping failure %s the current request completes', async (timing) => {
    await openMappingForm();
    const stale = deferred<{ originalName: string; targetName: string }[]>();
    const current = deferred<{ originalName: string; targetName: string }[]>();
    vi.mocked(previewTableMap).mockReturnValueOnce(stale.promise).mockReturnValueOnce(current.promise);
    const oldRequest = state.loadTableMappings();
    const newRequest = state.loadTableMappings();
    if (timing === 'before') {
      stale.reject({ code: 1, msg: '旧数据库已失效' }); await oldRequest;
    }
    expect(state.tableMappingLoading).toBe(true);
    expect(state.tableMappingError).toBe('');
    const rows = [{ originalName: 'orders', targetName: 'latest_orders' }];
    current.resolve(rows); await newRequest;
    if (timing === 'after') {
      stale.reject({ code: 1, msg: '旧数据库已失效' }); await oldRequest;
    }
    expect(state.mappingRows).toEqual(rows);
    expect(state.tableMappingError).toBe('');
    expect(state.tableMappingLoading).toBe(false);
  });

  it('ignores a mapping failure after closing the form', async () => {
    await openMappingForm();
    const stale = deferred<{ originalName: string; targetName: string }[]>();
    vi.mocked(previewTableMap).mockReturnValueOnce(stale.promise);
    const pending = state.loadTableMappings();
    await harness.options.onOpenChange(false);
    stale.reject({ code: 1, msg: '已关闭页面的错误' }); await pending;
    expect(state.mappingRows).toEqual([]);
    expect(state.tableMappingError).toBe('');
    expect(state.tableMappingLoading).toBe(false);
  });

  it('keeps saved mapping decisions through edit restore and submits them unchanged', async () => {
    await openMappingForm(true);
    expect(state.model.typeMappingAcknowledgements).toEqual([mappingAcknowledgement]);
    expect(host.querySelector('[data-testid="type-mapping-decisions"]')).not.toBeNull();
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledWith(expect.objectContaining({ typeMappingAcknowledgements: [mappingAcknowledgement] }));
  });

  it('starts cross-product tasks with empty decisions and does not infer approval from source/target choices', async () => {
    await openMappingForm();
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledWith(expect.objectContaining({ typeMappingAcknowledgements: [] }));
  });

  it.each([
    ['target datasource', { targetDatabaseId: '13' }],
    ['column mapping', { columnNameMapper: [{ fromPattern: 'amount', toValue: 'total' }] }],
    ['table mapping', { tableNameMapper: [{ fromPattern: 'orders', toValue: 'changed' }] }],
    ['table name conversion', { targetTableNameCase: 'SNAKE' }],
    ['column name conversion', { targetColumnNameCase: 'CAMEL' }],
    ['default policy', { syncDefaultVal: true }],
    ['source keys', { sourcePrimaryKeys: ['amount'] }],
    ['per-table mapping', { mapperType: 'SINGLE', configMap: { orders: { regexColumnMapper: [{ fromPattern: 'amount', toValue: 'total' }] } } }],
    ['source scope', { sourceSelectedTables: ['items'] }],
    ['SQL source', { sourceType: 2, sourceSql: 'SELECT amount FROM orders' }],
    ['other engine', { engineType: 2 }],
    ['cursor mode', { targetDataSync: true, changeDataSyncType: 'INCREASE_COLUMN' }],
  ])('clears approvals when %s changes instead of broadening them to a new plan', async (_reason, change) => {
    await openMappingForm(true); Object.assign(state.model, change); await flush();
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
  });

  it('retains decisions when editing unrelated task descriptions and includes SINGLE configuration in preview context', async () => {
    await openMappingForm(true); state.model.remark = 'new description'; state.model.taskName = 'new label'; await flush();
    expect(state.model.typeMappingAcknowledgements).toEqual([mappingAcknowledgement]);
    const configMap = { orders: { regexColumnMapper: [{ fromPattern: 'amount', toValue: 'total' }], sourcePrimaryKeys: ['amount'] } };
    Object.assign(state.model, { mapperType: 'SINGLE', configMap }); await flush();
    expect(state.typeMappingRequest.configMap).toEqual(configMap);
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
  });

  it('sends the actual target, overridden source keys and default policy in both name previews', async () => {
    await openMappingForm();
    Object.assign(state.model, {
      targetDatabaseId: '9007199254740998',
      sourcePrimaryKeys: ['renamed_key'],
      syncDefaultVal: true,
    });
    state.previewColumnTable = 'orders';
    vi.mocked(previewTableMap).mockResolvedValue([]);
    vi.mocked(previewColumnMap).mockResolvedValue([]);
    await state.handlePreviewTables();
    await state.handlePreviewColumns();
    const context = expect.objectContaining({
      schemaVersion: 1,
      targetDatabaseId: '9007199254740998',
      sourcePrimaryKeys: ['renamed_key'],
      syncDefaultVal: true,
    });
    expect(previewTableMap).toHaveBeenCalledWith(context);
    expect(previewColumnMap).toHaveBeenCalledExactlyOnceWith(context);
  });

  it('defaults new tasks to no conversion and offers two selects instead of the old case switches', async () => {
    await openMappingForm();
    expect(state.model.targetTableNameCase).toBe('NONE');
    expect(state.model.targetColumnNameCase).toBe('NONE');
    expect(host.textContent).toContain('表名转换');
    expect(host.textContent).toContain('字段名转换');
    expect(host.textContent).not.toContain('表名字段名转小写');
    expect(host.textContent).not.toContain('表名字段名转大写');
    Object.assign(state.model, { targetTableNameCase: 'SNAKE', targetColumnNameCase: 'CAMEL' });
    await flush();
    expect(host.querySelector('[data-testid="table-name-case-summary"]')?.textContent?.trim()).toBe('驼峰转下划线');
    expect(host.querySelector('[data-testid="column-name-case-summary"]')?.textContent?.trim()).toBe('下划线转驼峰');
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      targetTableNameCase: 'SNAKE', targetColumnNameCase: 'CAMEL', targetLowerCase: false, targetUpperCase: false,
    }));
  });

  it.each([
    ['the legacy lowercase switch', { targetLowerCase: true }, 'LOWER', 'LOWER'],
    ['the legacy uppercase switch', { targetUpperCase: true }, 'UPPER', 'UPPER'],
    ['both legacy switches', { targetLowerCase: true, targetUpperCase: true }, 'UPPER', 'UPPER'],
    ['no legacy switch', { targetLowerCase: false, targetUpperCase: false }, 'NONE', 'NONE'],
    ['saved modes over legacy switches', { targetUpperCase: true, targetTableNameCase: 'SNAKE', targetColumnNameCase: null }, 'SNAKE', 'UPPER'],
  ])('restores %s as equivalent modes without dropping saved decisions and submits them explicitly', async (_case, saved, table, column) => {
    await openMappingForm(true, saved);
    expect(state.model.targetTableNameCase).toBe(table);
    expect(state.model.targetColumnNameCase).toBe(column);
    expect(state.model.typeMappingAcknowledgements).toEqual([mappingAcknowledgement]);
    expect(state.buildPreviewReq()).toMatchObject({ targetTableNameCase: table, targetColumnNameCase: column });
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      targetTableNameCase: table, targetColumnNameCase: column, targetLowerCase: false, targetUpperCase: false,
      typeMappingAcknowledgements: [mappingAcknowledgement],
    }));
  });

  it('sends both name modes, not the superseded switches, in both name previews', async () => {
    await openMappingForm();
    Object.assign(state.model, { targetTableNameCase: 'UPPER', targetColumnNameCase: 'SNAKE' });
    state.previewColumnTable = 'orders';
    vi.mocked(previewTableMap).mockResolvedValue([]);
    vi.mocked(previewColumnMap).mockResolvedValue([]);
    await state.handlePreviewTables();
    await state.handlePreviewColumns();
    const context = expect.objectContaining({ targetTableNameCase: 'UPPER', targetColumnNameCase: 'SNAKE' });
    expect(previewTableMap).toHaveBeenCalledWith(context);
    expect(previewColumnMap).toHaveBeenCalledExactlyOnceWith(context);
    expect(vi.mocked(previewTableMap).mock.calls[0]![0]).not.toHaveProperty('targetLowerCase');
    expect(vi.mocked(previewColumnMap).mock.calls[0]![0]).not.toHaveProperty('targetUpperCase');
    expect(state.typeMappingRequest).toMatchObject({ targetTableNameCase: 'UPPER', targetColumnNameCase: 'SNAKE' });
  });

  it.each([
    ['target datasource', { targetDatabaseId: '13' }],
    ['source keys', { sourcePrimaryKeys: ['replacement_key'] }],
    ['default policy', { syncDefaultVal: true }],
    ['column name conversion', { targetColumnNameCase: 'LOWER' }],
  ])('discards a pending name preview after its %s changes', async (_reason, change) => {
    await openMappingForm();
    const pending = deferred<{ originalName: string; targetName: string }[]>();
    vi.mocked(previewTableMap).mockReturnValueOnce(pending.promise);
    const started = state.handlePreviewTables();
    expect(state.previewLoading).toBe(true);
    Object.assign(state.model, change);
    await flush();
    pending.resolve([{ originalName: 'orders', targetName: 'outdated_orders' }]);
    await started;
    expect(state.previewData).toEqual([]);
    expect(state.previewLoading).toBe(false);
  });

  it('preserves exact source names through edit restore, preview and submission', async () => {
    const names = ['comma,name', 'literal*name', 'literal?name', ' edge ', '\u00A0edge\u00A0'];
    vi.mocked(getAccess).mockResolvedValue({
      id: harness.data.id, taskName: 'exact source names', engineType: 1,
      sourceType: 1, sourceDatabaseId: '11', targetDatabaseId: '12',
      accessMode: 2, sourceSelectedTables: names,
    });
    await harness.options.onOpenChange(true);
    expect(state.model.sourceSelectedTables).toEqual(names);
    vi.mocked(previewTableMap).mockResolvedValue([]);
    await state.handlePreviewTables();
    expect(previewTableMap).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ sourceSelectedTables: names }));
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ sourceSelectedTables: names }));
  });

  it('switches an edited per-table mapping to global for SQL without deleting its inactive table configuration', async () => {
    await openMappingForm(true);
    const configMap = { orders: { regexColumnMapper: [{ fromPattern: 'amount', toValue: 'total' }] } };
    Object.assign(state.model, { mapperType: 'SINGLE', configMap });
    Object.assign(state.model, { sourceType: 2, sourceSql: 'SELECT amount FROM orders', targetTable: 'orders' });
    await flush();
    expect(state.model.mapperType).toBe('ALL');
    expect(state.buildPreviewReq().mapperType).toBe('ALL');
    expect(state.buildPreviewReq().configMap).toEqual(configMap);
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledWith(expect.objectContaining({ sourceType: 2, mapperType: 'ALL', configMap }));
  });

  it('preserves an invalid restored SQL mapping for explicit correction and refuses to submit it', async () => {
    vi.mocked(getDatabaseSimpleList).mockResolvedValue([
      { id: '11', name: 'source', productId: 'POSTGRESQL' },
      { id: '12', name: 'target', productId: 'POSTGRESQL' },
    ]);
    const configMap = { orders: { regexColumnMapper: [{ fromPattern: 'amount', toValue: 'total' }] } };
    vi.mocked(getAccess).mockResolvedValue({
      id: harness.data.id, taskName: 'saved SQL', engineType: 1, sourceType: 2,
      sourceDatabaseId: '11', targetDatabaseId: '12', accessMode: 2,
      sourceSql: 'SELECT amount FROM orders', targetTable: 'orders', mapperType: 'SINGLE', configMap,
    });
    await harness.options.onOpenChange(true); await flush();
    expect(state.model.mapperType).toBe('SINGLE');
    expect(state.model.configMap).toEqual(configMap);
    await state.handleSubmit();
    expect(updateAccess).not.toHaveBeenCalled();
    expect(state.stepKeys[state.currentStep]).toBe('mapper');
  });

  it('clears current session decisions when the wizard closes or session changes', async () => {
    await openMappingForm(true); await harness.options.onOpenChange(false); await flush();
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
    await openMappingForm(true); harness.access.refreshToken = '2'; await flush();
    expect(state.model.typeMappingAcknowledgements).toEqual([]);
  });

  async function openIdentityForm(product = 'SQLSERVER', edit = false) {
    vi.mocked(getDatabaseSimpleList).mockResolvedValue([
      { id: '11', name: 'source', productId: product },
      { id: '12', name: 'target', productId: product },
      { id: '13', name: 'other target', productId: product },
    ]);
    const detail = {
      taskName: 'identity transfer', engineType: 1, sourceType: 1,
      sourceDatabaseId: '11', targetDatabaseId: '12', accessMode: 2,
      sourceSelectedTables: ['orders'], targetDataSync: false,
    };
    if (edit) {
      vi.mocked(getAccess).mockResolvedValue({ ...detail, id: harness.data.id,
        preserveIdentityValues: true, exclusiveTargetOwnership: true,
        identitySourceQuiesced: product === 'SQLSERVER' });
    } else harness.data = {};
    await harness.options.onOpenChange(true);
    if (!edit) Object.assign(state.model, detail);
    state.currentStep = 2;
    await flush();
  }
  async function checkDeclaration(name: string, checked = true) {
    const input = host.querySelector(`[data-testid="${name}"] input`) as HTMLInputElement | null;
    expect(input).not.toBeNull();
    input!.checked = checked;
    input!.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
  }
  it('never infers SQL Server declarations and requires explicit source and target confirmations before submit', async () => {
    await openIdentityForm();
    expect(state.model.preserveIdentityValues).toBe(false);
    expect(state.model.exclusiveTargetOwnership).toBe(false);
    expect(state.model.identitySourceQuiesced).toBe(false);
    await checkDeclaration('identity-preserve');
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
    await checkDeclaration('identity-exclusive');
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await checkDeclaration('identity-source-quiesced');
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    expect(host.textContent).toContain('系统不会自动停止或锁住源端业务');
    expect(host.querySelector('[data-testid="identity-summary"]')?.textContent).toContain('保留原值并校准后续发号');
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      preserveIdentityValues: true, exclusiveTargetOwnership: true, identitySourceQuiesced: true,
      targetDropTable: false, targetTruncateData: false,
    }));
    expect(state.model.preserveIdentityValues).toBe(false);
    expect(state.model.exclusiveTargetOwnership).toBe(false);
    expect(state.model.identitySourceQuiesced).toBe(false);
  });
  it.each(['POSTGRESQL', 'SQLSERVER'])('preserves saved %s declarations through editing and updating', async (product) => {
    await openIdentityForm(product, true);
    expect(state.model.preserveIdentityValues).toBe(true);
    expect(state.model.exclusiveTargetOwnership).toBe(true);
    expect(state.model.identitySourceQuiesced).toBe(product === 'SQLSERVER');
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    expect(Boolean(host.querySelector('[data-testid="identity-source-quiesced"]'))).toBe(product === 'SQLSERVER');
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      id: '9007199254740993', preserveIdentityValues: true,
      exclusiveTargetOwnership: true, identitySourceQuiesced: product === 'SQLSERVER',
    }));
  });
  it.each(['DIFF', 'CURSOR', 'SQL', 'PRODUCT', 'TARGET', 'ENGINE', 'STRUCTURE'])('clears stale identity declarations on %s context change', async (change) => {
    await openIdentityForm('SQLSERVER', true);
    if (change === 'DIFF' || change === 'CURSOR') {
      state.model.changeDataSyncType = change === 'DIFF' ? 'PK_ORDER_COMPARE' : 'INCREASE_COLUMN';
      state.model.targetDataSync = true;
    } else if (change === 'SQL') state.model.sourceType = 2;
    else if (change === 'PRODUCT') state.databaseList[1].productId = 'MYSQL';
    else if (change === 'TARGET') state.model.targetDatabaseId = '13';
    else if (change === 'ENGINE') state.model.engineType = 2;
    else state.model.targetOnlyCreate = true;
    expect(state.model.preserveIdentityValues).toBe(false);
    expect(state.model.exclusiveTargetOwnership).toBe(false);
    expect(state.model.identitySourceQuiesced).toBe(false);
    state.model.targetDataSync = false;
    expect(state.model.preserveIdentityValues).toBe(false);
  });
  it('clears companion declarations when preservation is disabled and rejects contradictory auto identity generation', async () => {
    await openIdentityForm('SQLSERVER', true);
    state.model.targetAutoIncrement = true;
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    state.model.targetAutoIncrement = false;
    await checkDeclaration('identity-preserve', false);
    expect(state.model.exclusiveTargetOwnership).toBe(false);
    expect(state.model.identitySourceQuiesced).toBe(false);
  });
  it('blocks unsupported stale declarations before a request and resets them on close', async () => {
    await openIdentityForm('MYSQL');
    expect(state.identityTransferAvailable).toBe(false);
    state.model.preserveIdentityValues = true;
    state.model.exclusiveTargetOwnership = true;
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
    await harness.options.onOpenChange(false);
    expect(state.model.preserveIdentityValues).toBe(false);
    expect(state.model.exclusiveTargetOwnership).toBe(false);
    expect(state.model.identitySourceQuiesced).toBe(false);
  });
  it('starts without destructive replacement and clears incompatible options when enabling sync', () => {
    expect(state.model.schemaVersion).toBe(1);
    expect(state.model.targetDropTable).toBe(false);
    expect(state.model.targetTruncateData).toBe(false);
    state.model.targetDropTable = true;
    state.model.targetTruncateData = true;
    state.model.tableReadMode = 'ONLY';
    state.model.targetDataSync = true;
    state.handleDataSyncChange(true);
    expect(state.model.targetDropTable).toBe(false);
    expect(state.model.targetTruncateData).toBe(false);
    expect(state.model.tableReadMode).toBe('INCLUDE_DESCENDANTS');
  });
  it('requires supported products and the explicit monotonic watermark contract for cursor sync', () => {
    Object.assign(state.model, {
      engineType: 1,
      sourceType: 1,
      sourceDatabaseId: '11',
      targetDatabaseId: '12',
      targetDataSync: true,
      changeDataSyncType: 'INCREASE_COLUMN',
      increaseColumnName: 'revision',
    });
    state.databaseList = [
      { id: '11', productId: 'POSTGRESQL', cursorTargets: ['POSTGRESQL', 'MYSQL'] },
      { id: '12', productId: 'POSTGRESQL' },
    ];
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    state.model.cursorWatermarkContract = 'MONOTONIC_WATERMARK_V1';
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    state.databaseList[1].productId = 'MYSQL';
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    state.model.cursorWatermarkContract = 'MONOTONIC_WATERMARK_V1';
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
  });
  const provenCursorPairs = [
    ['POSTGRESQL', 'POSTGRESQL'], ['POSTGRESQL', 'MYSQL'], ['POSTGRESQL', 'MARIADB'],
    ['MYSQL', 'POSTGRESQL'], ['MYSQL', 'MYSQL'], ['MYSQL', 'MARIADB'],
    ['MARIADB', 'POSTGRESQL'], ['MARIADB', 'MYSQL'], ['MARIADB', 'MARIADB'],
    ['TIDB', 'TIDB'], ['TIDB', 'POSTGRESQL'], ['POSTGRESQL', 'TIDB'],
    ['SQLITE3', 'SQLITE3'],
    ['DM', 'DM'], ['DM', 'POSTGRESQL'], ['POSTGRESQL', 'DM'],
    ['SQLSERVER', 'SQLSERVER'], ['SQLSERVER', 'POSTGRESQL'], ['POSTGRESQL', 'SQLSERVER'],
    ['SQLSERVER2000', 'SQLSERVER2000'], ['OSCAR', 'OSCAR'],
    ['OPENGAUSS', 'OPENGAUSS'], ['OPENGAUSS', 'POSTGRESQL'], ['POSTGRESQL', 'OPENGAUSS'],
    ['OCEANBASE', 'OCEANBASE'], ['OCEANBASE', 'POSTGRESQL'], ['POSTGRESQL', 'OCEANBASE'],
    ['MONGODB', 'MONGODB'], ['INFLUXDB3', 'INFLUXDB3'],
    ['ORACLE', 'ORACLE'], ['GREENPLUM', 'GREENPLUM'], ['DORIS', 'DORIS'], ['STARROCKS', 'STARROCKS'],
    ['ELASTICSEARCH', 'ELASTICSEARCH'],
    ['KINGBASE', 'KINGBASE'], ['GBASE8A', 'POSTGRESQL'], ['GBASE8A', 'GBASE8A'], ['HIGHGO', 'HIGHGO'],
    ['SYBASE', 'SYBASE'], ['DB2', 'DB2'], ['HIVE', 'HIVE'], ['UXDB', 'UXDB'],
    ['CLICKHOUSE', 'CLICKHOUSE'], ['TDENGINE', 'TDENGINE'], ['TDENGINE', 'POSTGRESQL'],
    ['DORIS', 'POSTGRESQL'],
  ];
  async function openCursorForm(source: string, target: string, edit = false) {
    vi.mocked(getDatabaseSimpleList).mockResolvedValue([
      { id: '9007199254740999', name: 'cursor source', productId: source,
        cursorTargets: provenCursorPairs.filter(([product]) => product === source).map(([, destination]) => destination!),
      },
      { id: '9007199254740998', name: 'cursor target', productId: target },
    ]);
    const detail = {
      taskName: 'typed cursor', engineType: 1, sourceType: 1, accessMode: 2,
      sourceDatabaseId: '9007199254740999', targetDatabaseId: '9007199254740998',
      sourceSelectedTables: ['rows'], targetDataSync: true,
      changeDataSyncType: 'INCREASE_COLUMN', increaseColumnName: 'revision',
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1' as const,
      cursorBootstrapAllowed: true,
    };
    if (edit) vi.mocked(getAccess).mockResolvedValue({ ...detail, id: harness.data.id });
    else harness.data = {};
    await harness.options.onOpenChange(true);
    if (!edit) Object.assign(state.model, detail);
    state.currentStep = 2;
    await flush();
  }
  it('refuses GBase 8a self-target when the backend declares only PostgreSQL', async () => {
    await openCursorForm('GBASE8A', 'GBASE8A');
    state.databaseList[0].cursorTargets = ['POSTGRESQL'];
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
  });
  it.each(['CLICKHOUSE', 'TDENGINE'])('accepts backend-declared %s cursor targets without a local product whitelist', async (product) => {
    await openCursorForm(product, product);
    state.databaseList[0].cursorTargets = [product];
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1',
    }));
  });
  it('refuses absent or empty backend cursor declarations for a known product', async () => {
    await openCursorForm('POSTGRESQL', 'POSTGRESQL');
    delete state.databaseList[0].cursorTargets;
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    state.databaseList[0].cursorTargets = [];
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
  });
  it('uses a declared direction for a registered product without hardcoded display help', async () => {
    await openCursorForm('NEW_REGISTERED_PRODUCT', 'POSTGRESQL');
    state.databaseList[0].cursorTargets = ['POSTGRESQL'];
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    await flush();
    expect(host.querySelector('[data-testid="cursor-source-prerequisite"]')?.textContent).toContain('代次');
  });
  it.each(['POSTGRESQL', [null], ['POSTGRESQL', 1]])('refuses malformed backend cursor targets %j', async (targets) => {
    await openCursorForm('POSTGRESQL', 'POSTGRESQL');
    state.databaseList[0].cursorTargets = targets;
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
  });
  it.each(provenCursorPairs)('submits proven %s to %s cursor configuration without inventing deployment identity', async (source, target) => {
    await openCursorForm(source!, target!);
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    expect(host.querySelector('[data-testid="cursor-source-prerequisite"]')?.textContent).toContain('代次');
    expect(Boolean(host.querySelector('[data-testid="cursor-cross-product-hint"]'))).toBe(source !== target);
    expect(Boolean(host.querySelector('[data-testid="cursor-dm-hint"]'))).toBe(source === 'DM' || target === 'DM');
    expect(host.querySelector('[data-testid="cursor-summary"]')?.textContent).toContain('已声明新增和更新推进水位');
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      sourceDatabaseId: '9007199254740999', targetDatabaseId: '9007199254740998',
      changeDataSyncType: 'INCREASE_COLUMN', increaseColumnName: 'revision',
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1', cursorBootstrapAllowed: true,
    }));
    const payload = vi.mocked(createAccess).mock.calls[0]![0];
    expect(payload).not.toHaveProperty('sourceGenerationPin');
    expect(payload).not.toHaveProperty('targetGenerationPin');
    expect(payload).not.toHaveProperty('cursorBindings');
    expect(state.model.cursorWatermarkContract).toBeUndefined();
    expect(state.model.cursorBootstrapAllowed).toBe(false);
  });
  it('requires the actual watermark confirmation control and carries the independent bootstrap choice', async () => {
    await openCursorForm('TIDB', 'TIDB');
    state.model.cursorWatermarkContract = undefined;
    state.model.cursorBootstrapAllowed = false;
    await flush();
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
    await checkDeclaration('cursor-watermark-contract');
    expect(state.model.cursorBootstrapAllowed).toBe(false);
    await checkDeclaration('cursor-bootstrap');
    await state.handleSubmit();
    expect(createAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1', cursorBootstrapAllowed: true,
    }));
  });
  it.each([
    ['TIDB', 'MYSQL'], ['MARIADB', 'TIDB'], ['MYSQL', 'TIDB'],
    ['SQLITE3', 'POSTGRESQL'], ['POSTGRESQL', 'SQLITE3'],
    ['DM', 'MYSQL'], ['MYSQL', 'DM'], ['DM', 'MARIADB'], ['MARIADB', 'DM'],
    ['DM', 'TIDB'], ['TIDB', 'DM'], ['DM', 'SQLITE3'], ['SQLITE3', 'DM'],
    ['SQLSERVER', 'MYSQL'], ['MYSQL', 'SQLSERVER'], ['SQLSERVER', 'OPENGAUSS'], ['OPENGAUSS', 'SQLSERVER'],
    ['OPENGAUSS', 'MYSQL'], ['OPENGAUSS', 'TIDB'], ['SQLSERVER', 'DM'], ['SQLSERVER2000', 'POSTGRESQL'], ['OSCAR', 'POSTGRESQL'],
    ['OCEANBASE', 'MYSQL'], ['MYSQL', 'OCEANBASE'], ['OCEANBASE', 'TIDB'], ['OCEANBASE', 'DM'],
    ['MONGODB', 'POSTGRESQL'], ['POSTGRESQL', 'MONGODB'], ['MONGODB', 'MYSQL'], ['INFLUXDB3', 'POSTGRESQL'],
    ['POSTGRESQL', 'INFLUXDB3'], ['INFLUXDB3', 'MONGODB'], ['MONGODB', 'INFLUXDB3'],
    ['ORACLE', 'POSTGRESQL'], ['POSTGRESQL', 'ORACLE'], ['ORACLE', 'DM'], ['GREENPLUM', 'POSTGRESQL'],
    ['POSTGRESQL', 'GREENPLUM'], ['DORIS', 'MYSQL'], ['STARROCKS', 'POSTGRESQL'], ['DORIS', 'STARROCKS'],
    ['ELASTICSEARCH', 'POSTGRESQL'], ['POSTGRESQL', 'ELASTICSEARCH'],
    ['KINGBASE', 'POSTGRESQL'], ['GBASE8A', 'MYSQL'], ['HIGHGO', 'POSTGRESQL'],
    ['SYBASE', 'POSTGRESQL'], ['DB2', 'POSTGRESQL'], ['HIVE', 'POSTGRESQL'], ['DB2', 'HIVE'],
    ['UXDB', 'POSTGRESQL'],
  ])('does not infer unverified %s to %s cursor support', async (source, target) => {
    await openCursorForm(source!, target!);
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await state.handleSubmit();
    expect(createAccess).not.toHaveBeenCalled();
  });
  it.each(['TIDB', 'SQLITE3'])('keeps saved %s cursor declarations while editing and displays its deployment requirements', async (product) => {
    await openCursorForm(product, product, true);
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    const help = host.querySelector('[data-testid="cursor-source-prerequisite"]')?.textContent;
    expect(help).toContain(product === 'TIDB' ? '至少需要 2 个连接' : '非空 INTEGER');
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1', cursorBootstrapAllowed: true,
    }));
  });
  it.each([
    ['SQLSERVER', ['ALLOW_SNAPSHOT_ISOLATION', '不会修改数据库设置', 'fence', 'SQL Server 2000']],
    ['SQLSERVER2000', ['8.00.760', '阻塞并发写入', '源库与目标库', '重新引导']],
    ['OSCAR', ['V8.0.251217', '阻塞并发写入', '受管源部署代次']],
    ['OPENGAUSS', ['REPEATABLE READ', 'ACCESS SHARE', 'JDBC 提交返回']],
    ['OCEANBASE', ['MySQL 模式', '4.2.1.7 Oracle 模式', '实际租户身份', '按表串行']],
    ['MONGODB', ['BSON 类型族', '_id 单唯一键', 'majority']],
    ['INFLUXDB3', ['tagset', '纳秒', 'accept_partial=false']],
    ['ORACLE', ['SERIALIZABLE', 'ROW SHARE', 'MERGE', 'CON_NAME']],
    ['GREENPLUM', ['REPEATABLE READ', '分布键', 'ON CONFLICT']],
    ['DORIS', ['唯一键模型', 'keyset', 'ClusterId']],
    ['STARROCKS', ['主键模型', 'keyset', 'ClusterId']],
    ['ELASTICSEARCH', ['search_after', 'date_nanos', '_id 单键']],
    ['KINGBASE', ['oracle 模式', 'ci 排序', 'ON CONFLICT']],
    ['GBASE8A', ['实际唯一性', '目标初始化受管部署代次', '重新引导']],
    ['HIGHGO', ['REPEATABLE READ', 'ACCESS SHARE', '24:00:00']],
    ['SYBASE', ['隔离级 3', 'MERGE', 'JDBC 提交返回']],
    ['DB2', ['RR 隔离', 'MERGE', '独占锁']],
    ['HIVE', ['文件快照', '全 ACID ORC', '目录身份']],
    ['UXDB', ['ux_catalog', 'ON CONFLICT', '24:00:00']],
  ] as const)('preserves saved %s cursor settings and explains its snapshot and generation prerequisites', async (product, phrases) => {
    await openCursorForm(product, product, true);
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    const help = host.querySelector('[data-testid="cursor-source-prerequisite"]')?.textContent ?? '';
    expect(help).toContain('代次');
    for (const phrase of phrases) expect(help).toContain(phrase);
    expect(host.querySelector('[data-testid="cursor-dm-hint"]')).toBeNull();
    expect(host.querySelector('[data-testid="cursor-cross-product-hint"]')).toBeNull();
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1', cursorBootstrapAllowed: true,
    }));
    expect(vi.mocked(updateAccess).mock.calls[0]![0]).not.toHaveProperty('sourceGenerationPin');
    expect(vi.mocked(updateAccess).mock.calls[0]![0]).not.toHaveProperty('targetGenerationPin');
  });
  it('preserves saved DM cursor settings and explains its numeric and deployment prerequisites', async () => {
    await openCursorForm('DM', 'DM', true);
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(true);
    const help = host.querySelector('[data-testid="cursor-source-prerequisite"]')?.textContent;
    expect(help).toContain('非空精确数值');
    expect(help).toContain('保存在库外');
    expect(help).toContain('停止旧任务');
    expect(host.querySelector('[data-testid="cursor-dm-hint"]')?.textContent).toContain('identity');
    await state.handleSubmit();
    expect(updateAccess).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      sourceDatabaseId: '9007199254740999', targetDatabaseId: '9007199254740998',
      cursorWatermarkContract: 'MONOTONIC_WATERMARK_V1', cursorBootstrapAllowed: true,
    }));
    expect(vi.mocked(updateAccess).mock.calls[0]![0]).not.toHaveProperty('sourceGenerationPin');
  });
  it.each(['SQL', 'STRUCTURE', 'SOURCE', 'MODE', 'WATERMARK'])('clears stale cursor declarations after %s context changes', async (change) => {
    await openCursorForm('SQLITE3', 'SQLITE3', true);
    if (change === 'SQL') {
      state.model.sourceType = 2;
      state.model.targetTable = 'rows';
    } else if (change === 'STRUCTURE') state.model.targetOnlyCreate = true;
    else if (change === 'SOURCE') state.model.sourceDatabaseId = 'another-source';
    else if (change === 'MODE') state.model.changeDataSyncType = 'PK_ORDER_COMPARE';
    else state.model.increaseColumnName = 'other_revision';
    expect(state.model.cursorWatermarkContract).toBeUndefined();
    expect(state.model.cursorBootstrapAllowed).toBe(false);
  });
  it.each(['SQL', 'STRUCTURE'])('blocks %s even if a stale cursor contract is manually restored', async (change) => {
    await openCursorForm('SQLITE3', 'SQLITE3', true);
    if (change === 'SQL') {
      state.model.sourceType = 2;
      state.model.targetTable = 'rows';
    } else state.model.targetOnlyCreate = true;
    state.model.cursorWatermarkContract = 'MONOTONIC_WATERMARK_V1';
    expect(state.validateStep(state.stepKeys.indexOf('attributes'))).toBe(false);
    await state.handleSubmit();
    expect(updateAccess).not.toHaveBeenCalled();
  });
  it('clears all session-owned options and model when the session changes', async () => {
    await harness.options.onOpenChange(true);
    harness.access.refreshToken = '2';
    await flush();
    expect(state.model.id).toBeUndefined();
    expect(state.databaseList).toEqual([]);
    expect(state.model.apiMappings).toEqual([]);
    expect(state.deptTree).toEqual([]);
    expect(harness.close).toHaveBeenCalled();
  });
  it('does not apply a closed modal response or unlock a newer opening', async () => {
    const old = deferred<any>();
    const fresh = deferred<any>();
    vi.mocked(getAccess)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(fresh.promise);
    const first = harness.options.onOpenChange(true);
    await flush();
    await harness.options.onOpenChange(false);
    harness.data = { id: '9007199254740995' };
    const second = harness.options.onOpenChange(true);
    await flush();
    const unlocked = harness.unlock.mock.calls.length;
    old.resolve({ id: '9007199254740993', taskName: 'stale', engineType: 3 });
    await first;
    expect(state.model.taskName).not.toBe('stale');
    expect(harness.unlock).toHaveBeenCalledTimes(unlocked);
    fresh.resolve({ id: '9007199254740995', taskName: 'fresh', engineType: 3 });
    await second;
    expect(state.model.id).toBe('9007199254740995');
  });
  it('keeps the newest source selection loading while an older source finishes', async () => {
    await harness.options.onOpenChange(true);
    const old = deferred<any>();
    const fresh = deferred<any>();
    vi.mocked(getDatabaseTableList)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(fresh.promise);
    state.model.sourceDatabaseId = '9007199254740997';
    const first = state.loadSourceTables();
    state.model.sourceDatabaseId = '9007199254740999';
    const second = state.loadSourceTables();
    old.resolve([{ tableName: 'old_table' }]);
    await first;
    expect(state.sourceTablesLoading).toBe(true);
    expect(state.sourceTables).toEqual([]);
    fresh.resolve([{ tableName: 'new_table' }]);
    await second;
    expect(state.sourceTables).toEqual([{ tableName: 'new_table' }]);
    expect(getDatabaseTableList).toHaveBeenLastCalledWith('9007199254740999');
  });
});
