import type { App } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  EtlPreviewSession,
  EtlPreviewSessionKey,
} from '#/api/dataforge/etl/preview-session';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  cancelEtlPreviewSession,
  createEtlPreviewSession,
  getEtlPreviewCapabilities,
  getEtlPreviewSession,
} from '#/api/dataforge/etl/preview-session';

import PreviewDialog from './preview-dialog.vue';

const runSample = vi.hoisted(() => vi.fn());
const identity = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
  user: { userInfo: { userId: '7' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => identity.access,
  useUserStore: () => identity.user,
}));

vi.mock('#/api/dataforge/etl/preview-session', () => ({
  cancelEtlPreviewSession: vi.fn(),
  createEtlPreviewSession: vi.fn(),
  getEtlPreviewCapabilities: vi.fn(),
  getEtlPreviewSession: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  return {
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Button: slot('button'),
    InputNumber: slot('input'),
    Select: defineComponent({
      props: ['value', 'options'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            props.options.map((option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    Tag: slot('span'),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', slots.default?.()) : null,
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
function sessionReceipt(
  key: EtlPreviewSessionKey,
  status: EtlPreviewSession['status'] = 'SUCCEEDED',
): EtlPreviewSession {
  return {
    ...key,
    mode: 'INSTANCE_LOCAL',
    status,
    cleanupStatus:
      status === 'RUNNING' || status === 'CANCEL_REQUESTED'
        ? 'RUNNING'
        : 'CONFIRMED',
    workerExited: status !== 'RUNNING',
    expiresAt: Date.now() + 120_000,
  };
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}
let props: {
  graph: DataforgeEtlApi.GraphRequest;
  open: boolean;
  parameters: Record<string, string>;
};
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await vi.advanceTimersByTimeAsync(800);
  await nextTick();
}
function portItems() {
  return [...host.querySelectorAll('.preview-ports > *')].map((item) =>
    item.textContent?.replaceAll(/\s+/g, ' ').trim(),
  );
}
function inputSample(
  overrides: Partial<DataforgeEtlApi.PreviewInputSample> = {},
): DataforgeEtlApi.PreviewInputSample {
  return {
    sourceNodeNo: 'upstream',
    sourceNodeName: '上游来源',
    sourceOutputPort: 'main',
    inputPort: 'in',
    columns: [{ name: 'value' }],
    rows: [{ value: 'input-value' }],
    rowCount: 1,
    truncated: false,
    ...overrides,
  };
}
async function mountPreview() {
  host = document.createElement('div');
  document.body.append(host);
  props = reactive({
    open: false,
    graph: {
      nodes: [
        {
          id: 'source',
          properties: {
            name: '随机数',
            toolCode: 'random-records',
            toolType: 1,
          },
        },
      ],
      edges: [],
    },
    parameters: {},
  });
  app = createApp({ render: () => h(PreviewDialog, props) });
  app.mount(host);
  props.open = true;
  await flush();
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers();
  let request: any;
  vi.mocked(getEtlPreviewCapabilities).mockResolvedValue({
    instanceId: 'fixture-instance',
    mode: 'INSTANCE_LOCAL',
    issuedAt: Date.now(),
    retentionSeconds: 120,
    executionSeconds: 45,
    concurrency: 4,
  });
  vi.mocked(createEtlPreviewSession).mockImplementation(async (data) => {
    request = data;
    return sessionReceipt(data, 'RUNNING');
  });
  vi.mocked(getEtlPreviewSession).mockImplementation(async (key) => ({
    ...sessionReceipt(key),
    result: await runSample(request),
  }));
  vi.mocked(cancelEtlPreviewSession).mockImplementation(async (key) =>
    sessionReceipt(key, 'CANCELLED'),
  );
  identity.access = reactive({ accessToken: '1', refreshToken: '' });
  identity.user = reactive({ userInfo: { userId: '7' } });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.useRealTimers();
});

describe('etl 有界预览', () => {
  it('分别展示多路输入的来源端口、执行流量和展示配额，不与输出按行对齐', async () => {
    vi.mocked(runSample).mockResolvedValue({
      success: true,
      columns: [{ name: 'joined' }],
      rows: [{ joined: 'left-one/right-one' }],
      inputRows: 8,
      targetInputRows: 7,
      outputRows: 12,
      issues: [],
      truncated: true,
      comparisonMode: 'INDEPENDENT_SAMPLES',
      inputSamples: [
        inputSample({ sourceNodeNo: 'left', sourceNodeName: '左路', sourceOutputPort: 'pass', inputPort: 'input-1', rows: [{ value: 'left-one' }, { value: 'left-two' }], rowCount: 4, truncated: true }),
        inputSample({ sourceNodeNo: 'right', sourceNodeName: '右路', sourceOutputPort: 'main', inputPort: 'input-2', rows: [{ value: 'right-one' }], rowCount: 3, truncated: true }),
      ],
    });
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    const streams = [...host.querySelectorAll('.preview-input-stream')];
    expect(streams).toHaveLength(2);
    expect(streams[0]!.textContent).toContain('左路（left）');
    expect(streams[0]!.textContent).toContain('来源输出端口 pass → 目标输入端口 input-1');
    expect(streams[0]!.textContent).toContain('样本流经 4 行 · 展示 2 行');
    expect(streams[1]!.textContent).toContain('来源输出端口 main → 目标输入端口 input-2');
    expect(streams[1]!.textContent).toContain('样本流经 3 行 · 展示 1 行');
    expect(streams.every((stream) => stream.textContent?.includes('本端口展示配额已截断'))).toBe(true);
    expect(host.querySelectorAll('.preview-inputs tbody tr')).toHaveLength(3);
    expect(host.querySelectorAll('.preview-output tbody tr')).toHaveLength(1);
    expect(host.textContent).toContain('来源样本输入 8');
    expect(host.textContent).toContain('流入目标 7');
    expect(host.textContent).toContain('不能按行号一一对应');
    expect(host.textContent).toContain('共享响应字节预算');
    expect(host.textContent).not.toContain('未提供输入样本');
  });
  it('输入输出均保留 unsafe Long、精确小数字符串和 NULL', async () => {
    const row = JSON.parse('{"id":"9223372036854775807","amount":"12345678901234567890.12345678901234567890","empty":null}');
    const fields = [{ name: 'id', dataType: 'LONG', nullable: false }, { name: 'amount', dataType: 'NUMERIC', precision: 38, scale: 20, timezone: null }, { name: 'empty', nullable: null }];
    vi.mocked(runSample).mockResolvedValue({
      success: true, columns: fields, rows: [row], inputRows: 1, targetInputRows: 1, outputRows: 1, issues: [], truncated: false,
      comparisonMode: 'INDEPENDENT_SAMPLES', inputSamples: [inputSample({ columns: fields, rows: [row] })],
    });
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    for (const selector of ['.preview-inputs', '.preview-output']) {
      const cells = [...host.querySelectorAll(`${selector} tbody td`)].map((cell) => cell.textContent?.trim());
      expect(cells).toEqual(['9223372036854775807', '12345678901234567890.12345678901234567890', 'NULL']);
      const header = host.querySelector(`${selector} thead`)?.textContent;
      expect(header).toContain('可空性 禁止空值');
      expect(header).toContain('精度 38 · scale 20');
      expect(header).toContain('时区 未知');
      expect(header).toContain('可空性 未知');
    }
  });
  it('明确区分旧服务未提供输入样本与已返回空输入列表', async () => {
    vi.mocked(runSample).mockResolvedValue({ success: true, columns: [], rows: [], inputRows: 0, outputRows: 0, issues: [], truncated: false });
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    expect(host.textContent).toContain('未提供输入样本，无法判断输入是否为空');
    expect(host.textContent).not.toContain('本次未记录目标输入流');
    vi.mocked(runSample).mockResolvedValue({ success: true, columns: [], rows: [], inputRows: 0, outputRows: 0, issues: [], truncated: false, inputSamples: [], targetInputRows: 0, comparisonMode: 'INDEPENDENT_SAMPLES' });
    button('执行预览')!.click();
    await flush();
    expect(host.textContent).toContain('本次未记录目标输入流');
    expect(host.textContent).toContain('流入目标 0');
    expect(host.textContent).not.toContain('未提供输入样本');
  });
  it.each(['accessToken', 'refreshToken'] as const)('切换%s丢弃迟到的两侧样本', async (field) => {
    let finish!: (value: DataforgeEtlApi.PreviewResult) => void;
    vi.mocked(runSample).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    identity.access[field] = '2';
    await flush();
    finish({ success: true, columns: [{ name: 'value' }], rows: [{ value: 'private-output' }], inputRows: 1, outputRows: 1, issues: [], truncated: false, inputSamples: [inputSample({ rows: [{ value: 'private-input' }] })] });
    await flush();
    expect(host.textContent).not.toContain('private-input');
    expect(host.textContent).not.toContain('private-output');
    expect(host.querySelector('.preview-comparison')).toBeNull();
  });
  it('取消返回未确认时继续等待，只有资源确认退出才显示已取消', async () => {
    vi.mocked(getEtlPreviewSession).mockImplementation(async (key) =>
      sessionReceipt(key, 'RUNNING'),
    );
    vi.mocked(cancelEtlPreviewSession).mockImplementation(async (key) => ({
      ...sessionReceipt(key, 'CANCEL_REQUESTED'),
      workerExited: false,
    }));
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    button('取消服务端预览')!.click();
    await flush();
    expect(cancelEtlPreviewSession).toHaveBeenCalledOnce();
    expect(host.textContent).not.toContain('已取消且资源退出已确认');
    vi.mocked(getEtlPreviewSession).mockImplementation(async (key) => ({
      ...sessionReceipt(key, 'TERMINATION_UNCONFIRMED'),
      cleanupStatus: 'UNCONFIRMED',
      workerExited: true,
    }));
    await flush();
    expect(host.textContent).toContain('资源退出尚未确认');
    expect(button('执行预览')?.disabled).toBe(true);
    vi.mocked(getEtlPreviewSession).mockImplementation(async (key) =>
      sessionReceipt(key, 'CANCELLED'),
    );
    await flush();
    expect(host.textContent).toContain('已取消且资源退出已确认');
    expect(button('执行预览')?.disabled).toBe(false);
  });
  it('创建响应丢失时保留原请求号，核查与取消不依赖创建回执', async () => {
    vi.mocked(createEtlPreviewSession).mockRejectedValue(new Error('timeout'));
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    const first = vi.mocked(createEtlPreviewSession).mock.calls[0]![0];
    button('原样重试受理')!.click();
    await flush();
    expect(vi.mocked(createEtlPreviewSession).mock.calls[1]![0]).toEqual(first);
    button('取消服务端预览')!.click();
    await flush();
    expect(cancelEtlPreviewSession).toHaveBeenCalledWith({
      instanceId: first.instanceId,
      requestId: first.requestId,
    });
    expect(host.textContent).toContain('已取消且资源退出已确认');
  });
  it('关闭窗口会发送服务端取消；未知实例错误不显示已清理', async () => {
    vi.mocked(getEtlPreviewSession).mockRejectedValue(
      new Error('instance mismatch'),
    );
    vi.mocked(cancelEtlPreviewSession).mockRejectedValue(
      new Error('instance mismatch'),
    );
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    expect(host.textContent).toContain('不能据此确认资源退出');
    props.open = false;
    await flush();
    expect(cancelEtlPreviewSession).toHaveBeenCalledOnce();
    props.open = true;
    await flush();
    expect(host.textContent).not.toContain('已取消且资源退出已确认');
  });
  it('变更目标会取消原会话且丢弃原结果，身份变更不冒用新身份取消旧会话', async () => {
    vi.mocked(getEtlPreviewSession).mockImplementation(async (key) =>
      sessionReceipt(key, 'RUNNING'),
    );
    await mountPreview();
    button('执行预览')!.click();
    await flush();
    props.parameters = { p: 'new' };
    await flush();
    expect(cancelEtlPreviewSession).toHaveBeenCalledOnce();
    vi.mocked(cancelEtlPreviewSession).mockClear();
    identity.access.refreshToken = '2';
    await flush();
    expect(cancelEtlPreviewSession).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('会话编号：');
  });
  it('整表采样携带环境，不将权限过滤后的短页显示为全表总数', async () => {
    vi.mocked(runSample).mockResolvedValue({
      success: true,
      columns: [{ name: 'masked' }],
      rows: [{ masked: '***' }],
      inputRows: 3,
      outputRows: 1,
      issues: [],
      truncated: true,
    });
    await mountPreview();
    props.open = false;
    props.graph = {
      environmentId: '9007199254740993',
      nodes: [
        {
          id: 'table',
          properties: {
            name: '整表',
            toolCode: 'read-db',
            nodeJson: {
              ifSql: 0,
              logicalDatabaseKey: 'warehouse',
              tableName: 'source',
            },
          },
        },
        {
          id: 'sql',
          properties: {
            name: 'SQL 来源',
            toolCode: 'read-db',
            nodeJson: { ifSql: 1 },
          },
        },
      ],
      edges: [],
    };
    await flush();
    props.open = true;
    await flush();
    expect(
      [...host.querySelectorAll('option')].map((option) => option.value),
    ).toEqual(['table']);
    host.querySelector('button')!.click();
    await flush();
    expect(runSample).toHaveBeenCalledWith(
      expect.objectContaining({
        environmentId: '9007199254740993',
        targetNodeId: 'table',
        limit: 100,
      }),
    );
    expect(host.textContent).toContain('PostgreSQL 整表采样');
    expect(host.textContent).toContain('样本输入 3');
    expect(host.textContent).toContain('不是全表总数');
    expect(host.textContent).toContain('***');
  });

  it('纯内存转换节点可选为预览目标，加解密、脚本和写入节点不可选', async () => {
    await mountPreview();
    props.open = false;
    const transform = (id: string, toolCode: string, toolType = 2) => ({
      id,
      properties: { name: id, toolCode, toolType },
    });
    props.graph = {
      nodes: [
        transform('source', 'generate-records', 1),
        transform('filter', 'data-filter'),
        transform('derive', 'field-derive'),
        transform('calc', 'calculator'),
        transform('rename', 'column-map'),
        transform('branch', 'data-condition'),
        transform('join', 'multi-join'),
        transform('product', 'cartesian-product'),
        transform('encrypt', 'data-encrypt'),
        transform('decrypt', 'data-decrypt'),
        transform('script', 'js-script', 4),
        transform('sql', 'sql-script', 4),
        transform('api', 'rest-api', 1),
        transform('csv', 'write-csv', 3),
        transform('sink', 'insert-db', 3),
      ],
      edges: [],
    };
    await flush();
    props.open = true;
    await flush();
    expect(
      [...host.querySelectorAll('option')].map((option) => option.value),
    ).toEqual([
      'source',
      'filter',
      'derive',
      'calc',
      'rename',
      'branch',
      'join',
      'product',
    ]);
    expect(host.textContent).toContain('加解密和目标写入不参与预览');
    expect(host.textContent).toContain('最多 32 个节点、展示 100 行');
    expect(host.textContent).toContain('不是全表总数');
  });

  it('用户切换后丢弃已经发出的真实源预览响应', async () => {
    let resolve!: (value: DataforgeEtlApi.PreviewResult) => void;
    vi.mocked(runSample).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mountPreview();
    host.querySelector('button')!.click();
    await flush();
    identity.user.userInfo.userId = '8';
    await flush();
    resolve({
      success: true,
      columns: [{ name: 'private' }],
      rows: [{ private: 'old-user-sample' }],
      inputSamples: [inputSample({ rows: [{ value: 'old-user-input' }] })],
      inputRows: 1,
      outputRows: 1,
      issues: [],
      truncated: false,
    });
    await flush();
    expect(host.textContent).not.toContain('old-user-sample');
    expect(host.textContent).not.toContain('old-user-input');
  });
  it('展示有界样本及截断标识，保留大整数字符串', async () => {
    vi.mocked(runSample).mockResolvedValue({
      success: true,
      columns: [{ name: 'id', dataType: 'LONG' }],
      rows: [{ id: '9007199254740993' }],
      inputRows: 100,
      outputRows: 100,
      issues: [],
      truncated: true,
      portCounts: { main: 100 },
      rowPorts: ['main'],
    });
    await mountPreview();
    host.querySelector('button')!.click();
    await flush();
    expect(runSample).toHaveBeenCalledWith(
      expect.objectContaining({ targetNodeId: 'source', limit: 100 }),
    );
    expect(host.textContent).toContain('9007199254740993');
    expect(host.textContent).toContain('结果已截断');
    expect(host.textContent).toContain('不能证明全表唯一性');
    expect(host.querySelector('.preview-ports')).toBeNull();
    expect(host.textContent).not.toContain('输出端口');
  });

  it('质量节点按合格、警告、不合格统计样本，并逐行标记所属端口', async () => {
    vi.mocked(runSample).mockResolvedValue({
      success: true,
      columns: [{ name: 'id' }],
      rows: [{ id: 1 }, { id: 2 }, { id: 3 }],
      inputRows: 3,
      outputRows: 3,
      issues: [],
      truncated: false,
      portCounts: { reject: 1, pass: 2 },
      rowPorts: ['pass', 'reject', 'pass'],
      previewId: 'preview-regression',
      inputSamples: [inputSample()],
      targetInputRows: 3,
      comparisonMode: 'INDEPENDENT_SAMPLES',
      rowViolations: [
        [],
        [
          {
            ruleCode: 'required',
            field: 'id',
            severity: 'ERROR',
            code: 'NULL',
          },
        ],
        [],
      ],
      budget: {
        maxRows: 10_000,
        maxBytes: 10_485_760,
        maxRowBytes: 262_144,
        usedRows: 6,
        usedBytes: 1000,
        peakBufferedBytes: 512,
        responseBytes: 700,
        maxResponseRows: 100,
        timeoutSeconds: 30,
      },
    });
    await mountPreview();
    props.open = false;
    props.graph = {
      nodes: [
        {
          id: 'quality',
          properties: {
            name: '质量检查',
            toolCode: 'quality-check',
            toolType: 2,
            nodeJson: { disposition: 'ROUTE' },
          },
        },
      ],
      edges: [],
    };
    await flush();
    props.open = true;
    await flush();
    host.querySelector('button')!.click();
    await flush();
    expect(portItems()).toEqual(['按输出端口', '合格 2', '不合格 1']);
    expect(host.querySelector('.preview-output thead th')?.textContent?.trim()).toBe(
      '输出端口',
    );
    expect(
      [...host.querySelectorAll('.preview-output tbody tr')].map((row) =>
        row.querySelector('td')?.textContent?.trim(),
      ),
    ).toEqual(['合格', '不合格', '合格']);
    expect(host.textContent).toContain('预览编号：preview-regression');
    expect(host.textContent).toContain('累计传输 6 / 10000 行');
    expect(
      [...host.querySelectorAll('.preview-output tbody tr')].map((row) =>
        row.querySelectorAll('td')[1]?.textContent?.trim(),
      ),
    ).toEqual(['无违规规则', 'required / id / ERROR / NULL', '无违规规则']);
    const select = host.querySelector<HTMLSelectElement>('#etl-preview-output-port')!;
    select.value = 'reject';
    select.dispatchEvent(new Event('change'));
    await flush();
    expect(host.querySelectorAll('.preview-output tbody tr')).toHaveLength(1);
    expect(host.querySelector('.preview-output tbody')?.textContent).toContain('required / id / ERROR / NULL');
    expect(host.querySelector('.preview-output tbody tr td:last-child')?.textContent?.trim()).toBe('2');
    expect(host.querySelectorAll('.preview-inputs tbody tr')).toHaveLength(1);
    expect(host.querySelector('.preview-inputs')?.textContent).toContain('input-value');
    select.value = 'pass';
    select.dispatchEvent(new Event('change'));
    await flush();
    expect(host.querySelectorAll('.preview-output tbody tr')).toHaveLength(2);
    expect(host.querySelector('.preview-output tbody')?.textContent).not.toContain('required / id');
  });

  it('条件分流按配置的分支顺序展示分支名称', async () => {
    vi.mocked(runSample).mockResolvedValue({
      success: true,
      columns: [{ name: 'seq' }],
      rows: [{ seq: 1 }, { seq: 4 }],
      inputRows: 5,
      outputRows: 5,
      issues: [],
      truncated: true,
      portCounts: { low: 3, high: 2 },
      rowPorts: ['low', 'high'],
    });
    await mountPreview();
    props.open = false;
    props.graph = {
      nodes: [
        {
          id: 'branch',
          properties: {
            name: '分流',
            toolCode: 'data-condition',
            toolType: 2,
            nodeJson: {
              branches: [
                { id: 'high', name: '高价值' },
                { id: 'low', name: '普通' },
              ],
            },
          },
        },
      ],
      edges: [],
    };
    await flush();
    props.open = true;
    await flush();
    host.querySelector('button')!.click();
    await flush();
    expect(portItems()).toEqual(['按输出端口', '高价值 2', '普通 3']);
    expect(
      [...host.querySelectorAll('tbody tr')].map((row) =>
        row.querySelector('td')?.textContent?.trim(),
      ),
    ).toEqual(['普通', '高价值']);
  });

  it('关闭后返回的旧请求不会显示在新预览窗口', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof runSample>>) => void;
    vi.mocked(runSample).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mountPreview();
    host.querySelector('button')!.click();
    await flush();
    props.open = false;
    await flush();
    props.open = true;
    await flush();
    resolve({
      success: true,
      columns: [{ name: 'secret' }],
      rows: [{ secret: '旧窗口数据' }],
      inputSamples: [inputSample({ rows: [{ value: '旧窗口输入' }] })],
      inputRows: 1,
      outputRows: 1,
      issues: [],
      truncated: false,
    });
    await flush();
    expect(host.textContent).not.toContain('旧窗口数据');
    expect(host.textContent).not.toContain('旧窗口输入');
    expect(host.textContent).not.toContain('预览完成');
  });
});
