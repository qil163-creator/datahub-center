import type { App, SetupContext } from 'vue';

import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { RunReceipt } from '#/api/dataforge/execution/command';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/access';

import Task from './task.vue';

const harness = vi.hoisted(() => ({
  access: {} as any,
  options: {} as any,
  drawerData: { id: '9007199254740993', taskName: 'Test access' },
  permissions: ['dataforge:access:run', 'dataforge:access:query', 'dataforge:access:delete', 'dataforge:access:resolve'],
  actions: [] as any[],
  success: vi.fn(),
  warning: vi.fn(),
  errors: [] as unknown[],
}));

vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access, useUserStore: () => ({ userInfo: { userId: 1 } }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: (codes: string[]) => codes.some((code) => harness.permissions.includes(code)) }) }));
vi.mock('@vben/utils', () => ({ formatDateTime: (value: unknown) => String(value ?? '') }));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: any) => {
    harness.options = options;
    return [(_: unknown, { slots }: SetupContext) => h('section', slots.default?.()), {
      getData: () => harness.drawerData,
      close: () => options.onOpenChange(false),
    }];
  },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign((props: { actions?: any[]; dropDownActions?: any[] }) => {
      const actions = [...(props.actions ?? []), ...(props.dropDownActions ?? [])];
      harness.actions = actions;
      return h('div', actions.filter((action) => action.ifShow !== false && (!action.auth || action.auth.some((code: string) => harness.permissions.includes(code)))).map((action) => h('button', { disabled: action.disabled, onClick: action.onClick ?? action.popConfirm?.confirm }, action.label)));
  }, { props: { actions: Array, dropDownActions: Array } }),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) => (_: unknown, { slots, attrs }: SetupContext) => h(tag, attrs, slots.default?.());
  return {
    Button: slot('button'), Tag: slot('span'), Alert: slot('div'),
    Checkbox: (props: { checked?: boolean; disabled?: boolean }, { emit, slots }: SetupContext) => h('label', [h('input', { type: 'checkbox', checked: props.checked, disabled: props.disabled, onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked) }), slots.default?.()]),
    TextArea: (props: { value?: string }, { emit, attrs }: SetupContext) => h('textarea', { ...attrs, value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value) }),
    Input: (props: { value?: string }, { emit, attrs }: SetupContext) => h('input', { ...attrs, value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }),
    Select: (props: { disabled?: boolean; options?: { label: string; value: string }[]; value?: string }, { emit }: SetupContext) => h('select', { value: props.value, disabled: props.disabled, onChange: (event: Event) => emit('update:value', (event.target as HTMLSelectElement).value) }, props.options?.map((option) => h('option', { value: option.value }, option.label))),
    message: { success: harness.success, warning: harness.warning },
    Modal: (props: { open?: boolean; title?: string }, { emit, slots }: SetupContext) => props.open ? h('aside', { 'aria-label': props.title }, [h('button', { onClick: () => emit('update:open', false) }, '关闭'), slots.default?.()]) : null,
    Table: Object.assign((props: { columns: any[]; dataSource?: any[]; loading?: boolean }, { slots }: SetupContext) => h('div', { 'data-loading': String(props.loading) }, (props.dataSource ?? []).flatMap((record) => props.columns.map((column) => h('div', slots.bodyCell?.({ column, record }) ?? String(record[column.dataIndex] ?? ''))))), { props: { columns: Array, dataSource: Array, loading: Boolean } }),
  };
});
vi.mock('#/api/dataforge/access', () => ({
  getAccessTaskPage: vi.fn(), getAccessTask: vi.fn(), getAccessTaskDetailPage: vi.fn(),
  deleteAccessTaskList: vi.fn(), stopAccess: vi.fn(), resolveAccess: vi.fn(),
  getAccessGroupRecovery: vi.fn(), recoverAccessGroups: vi.fn(),
}));

