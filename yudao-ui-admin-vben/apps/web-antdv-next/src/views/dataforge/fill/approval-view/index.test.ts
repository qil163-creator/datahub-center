import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ApprovalView from './index.vue';

const api = vi.hoisted(() => ({
  getSubmissionPreview: vi.fn(),
  getSimpleUserList: vi.fn(),
}));
vi.mock('#/api/dataforge/fill', () => ({ getSubmissionPreview: api.getSubmissionPreview }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: api.getSimpleUserList }));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({
    props: { current: Number, pageSize: Number, total: Number },
    emits: ['change'],
    setup: (props, { emit }) => () => h('div', { 'data-pager': `${props.current}/${props.pageSize}/${props.total}` }, [
      h('button', { 'data-next': '', onClick: () => emit('change', 2, props.pageSize) }, '下一页'),
      h('button', { 'data-size': '', onClick: () => emit('change', 1, 20) }, '20条/页'),
    ]),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-11-01 10:00:00' }));
vi.mock('antdv-next', () => {
  const Box = defineComponent({ setup: (_props, { slots }) => () => h('div', [slots.default?.()]) });
  return {
    Alert: defineComponent({
      props: { message: String, description: String },
      setup: (props, { slots }) => () => h('div', { role: 'alert' }, [props.message, props.description, slots.description?.()]),
    }),
    Descriptions: Box,
    DescriptionsItem: defineComponent({
      props: { label: String },
      setup: (props, { slots }) => () => h('div', [h('b', props.label), slots.default?.()]),
    }),
    Spin: Box,
    Tag: Box,
    Table: defineComponent({
      props: { dataSource: Array, columns: Array },
      setup: (props, { slots }) => () =>
        h('table', (props.dataSource as any[]).map((row) =>
          h('tr', { 'data-row': row.rowId }, [
            JSON.stringify(row.data),
            ...((props.columns as any[]) ?? []).map((column) =>
              h('td', { 'data-cell': `${row.rowId}:${column.key}` }, slots.bodyCell?.({ column, record: row }))),
          ]))),
    }),
  };
});

const schema = JSON.stringify([{ fieldCode: 'name', name: '名称' }, { fieldCode: 'amount', name: '金额' }]);
const snapshot = (id: number, attempt: number, rows: any[], extra: object = {}) => ({
  id,
  attemptNo: attempt,
  fieldSchema: schema,
  rowCount: rows.length,
  rows,
  submitterUserId: 7,
  submissionType: 1,
  approvalStatus: 1,
  submitTime: 1,
  taskName: '月报',
  orgName: '市局',
  periodKey: '2026-10',
  ...extra,
});

let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  api.getSimpleUserList.mockResolvedValue([{ id: 7, nickname: '张三' }]);
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(id: number | string) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ id });
  app = createApp({ render: () => h(ApprovalView, { id: state.id }) });
  app.mount(host);
  return state;
}
async function settle() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}

