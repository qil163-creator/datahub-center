import type { App } from 'vue';

import type { SubflowContract } from '#/api/dataforge/etl/subflow';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl/subflow';

import SubPipeline from './sub-pipeline.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '7' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('#/api/dataforge/etl/subflow', () => ({
  getSubflowContract: vi.fn(),
  getSubflowPipelinePage: vi.fn(),
  getSubflowVersionPage: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', props.message),
  }),
  Input: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (e: Event) =>
            emit('update:value', (e.target as HTMLInputElement).value),
        }),
  }),
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
            onChange: (e: Event) =>
              emit('update:value', (e.target as HTMLSelectElement).value),
          },
          [
            h('option', { value: '' }, ''),
            ...props.options.map((item: { label: string; value: string }) =>
              h('option', { value: item.value }, item.label),
            ),
          ],
        ),
  }),
}));
let app: App;
let host: HTMLDivElement;
let config: Record<string, any>;
async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
function input(index: number, text: string) {
  const field = host.querySelectorAll('input')[index]!;
  field.value = text;
  field.dispatchEvent(new Event('input'));
}
function select(index: number, value: string) {
  const field = host.querySelectorAll('select')[index]!;
  field.value = value;
  field.dispatchEvent(new Event('change'));
}
const contract: SubflowContract = {
  pipelineId: '9007199254740993',
  versionId: '9007199254740995',
  contentHash: 'pinned-hash',
  inputs: [{ name: 'in', fields: [{ name: 'code', type: 'STRING' }] }],
  outputs: [{ name: 'out', fields: [] }],
  parameters: ['amount'],
  parameterDefinitions: [
    {
      name: 'amount',
      type: 'DECIMAL',
      required: true,
      min: '0.123456789',
      defaultValue: '999',
    },
  ],
};
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '7' } });
  config = reactive({ pipelineId: '1', versionId: '2', parameters: {} });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(SubPipeline, { config });
  app.mount(host);
  vi.mocked(api.getSubflowPipelinePage).mockResolvedValue({
    list: [{ id: contract.pipelineId, name: '转换流程' }],
    total: 1,
  });
  vi.mocked(api.getSubflowVersionPage).mockResolvedValue({
    list: [
      {
        id: contract.versionId,
        pipelineId: contract.pipelineId,
        revision: 9,
        eventType: 'PUBLISH',
        contentHash: 'pinned-hash',
        actorId: '7',
        createTime: 1,
      },
      {
        id: '3',
        pipelineId: '1',
        revision: 1,
        eventType: 'RESTORE',
        contentHash: 'old',
        actorId: '7',
        createTime: 1,
      },
    ],
    total: 2,
  });
  vi.mocked(api.getSubflowContract).mockResolvedValue(contract);
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('子流程发布版本选择', () => {
  it('固定版本展示并原样保存完整字段契约，旧字段未知不会变成不可空', async () => {
    const detailed: SubflowContract = {
      ...contract,
      inputs: [{ name: 'in', fields: [
        { name: 'amount', type: 'Decimal', nullable: false, precision: 20, scale: -2, timezone: null },
        { name: 'legacy', type: 'String' },
      ] }],
    };
    vi.mocked(api.getSubflowContract).mockResolvedValue(detailed);
    input(1, String(contract.versionId));
    await flush();
    button('读取契约').click();
    await flush();
    expect(host.textContent).toContain('可空性 禁止空值');
    expect(host.textContent).toContain('精度 20 · scale -2');
    expect(host.textContent).toContain('类型 String · 可空性 未知');
    expect(host.textContent).toContain('时区 未知');
    button('使用此固定版本').click();
    await flush();
    expect(config.inputs).toEqual(detailed.inputs);
    expect(config.inputs[0].fields[1]).not.toHaveProperty('nullable');
    expect(config.versionId).toBe('9007199254740995');
    expect(config.contentHash).toBe('pinned-hash');
  });
  it('搜索、PUBLISH版本、契约预览后明确应用固定版本；不继承默认值', async () => {
    input(0, '转换');
    await flush();
    button('搜索').click();
    await flush();
    select(0, String(contract.pipelineId));
    await flush();
    expect(host.textContent).not.toContain('修订 1 · 版本 3');
    select(1, String(contract.versionId));
    await flush();
    button('读取契约').click();
    await flush();
    expect(config.versionId).toBe('2');
    expect(host.textContent).toContain('最小值 0.123456789');
    expect(host.textContent).toContain('code');
    expect(host.textContent).toContain('类型 STRING · 可空性 未知');
    button('使用此固定版本').click();
    await flush();
    expect(config).toMatchObject({
      pipelineId: contract.pipelineId,
      versionId: contract.versionId,
      contentHash: 'pinned-hash',
      parameters: { amount: '' },
    });
    expect(api.getSubflowVersionPage).toHaveBeenCalledWith({
      pipelineId: contract.pipelineId,
      pageNo: 1,
      pageSize: 20,
    });
  });
  it('搜索条件变化后旧列表不能覆盖新结果', async () => {
    let finish!: (
      value: Awaited<ReturnType<typeof api.getSubflowPipelinePage>>,
    ) => void;
    vi.mocked(api.getSubflowPipelinePage).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    input(0, '旧');
    await flush();
    button('搜索').click();
    await flush();
    input(0, '新');
    await flush();
    button('搜索').click();
    await flush();
    finish({ list: [{ id: '4', name: '过期私有流程' }], total: 1 });
    await flush();
    expect(host.textContent).not.toContain('过期私有流程');
    expect(host.textContent).toContain('转换流程');
  });
  it('租户/身份变化和卸载均使迟到契约失效，保留原图直到明确应用', async () => {
    let finish!: (value: SubflowContract) => void;
    vi.mocked(api.getSubflowContract).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    input(1, String(contract.versionId));
    await flush();
    button('读取契约').click();
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    finish(contract);
    await flush();
    expect(host.textContent).not.toContain('pinned-hash');
    expect(config.versionId).toBe('2');
    expect(host.textContent).not.toContain('当前引用：');
    input(1, String(contract.versionId));
    await flush();
    button('读取契约').click();
    await flush();
    app.unmount();
    finish(contract);
    await flush();
    expect(config.versionId).toBe('2');
  });
});