const record = { id: '9007199254740995', dataAccessId: '9007199254740993', runStatus: 4, occupiesSlot: true, reviewRequired: true, effectState: 'PARTIAL', rowVersion: '9007199254740997' };
let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
async function mountTask(open = true) {
  host = document.createElement('div'); document.body.append(host);
  app = createApp(Task);
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  if (open) await harness.options.onOpenChange(true);
  await flush();
}
function action(label: string) { return harness.actions.find((item) => item.label === label); }
function button(label: string, root: ParentNode = host) {
  const result = [...root.querySelectorAll('button')].find((item) => item.textContent?.trim() === label);
  if (!result) throw new Error(`Missing button: ${label}`);
  return result;
}
async function click(label: string, root: ParentNode = host) {
  button(label, root).click();
  await flush();
}
function dialog() {
  const result = host.querySelector<HTMLElement>('aside[aria-label="人工核查执行结果"]');
  if (!result) throw new Error('Review dialog is not open');
  return result;
}
async function input(label: string, value: string) {
  const element = dialog().querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`)!;
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
async function check(index: number, checked: boolean) {
  const element = dialog().querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[index]!;
  element.checked = checked;
  element.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}
async function fillReview() {
  await check(0, true); await check(1, true);
  await input('核查原因', '  已确认资源退出并处置部分输出  ');
  await input('证据引用', '  audit/synthetic-record  ');
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
const receipt: RunReceipt = { recordId: record.id, replayed: false, runStatus: 4, stopRequested: false };

beforeEach(() => {
  vi.resetAllMocks();
  harness.errors = [];
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.permissions = ['dataforge:access:run', 'dataforge:access:query', 'dataforge:access:delete', 'dataforge:access:resolve'];
  vi.mocked(api.getAccessTaskPage).mockResolvedValue({ list: [record], total: 1 });
  vi.mocked(api.getAccessTask).mockResolvedValue({ ...record });
  vi.mocked(api.resolveAccess).mockResolvedValue(receipt);
  vi.mocked(api.getAccessGroupRecovery).mockResolvedValue({ recordId: record.id, rowVersion: record.rowVersion, applicable: false, ready: false, released: false, message: '没有登记数据组' });
  vi.mocked(api.recoverAccessGroups).mockResolvedValue({ ...receipt, runStatus: 5 });
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.useRealTimers(); });

describe('access execution record component', () => {
  const mappingPlan: DataforgeAccessApi.TypeMappingPlan = { schemaVersion: 1,
    sourceProduct: 'SQLITE3', sourceSchema: 'main', sourceTable: 'orders',
    targetProduct: 'POSTGRESQL', targetSchema: 'public', targetTable: 'orders_copy',
    fidelity: 'LOSSY', accepted: false, totalColumns: 1, truncated: false,
    columns: [{ column: 'amount', sourceNativeType: 'INTEGER', logicalType: 'INT64', targetNativeType: 'bigint',
      fidelity: 'LOSSY', accepted: false, mappingFingerprint: 'a'.repeat(64), lossCodes: ['SQLITE_AFFINITY'],
      issues: [{ severity: 'WARNING', code: 'SQLITE_AFFINITY', detail: '<img src=x onerror=unsafe> 类型约束变化' }] }],
  };
  it('shows persisted table mapping diagnostics as text and keeps them separate from execution outcome', async () => {
    vi.mocked(api.getAccessTaskDetailPage).mockResolvedValue({ list: [{ id: '1', sourceTableName: 'orders', targetTableName: 'orders_copy', ifSuccess: 0, errorMsg: '类型映射未确认', typeMappingPlan: mappingPlan }], total: 1 });
    await mountTask(); await click('表级结果'); await click('类型映射诊断');
    const diagnostic = host.querySelector('aside[aria-label="类型映射诊断"]')!;
    expect(diagnostic.textContent).toContain('类型映射检查：未通过');
    expect(diagnostic.textContent).toContain('执行是否成功以表级同步结果为准');
    expect(diagnostic.textContent).toContain('<img src=x onerror=unsafe>'); expect(diagnostic.querySelector('img')).toBeNull();
    expect(diagnostic.querySelector('input[type="checkbox"]')).toBeNull();
    expect(harness.errors).toEqual([]);
  });

  it('marks missing legacy diagnostics and rejects malformed plans without showing approval controls', async () => {
    vi.mocked(api.getAccessTaskDetailPage).mockResolvedValue({ list: [
      { id: '1', ifSuccess: 1, typeMappingPlan: null },
      { id: '2', ifSuccess: 0, typeMappingPlan: { ...mappingPlan, schemaVersion: 2 } as unknown as DataforgeAccessApi.TypeMappingPlan },
    ], total: 2 });
    await mountTask(); await click('表级结果'); expect(host.textContent).toContain('未生成'); expect(host.textContent).toContain('诊断不可读取');
    expect([...host.querySelectorAll('button')].some((element) => element.textContent?.trim() === '类型映射诊断')).toBe(false);
  });

  it('labels truncated execution summaries and clears an open diagnosis when the result dialog or session closes', async () => {
    vi.mocked(api.getAccessTaskDetailPage).mockResolvedValue({ list: [{ id: '1', ifSuccess: 0, typeMappingPlan: { ...mappingPlan, truncated: true, totalColumns: 5000 } }], total: 1 });
    await mountTask(); await click('表级结果'); await click('类型映射诊断');
    expect(host.textContent).toContain('本表共 5000 列');
    await click('关闭', host.querySelector('aside[aria-label="表级同步结果"]')!);
    expect(host.querySelector('[data-testid="type-mapping-diagnostic"]')).toBeNull();
    await click('表级结果'); await click('类型映射诊断'); harness.access.refreshToken = '2'; await flush();
    expect(host.querySelector('[data-testid="type-mapping-diagnostic"]')).toBeNull();
  });

  it('uses server drain evidence and a lossless fresh version without a client stop confirmation', async () => {
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue({ recordId: record.id, rowVersion: '9007199254741011', applicable: true, ready: true, released: false, message: '上一执行器已退出' });
    await mountTask(); await action('核查').onClick(); await flush();
    await input('核查原因', '恢复已登记组'); await input('证据引用', 'audit/synthetic-group');
    expect((button('提交核查') as HTMLButtonElement).disabled).toBe(true);
    await click('恢复数据组');
    expect(api.recoverAccessGroups).toHaveBeenCalledWith({ recordId: record.id, expectedVersion: '9007199254741011', requestId: expect.any(String), reason: '恢复已登记组', evidenceRef: 'audit/synthetic-group' });
    expect(vi.mocked(api.recoverAccessGroups).mock.calls[0]?.[0]).not.toHaveProperty('confirmedStopped');
    expect(harness.success).toHaveBeenCalledWith(expect.stringContaining('不代表恢复完成'));
  });

  it('retries a lost recovery receipt with the same payload and identity even when server status changes', async () => {
    vi.useFakeTimers();
    const ready = { recordId: record.id, rowVersion: record.rowVersion, applicable: true, ready: true, released: false, message: '可恢复' };
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue(ready);
    vi.mocked(api.recoverAccessGroups).mockRejectedValueOnce(new Error('receipt lost')).mockResolvedValue({ ...receipt, runStatus: 5, replayed: true });
    await mountTask(); await action('核查').onClick(); await flush();
    await input('核查原因', '恢复'); await input('证据引用', 'audit/synthetic-group');
    await click('恢复数据组');
    expect(host.textContent).toContain('上次恢复请求的受理结果尚未确认');
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue({ ...ready, rowVersion: '9007199254741100', ready: false, message: '恢复已受理' });
    await vi.advanceTimersByTimeAsync(5000); await flush();
    expect(host.textContent).toContain('恢复已受理');
    await click('重试确认恢复受理');
    const calls = vi.mocked(api.recoverAccessGroups).mock.calls;
    expect(calls).toHaveLength(2); expect(calls[1]?.[0]).toEqual(calls[0]?.[0]);
  });

  it('polls signed release and loads a current record version before allowing the separate output review', async () => {
    vi.useFakeTimers();
    const initial = { recordId: record.id, rowVersion: record.rowVersion, applicable: true, ready: false, released: false, message: '恢复已受理' };
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue(initial);
    await mountTask(); await action('核查').onClick(); await flush(); await fillReview();
    expect((button('提交核查') as HTMLButtonElement).disabled).toBe(true);
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue({ ...initial, released: true, rowVersion: '9007199254741111', message: '数据组已释放，仍须核查输出' });
    vi.mocked(api.getAccessTask).mockResolvedValue({ ...record, rowVersion: '9007199254741111' });
    await vi.advanceTimersByTimeAsync(5000); await flush();
    expect(host.textContent).toContain('9007199254741111');
    expect((button('提交核查') as HTMLButtonElement).disabled).toBe(false);
    await click('提交核查');
    expect(api.resolveAccess).toHaveBeenCalledWith(expect.objectContaining({ expectedVersion: '9007199254741111' }));
  });

  it('does not treat an old recovery status response as evidence for another record or session', async () => {
    const old = deferred<DataforgeAccessApi.GroupRecoveryState>();
    vi.mocked(api.getAccessGroupRecovery).mockReturnValueOnce(old.promise);
    await mountTask(); const pending = action('核查').onClick(); await flush();
    harness.access.refreshToken = '2'; await flush();
    old.resolve({ recordId: record.id, rowVersion: record.rowVersion, applicable: true, ready: true, released: false, message: 'OLD READY' });
    await pending; await flush();
    expect(host.textContent).not.toContain('OLD READY');
    expect(host.querySelector('aside[aria-label="人工核查执行结果"]')).toBeNull();
    expect(api.recoverAccessGroups).not.toHaveBeenCalled();
  });

  it('keeps recovery unavailable without server drain or resolve permission', async () => {
    vi.mocked(api.getAccessGroupRecovery).mockResolvedValue({ recordId: record.id, rowVersion: record.rowVersion, applicable: true, ready: false, released: false, message: '执行资源尚未确认退出' });
    await mountTask(); await action('核查').onClick(); await flush(); await fillReview();
    expect((button('恢复数据组') as HTMLButtonElement).disabled).toBe(true);
    expect((button('提交核查') as HTMLButtonElement).disabled).toBe(true);
    expect(host.textContent).toContain('执行资源尚未确认退出');
    harness.permissions = []; await harness.options.onOpenChange(true); await flush();
    expect([...host.querySelectorAll('button')].some((element) => element.textContent === '核查')).toBe(false);
  });
  it('renders FAILED/PARTIAL occupancy as pending review and removes deletion', async () => {
    await mountTask();
    expect(host.textContent).toContain('结果待核查');
    expect([...host.querySelectorAll('button')].some((button) => button.textContent === '删除')).toBe(false);
    expect(action('核查')?.auth).toEqual(['dataforge:access:resolve']);
  });

  it('stops the selected record and retries a lost response with the same request ID', async () => {
    vi.mocked(api.getAccessTaskPage).mockResolvedValue({ list: [{ ...record, runStatus: 2, reviewRequired: false }], total: 1 });
    vi.mocked(api.stopAccess).mockRejectedValueOnce(new Error('response lost')).mockResolvedValue({ recordId: record.id, runStatus: 2, stopRequested: true, replayed: true });
    await mountTask();
    expect(action('停止')).toBeDefined();
    await expect(action('停止').popConfirm.confirm()).rejects.toThrow('response lost');
    await action('停止').popConfirm.confirm();
    const [first, retry] = vi.mocked(api.stopAccess).mock.calls;
    expect(first?.[0]).toEqual({ recordId: record.id, requestId: expect.any(String) });
    expect(retry?.[0]).toEqual(first?.[0]);
    expect(harness.success).toHaveBeenCalledWith(expect.stringContaining('待确认'));
  });

  it('loads a fresh record version for the independently authorized review form', async () => {
    await mountTask();
    expect(action('核查')).toBeDefined();
    await action('核查').onClick(); await flush();
    expect(api.getAccessTask).toHaveBeenCalledWith(record.id);
    expect(host.textContent).toContain('9007199254740997');
    expect(host.textContent).toContain('已确认执行资源退出');
    expect(host.textContent).toContain('已核查输出');
  });

  it('clears records on session switch and discards the previous session response', async () => {
    await mountTask();
    let finish!: (value: any) => void;
    vi.mocked(api.getAccessTaskPage).mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    const pending = harness.options.onOpenChange(true);
    harness.access.refreshToken = '2'; await flush();
    finish({ list: [record], total: 1 }); await pending; await flush();
    expect(host.textContent).not.toContain('结果待核查');
    expect(host.textContent).not.toContain('表级结果');
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
  });

  it('submits both confirmations, evidence and the freshly queried version without losing Long precision', async () => {
    vi.mocked(api.getAccessTask).mockResolvedValue({ ...record, rowVersion: '9007199254741001' });
    await mountTask();
    await click('核查');
    await fillReview();
    const outcome = dialog().querySelector('select')!;
    outcome.value = 'STOPPED';
    outcome.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    await click('提交核查');
    expect(api.resolveAccess).toHaveBeenCalledExactlyOnceWith({
      recordId: '9007199254740995', expectedVersion: '9007199254741001',
      confirmedStopped: true, outputReviewed: true, outcome: 'STOPPED',
      reason: '已确认资源退出并处置部分输出', evidenceRef: 'audit/synthetic-record',
      requestId: expect.any(String),
    });
    expect(vi.mocked(api.resolveAccess).mock.calls[0]![0].requestId.length).toBeLessThanOrEqual(64);
    expect(host.querySelector('aside')).toBeNull();
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    expect(harness.errors).toEqual([]);
  });

  it('requires each checkbox and nonblank bounded reason and evidence before sending a review', async () => {
    await mountTask(); await click('核查');
    await input('核查原因', 'reason'); await input('证据引用', 'evidence');
    await check(0, true);
    expect(button('提交核查').disabled).toBe(true);
    await click('提交核查');
    await check(0, false); await check(1, true);
    expect(button('提交核查').disabled).toBe(true);
    await click('提交核查');
    await check(0, true);
    for (const [label, invalid] of [['核查原因', ' '], ['证据引用', ' '], ['核查原因', 'r'.repeat(501)], ['证据引用', 'e'.repeat(1001)]]) {
      await input(label!, invalid!);
      expect(button('提交核查').disabled).toBe(true);
      await click('提交核查');
      await input(label!, 'valid');
    }
    expect(api.resolveAccess).not.toHaveBeenCalled();
    expect(button('提交核查').disabled).toBe(false);
  });

  it('accepts version zero and the inclusive reason and evidence length limits', async () => {
    vi.mocked(api.getAccessTask).mockResolvedValue({ ...record, rowVersion: 0 });
    await mountTask(); await click('核查'); await fillReview();
    await input('核查原因', 'r'.repeat(500));
    await input('证据引用', 'e'.repeat(1000));
    await click('提交核查');
    expect(api.resolveAccess).toHaveBeenCalledWith(expect.objectContaining({
      expectedVersion: 0, reason: 'r'.repeat(500), evidenceRef: 'e'.repeat(1000),
    }));
  });

  it('retries a lost review response with the original complete command and blocks changed evidence', async () => {
    const lost = new Error('response lost');
    vi.mocked(api.resolveAccess).mockRejectedValueOnce(lost).mockResolvedValue({ ...receipt, replayed: true });
    await mountTask(); await click('核查'); await fillReview();
    await click('提交核查');
    expect(harness.errors).toEqual([lost]);
    expect(harness.success).not.toHaveBeenCalled();
    await input('证据引用', 'different-evidence');
    await click('提交核查');
    expect(api.resolveAccess).toHaveBeenCalledTimes(1);
    expect(harness.warning).toHaveBeenCalledWith(expect.stringContaining('核对执行记录'));
    await input('证据引用', 'audit/synthetic-record');
    await click('提交核查');
    expect(vi.mocked(api.resolveAccess).mock.calls[1]![0]).toEqual(vi.mocked(api.resolveAccess).mock.calls[0]![0]);
    expect(harness.success).toHaveBeenCalledTimes(1);
  });

  it('locks duplicate submissions until a receipt arrives', async () => {
    const pending = deferred<RunReceipt>();
    vi.mocked(api.resolveAccess).mockReturnValue(pending.promise);
    await mountTask(); await click('核查'); await fillReview(); await click('提交核查');
    expect(button('提交核查').disabled).toBe(true);
    await click('提交核查');
    expect(api.resolveAccess).toHaveBeenCalledTimes(1);
    pending.resolve(receipt); await flush();
    expect(host.querySelector('aside')).toBeNull();
  });

  it('preserves a rejected version and requires new review confirmations after closing and reloading', async () => {
    const conflict = new Error('记录版本已变化');
    vi.mocked(api.resolveAccess).mockRejectedValueOnce(conflict).mockResolvedValue(receipt);
    await mountTask(); await click('核查'); await fillReview(); await click('提交核查');
    const original = vi.mocked(api.resolveAccess).mock.calls[0]![0];
    expect(harness.errors).toEqual([conflict]);
    expect(harness.success).not.toHaveBeenCalled();
    expect(api.getAccessTask).toHaveBeenCalledTimes(1);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    expect(dialog().textContent).toContain('9007199254740997');
    await click('关闭', dialog());
    vi.mocked(api.getAccessTask).mockResolvedValue({ ...record, rowVersion: '9007199254741003' });
    await click('核查');
    expect(button('提交核查').disabled).toBe(true);
    expect([...dialog().querySelectorAll<HTMLInputElement>('input[type="checkbox"]')].every((item) => !item.checked)).toBe(true);
    expect(dialog().querySelector<HTMLInputElement>('[aria-label="证据引用"]')!.value).toBe('');
    await fillReview(); await click('提交核查');
    const next = vi.mocked(api.resolveAccess).mock.calls[1]![0];
    expect(next.expectedVersion).toBe('9007199254741003');
    expect(next.requestId).not.toBe(original.requestId);
  });

  it('requires the dedicated resolve permission even when a run-permitted user invokes the action', async () => {
    harness.permissions = ['dataforge:access:run', 'dataforge:access:query'];
    await mountTask();
    expect([...host.querySelectorAll('button')].some((item) => item.textContent === '核查')).toBe(false);
    await action('核查').onClick(); await flush();
    expect(api.getAccessTask).not.toHaveBeenCalled();
    expect(api.resolveAccess).not.toHaveBeenCalled();
  });

  it('refuses review when the fresh record has already released its slot', async () => {
    vi.mocked(api.getAccessTask).mockResolvedValue({ ...record, runStatus: 3, occupiesSlot: false, reviewRequired: false });
    await mountTask(); await click('核查');
    expect(dialog().querySelector('fieldset')).toBeNull();
    expect(api.resolveAccess).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalledTimes(1);
  });

  it('does not let a closed review read replace or unlock a newer review', async () => {
    const old = deferred<DataforgeAccessApi.AccessTask>();
    const fresh = deferred<DataforgeAccessApi.AccessTask>();
    vi.mocked(api.getAccessTask).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    await mountTask(); await click('核查'); await click('关闭', dialog()); await click('核查');
    old.resolve({ ...record, rowVersion: '9007199254741001' }); await flush();
    expect(dialog().textContent).toContain('正在读取最新记录');
    expect(dialog().textContent).not.toContain('9007199254741001');
    fresh.resolve({ ...record, rowVersion: '9007199254741003' }); await flush();
    expect(dialog().textContent).toContain('9007199254741003');
    expect(button('提交核查').disabled).toBe(true);
  });

  it('does not let a closed review receipt close or unlock the next submission', async () => {
    const old = deferred<RunReceipt>(); const fresh = deferred<RunReceipt>();
    vi.mocked(api.resolveAccess).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    await mountTask(); await click('核查'); await fillReview(); await click('提交核查');
    await click('关闭', dialog()); await click('核查'); await fillReview(); await click('提交核查');
    old.resolve(receipt); await flush();
    expect(button('提交核查').disabled).toBe(true);
    expect(harness.success).not.toHaveBeenCalled();
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    fresh.resolve(receipt); await flush();
    expect(harness.success).toHaveBeenCalledTimes(1);
    expect(host.querySelector('aside')).toBeNull();
  });

  it('discards an in-flight review receipt and all evidence after a session switch', async () => {
    const pending = deferred<RunReceipt>();
    vi.mocked(api.resolveAccess).mockReturnValue(pending.promise);
    await mountTask(); await click('核查'); await fillReview(); await click('提交核查');
    harness.access.refreshToken = '2'; await flush();
    expect(host.querySelector('aside')).toBeNull();
    pending.resolve(receipt); await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    await harness.options.onOpenChange(true); await flush(); await click('核查');
    expect(button('提交核查').disabled).toBe(true);
    expect(dialog().querySelector<HTMLInputElement>('[aria-label="证据引用"]')!.value).toBe('');
  });

  it('polls queued, stopping and occupied records but stops polling settled records', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getAccessTaskPage)
      .mockResolvedValueOnce({ list: [{ ...record, runStatus: 1, occupiesSlot: false, reviewRequired: false }], total: 1 })
      .mockResolvedValueOnce({ list: [{ ...record, runStatus: 2, stopRequested: true, reviewRequired: false }], total: 1 })
      .mockResolvedValueOnce({ list: [record], total: 1 })
      .mockResolvedValue({ list: [{ ...record, runStatus: 6, occupiesSlot: false, reviewRequired: false }], total: 1 });
    await mountTask();
    await vi.advanceTimersByTimeAsync(5000);
    expect(host.textContent).toContain('取消已受理 / 待确认');
    await vi.advanceTimersByTimeAsync(5000);
    expect(host.textContent).toContain('结果待核查');
    await vi.advanceTimersByTimeAsync(5000);
    expect(host.textContent).toContain('已确认停止');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(4);
  });

  it('resumes polling after an initial load failure and a successful manual refresh', async () => {
    vi.useFakeTimers();
    const failure = new Error('initial query failed');
    vi.mocked(api.getAccessTaskPage).mockRejectedValueOnce(failure).mockResolvedValue({ list: [record], total: 1 });
    await mountTask(false);
    await expect(harness.options.onOpenChange(true)).rejects.toThrow('initial query failed');
    await click('刷新');
    expect(host.textContent).toContain('结果待核查');
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(3);
  });

  it('automatically retries an initially failed empty list and renders the recovered records without a manual refresh', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getAccessTaskPage)
      .mockRejectedValueOnce(new Error('initial query failed'))
      .mockRejectedValueOnce(new Error('retry query failed'))
      .mockResolvedValue({ list: [record], total: 1 });
    await mountTask(false);
    await expect(harness.options.onOpenChange(true)).rejects.toThrow('initial query failed');
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain('结果待核查');
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(3);
    expect(api.getAccessTaskPage).toHaveBeenLastCalledWith({
      dataAccessId: '9007199254740993', pageNo: 1, pageSize: 10,
    });
    expect(host.textContent).toContain('结果待核查');
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
    expect(harness.errors).toEqual([]);
  });

  it('stops retrying after a failed list query recovers with a successful empty result', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getAccessTaskPage)
      .mockRejectedValueOnce(new Error('initial query failed'))
      .mockResolvedValue({ list: [], total: 0 });
    await mountTask(false);
    await expect(harness.options.onOpenChange(true)).rejects.toThrow('initial query failed');
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
  });

  it('does not carry a failed-query retry into a closed and reopened modal', async () => {
    vi.useFakeTimers();
    vi.mocked(api.getAccessTaskPage)
      .mockRejectedValueOnce(new Error('initial query failed'))
      .mockResolvedValue({ list: [], total: 0 });
    await mountTask(false);
    await expect(harness.options.onOpenChange(true)).rejects.toThrow('initial query failed');
    await harness.options.onOpenChange(false);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    await harness.options.onOpenChange(true);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
  });

  it('does not let a late failure from a closed modal restart polling a newer empty list', async () => {
    vi.useFakeTimers();
    let rejectOld!: (reason: Error) => void;
    const old = new Promise<never>((_resolve, reject) => { rejectOld = reject; });
    vi.mocked(api.getAccessTaskPage)
      .mockReturnValueOnce(old)
      .mockResolvedValue({ list: [], total: 0 });
    await mountTask(false);
    const first = harness.options.onOpenChange(true).catch((error: unknown) => error);
    await harness.options.onOpenChange(false);
    await harness.options.onOpenChange(true);
    const failure = new Error('old query failed');
    rejectOld(failure);
    expect(await first).toBe(failure);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
  });

  it.each([5, 6])('keeps occupied status %s visible for review and polling without permitting deletion', async (runStatus) => {
    vi.useFakeTimers();
    vi.mocked(api.getAccessTaskPage).mockResolvedValue({ list: [{ ...record, runStatus, reviewRequired: false }], total: 1 });
    await mountTask();
    expect(host.textContent).toContain('结果待核查');
    expect([...host.querySelectorAll('button')].some((item) => item.textContent === '删除')).toBe(false);
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
  });

  it('keeps a new record list loading when a previously closed modal query finishes', async () => {
    vi.useFakeTimers();
    const old = deferred<{ list: DataforgeAccessApi.AccessTask[]; total: number }>();
    const fresh = deferred<{ list: DataforgeAccessApi.AccessTask[]; total: number }>();
    vi.mocked(api.getAccessTaskPage).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    await mountTask(false);
    const first = harness.options.onOpenChange(true); await flush();
    await harness.options.onOpenChange(false);
    const second = harness.options.onOpenChange(true); await flush();
    old.resolve({ list: [{ ...record, runStatus: 3, occupiesSlot: false, reviewRequired: false }], total: 1 });
    await first; await flush();
    expect(host.querySelector('[data-loading="true"]')).not.toBeNull();
    expect(host.textContent).not.toContain('执行成功');
    expect(vi.getTimerCount()).toBe(0);
    fresh.resolve({ list: [record], total: 1 }); await second; await flush();
    expect(host.textContent).toContain('结果待核查');
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
    expect(vi.getTimerCount()).toBe(1);
  });

  it('ignores a successful review receipt that arrives after unmount', async () => {
    const pending = deferred<RunReceipt>();
    vi.mocked(api.resolveAccess).mockReturnValue(pending.promise);
    await mountTask(); await click('核查'); await fillReview(); await click('提交核查');
    app!.unmount(); app = undefined;
    pending.resolve(receipt); await flush();
    expect(harness.success).not.toHaveBeenCalled();
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(1);
    expect(host.textContent).toBe('');
  });

  it('invalidates pending polling and review reads and clears timers on unmount', async () => {
    vi.useFakeTimers();
    const poll = deferred<{ list: DataforgeAccessApi.AccessTask[]; total: number }>();
    const read = deferred<DataforgeAccessApi.AccessTask>();
    await mountTask();
    vi.mocked(api.getAccessTaskPage).mockReturnValue(poll.promise);
    vi.mocked(api.getAccessTask).mockReturnValue(read.promise);
    await click('核查');
    await vi.advanceTimersByTimeAsync(5000);
    app!.unmount(); app = undefined;
    poll.resolve({ list: [record], total: 1 }); read.resolve(record); await flush();
    expect(host.textContent).toBe('');
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getAccessTaskPage).toHaveBeenCalledTimes(2);
    expect(harness.success).not.toHaveBeenCalled();
  });
});