describe('审批人提交快照视图', () => {
  it('超过一百行时按统一分页展示，切换提交后重置页码和条数', async () => {
    const rows = Array.from({ length: 121 }, (_, index) => ({
      rowId: String(index + 1),
      rowOrder: index + 1,
      data: { name: `第${index + 1}行` },
    }));
    api.getSubmissionPreview.mockImplementation(async (id: number | string) =>
      snapshot(Number(id), 1, rows),
    );
    const state = mount(1);
    await settle();
    expect(host.querySelector('[data-pager]')?.getAttribute('data-pager')).toBe('1/100/121');
    expect(host.querySelectorAll('[data-row]')).toHaveLength(100);
    (host.querySelector('[data-next]') as HTMLButtonElement).click();
    await nextTick();
    expect(host.querySelectorAll('[data-row]')).toHaveLength(21);
    expect(host.querySelector('[data-row]')?.getAttribute('data-row')).toBe('101');
    (host.querySelector('[data-size]') as HTMLButtonElement).click();
    await nextTick();
    expect(host.querySelectorAll('[data-row]')).toHaveLength(20);
    state.id = 2;
    await settle();
    expect(host.querySelector('[data-pager]')?.getAttribute('data-pager')).toBe('1/100/121');
    expect(host.querySelector('[data-row]')?.getAttribute('data-row')).toBe('1');
  });

  it('显示提交人姓名、警告，并与上一次提交对比新增、删除和修改', async () => {
    api.getSubmissionPreview.mockImplementation(async (id: number | string) =>
      String(id) === '2'
        ? snapshot(2, 2, [{ rowId: 'a', rowOrder: 1, data: { name: '甲', amount: 11 } }, { rowId: 'c', rowOrder: 2, data: { name: '丙', amount: 3 } }], {
            previousSubmissionId: 1,
            warnings: [{ ruleCode: 'MAX_VALUE', severity: 2, message: '第 1 行【金额】不能大于 10' }],
          })
        : snapshot(1, 1, [{ rowId: 'a', rowOrder: 1, data: { name: '甲', amount: 10 } }, { rowId: 'b', rowOrder: 2, data: { name: '乙', amount: 2 } }]),
    );
    mount(2);
    await settle();
    expect(host.textContent).toContain('张三');
    expect(host.textContent).toContain('提交时存在 1 条警告');
    expect(host.textContent).toContain('第 1 行【金额】不能大于 10');
    expect(host.textContent).toContain('较上一次提交：新增 1 行，删除 1 行，修改 1 个单元格');
    expect(host.querySelector('[data-pager]')).toBeNull();
    expect(api.getSubmissionPreview).toHaveBeenCalledWith(2);
    expect(api.getSubmissionPreview).toHaveBeenCalledWith(1);
  });

  it('大数字编号按字符串传递，加载失败不显示横幅，缺少上一次提交时不显示对比', async () => {
    api.getSubmissionPreview.mockRejectedValueOnce(new Error('无权预览'));
    mount('9007199254740993');
    await settle();
    expect(api.getSubmissionPreview).toHaveBeenCalledWith('9007199254740993');
    expect(host.textContent).not.toContain('无权预览');

    api.getSubmissionPreview.mockResolvedValueOnce(snapshot(3, 1, [{ rowId: 'a', rowOrder: 1, data: { name: '甲' } }]));
    app?.unmount();
    host.remove();
    mount(3);
    await settle();
    expect(host.textContent).not.toContain('较上一次提交');
  });

  it('人员、部门、附件字段显示“名称（编号）”，查不到的编号显示为未知', async () => {
    api.getSubmissionPreview.mockResolvedValue(
      snapshot(4, 1, [{ rowId: 'r1', rowOrder: 1, data: { amount: 5, owner: '12,99', unit: '9007199254740993' } }], {
        fieldSchema: JSON.stringify([
          { fieldCode: 'owner', name: '负责人', refKind: 'USER' },
          { fieldCode: 'unit', name: '单位', refKind: 'DEPT' },
          { fieldCode: 'amount', name: '金额' },
        ]),
        refLabels: {
          owner: [{ label: '张三', value: '12' }],
          unit: [{ label: '市局', value: '9007199254740993' }],
        },
      }),
    );
    mount(4);
    await settle();
    expect(host.querySelector('[data-cell="r1:owner"]')?.textContent).toBe('张三（12），未知（99）');
    expect(host.querySelector('[data-cell="r1:unit"]')?.textContent).toBe('市局（9007199254740993）');
    expect(host.querySelector('[data-cell="r1:amount"]')?.textContent).toBe('');
  });

  it('切换提交编号时丢弃迟到的旧响应', async () => {
    let finishFirst!: (value: unknown) => void;
    api.getSubmissionPreview.mockImplementation((id: number | string) =>
      String(id) === '1'
        ? new Promise((resolve) => { finishFirst = resolve; })
        : Promise.resolve(snapshot(2, 1, [{ rowId: 'x', rowOrder: 1, data: { name: '新' } }], { taskName: '新任务' })),
    );
    const state = mount(1);
    await settle();
    state.id = 2;
    await settle();
    finishFirst(snapshot(1, 1, [{ rowId: 'y', rowOrder: 1, data: { name: '旧' } }], { taskName: '旧任务' }));
    await settle();
    expect(host.textContent).toContain('新任务');
    expect(host.textContent).not.toContain('旧任务');
  });
});
