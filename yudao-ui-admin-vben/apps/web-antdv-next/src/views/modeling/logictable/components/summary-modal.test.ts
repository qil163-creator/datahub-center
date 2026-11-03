import type { App } from 'vue';

import type { TableModelApi } from '#/api/modeling/tablemodel';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/tablemodel';

import SummaryModal from './summary-modal.vue';

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    accessToken: 'session',
    refreshToken: '',
    accessCodes: ['modeling:model:save'],
  }),
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }),
  getDictOptions: () => [
    { value: '1', label: '天（T+1）' },
    { value: '2', label: '小时（T+h）' },
  ],
}));
vi.mock('#/api/modeling/datadomain', () => ({
  getDataDomain: vi.fn(async (id) => ({
    id,
    name: '板块',
    code: 'BOARD',
    namingRules: [{ tableType: 'DWS', prefix: 'dws_' }],
  })),
}));
vi.mock('../options', () => ({
  useLogicOptions: () => ({
    dataDomains: ref([{ id: '10', name: '板块', code: 'BOARD', status: 0 }]),
    domains: ref([
      {
        id: '20',
        dataDomainId: '10',
        name: '主题',
        code: 'SUBJECT',
        status: 0,
      },
    ]),
    entities: ref([
      {
        id: '30',
        subjectDomainId: '20',
        name: '雨量站',
        code: 'rain_station',
        entityType: 'OBJECT',
        entitySubtype: 'NORMAL',
        status: 0,
      },
    ]),
    users: ref([]),
    documents: ref([]),
    layers: ref([{ id: '40', defaultTableType: 'SUMMARY', status: 0 }]),
    error: ref(''),
    load: vi.fn(async () => {}),
  }),
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModel: vi.fn(),
  getTableModelDesign: vi.fn(),
  createTableModel: vi.fn(),
  previewSummaryTableFields: vi.fn(),
  saveTableModelDraft: vi.fn(async () => true),
  updateTableModel: vi.fn(),
  modelSaveData: (model: Partial<TableModelApi.Model>) => ({
    ...model,
    expectedRevision: model.revision,
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const control = defineComponent({
    props: ['value', 'disabled', 'placeholder', 'mode'],
    emits: ['update:value', 'change'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: Array.isArray(props.value)
            ? props.value.join(',')
            : props.value,
          disabled: props.disabled,
          placeholder: props.placeholder,
          onChange: (event: Event) => {
            const raw = (event.target as HTMLInputElement).value;
            const value = props.mode === 'multiple' ? raw.split(',') : raw;
            emit('update:value', value);
            emit('change', value);
          },
        }),
  });
  return {
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('div', props.message),
    }),
    ConfigProvider: plain,
    Spin: plain,
    Radio: plain,
    RadioGroup: plain,
    Input: control,
    Select: control,
    TextArea: control,
    Button: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Modal: defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', [h('h1', props.title), slots.default?.()])
            : null,
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
let modal: InstanceType<typeof SummaryModal> | undefined;
const id = '9007199254740993';
const revision = '9007199254740995';
function model(modelId = id): TableModelApi.Model {
  return {
    id: modelId,
    revision,
    layoutRevision: 1,
    status: 'DRAFT',
    tableType: 'SUMMARY',
    tableCase: 2,
    dataDomainId: '10',
    subjectDomainId: '20',
    layerId: '40',
    dataTimeliness: '1',
    grainEntityIds: ['30'],
    name: `汇总${modelId}`,
    code: 'rain_station',
    tableNameNotation: 'dws_rain_station',
  };
}
async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(SummaryModal, {
          ref: (value) => {
            modal = value as InstanceType<typeof SummaryModal>;
          },
        }),
    }),
  );
  app.mount(host);
  await flush();
}
async function select(placeholder: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(
    `input[placeholder="${placeholder}"]`,
  )!;
  input.value = value;
  input.dispatchEvent(new Event('change'));
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getTableModel).mockImplementation(async (modelId) =>
    model(String(modelId)),
  );
  vi.mocked(api.getTableModelDesign).mockImplementation(async (modelId) => ({
    modelId, revision, status: 'DRAFT', fields: [{ fieldUid: 'grain', fieldName: 'rain_station_id', cnName: '雨量站ID', dataType: 'VARCHAR', fieldCategory: 'GRAIN', pkFlag: true }],
  }));
});
afterEach(() => {
  app?.unmount();
  modal = undefined;
  app = undefined;
  host.remove();
});

describe('汇总逻辑表编辑', () => {
  it('编辑保存携带无损版本编号和修订号，保留服务端生成字段', async () => {
    await mount();
    await modal?.open(id);
    await flush();
    const save = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '保存',
    );
    expect(save).toBeDefined();
    expect(save?.disabled).toBe(false);
    save?.click();
    await flush();
    expect(api.saveTableModelDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        modelId: id,
        expectedRevision: revision,
        tableNameNotation: 'dws_rain_station',
        grainEntityIds: ['30'],
        fields: [expect.objectContaining({ fieldUid: 'grain', pkFlag: true })],
      }),
    );
  });

  it('已废弃模型显示只读提示且不提供保存动作', async () => {
    vi.mocked(api.getTableModel).mockResolvedValue({
      ...model(),
      status: 'DISCARDED',
    });
    vi.mocked(api.getTableModelDesign).mockResolvedValue({ modelId: id, revision, status: 'DISCARDED', fields: [] });
    await mount();
    await modal?.open(id);
    await flush();
    expect(host.textContent).toContain('当前模型已废弃');
    expect(
      [...host.querySelectorAll('button')].some(
        (button) => button.textContent?.trim() === '保存',
      ),
    ).toBe(false);
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
  });

  it('重新打开另一模型时不会显示前一次迟到的详情', async () => {
    let resolve: (value: TableModelApi.Model) => void = () => {};
    vi.mocked(api.getTableModel).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mount();
    const first = modal?.open(id);
    await flush();
    await modal?.open('9007199254741001');
    await flush();
    resolve(model());
    await first;
    await flush();
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入中文表名"]',
      )?.value,
    ).toBe('汇总9007199254741001');
  });

  it('快速切换统计时效时只接受最后一次字段预览', async () => {
    let resolve: (value: never[]) => void = () => {};
    vi.mocked(api.previewSummaryTableFields).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    vi.mocked(api.previewSummaryTableFields).mockResolvedValueOnce([
      {
        fieldUid: 'new',
        fieldName: 'ds',
        cnName: '统计小时',
        dataType: 'DATE',
        fieldCategory: 'TIME',
      },
    ]);
    await mount();
    await modal?.open();
    await flush();
    await select('请选择数据板块', '10');
    await select('请先选择数据板块', '20');
    await select('请选择统计时效', '1');
    await select('请选择统计时效', '2');
    resolve([]);
    await flush();
    expect(host.textContent).toContain('统计小时');
    expect(api.previewSummaryTableFields).toHaveBeenLastCalledWith(
      expect.objectContaining({ dataTimeliness: '2' }),
    );
  });
});
