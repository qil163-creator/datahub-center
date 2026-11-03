/* eslint-disable vue/one-component-per-file -- 隔离控件验证原生实例的操作权限和只读边界。 */
import type { App, PropType } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { message, Modal } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import InstanceModal from './instance-modal.vue';

const permissions = vi.hoisted(() => new Set<string>());
const accessSession = reactive({ accessToken: 'session', refreshToken: 'refresh' });
vi.mock('@vben/stores', () => ({ useAccessStore: () => accessSession }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => permissions.has(code)),
  }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('@vben/utils', () => ({
  downloadFileFromBlobPart: vi.fn(),
  formatDateTime: String,
}));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevRecordPage: vi.fn(),
  getDevRecordStatistics: vi.fn(async () => ({
    total: 1,
    running: 1,
    success: 0,
    failed: 0,
  })),
  getDevRecord: vi.fn(),
  getDevRecordTaskLog: vi.fn(),
  downloadDevRecordLog: vi.fn(),
  stopDevRecord: vi.fn(async () => undefined),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const ModalDouble = Object.assign(
    defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', [slots.default?.(), slots.footer?.()])
            : null,
    }),
    { confirm: vi.fn() },
  );
  return {
    Alert: Container,
    Descriptions: Container,
    DescriptionsItem: Container,
    Input: Container,
    Pagination: Container,
    Switch: Container,
    TabPane: Container,
    Tabs: Container,
    Tag: Container,
    Button: defineComponent({
      emits: ['click'],
      setup:
        (_props, { emit, slots }) =>
        () =>
          h('button', { onClick: () => emit('click') }, slots.default?.()),
    }),
    Modal: ModalDouble,
    Table: defineComponent({
      props: {
        dataSource: { type: Array as PropType<DataforgeDevApi.Record[]>, default: () => [] },
        columns: { type: Array as PropType<{ key: string }[]>, default: () => [] },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource?.flatMap((record) =>
              props.columns?.map((column) =>
                slots.bodyCell?.({ column, record }),
              ),
            ),
          ),
    }),
    message: { success: vi.fn() },
  };
});

let app: App;
let modalProps: { readOnly: boolean };
let host: HTMLDivElement;
let instance: {
  close: () => void;
  open: (id: DataforgeDevApi.Id) => Promise<void>;
};
const nativeRecord: DataforgeDevApi.Record = {
  id: '9007199254740993',
  taskId: '9007199254740995',
  taskType: 'FLINK_SQL',
  nativeProgram: true,
  status: 'RUNNING',
};
async function flush() {
  for (let count = 0; count < 12; count++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  permissions.clear();
  accessSession.accessToken = 'session';
  accessSession.refreshToken = 'refresh';
  vi.mocked(devApi.getDevRecordPage).mockResolvedValue({
    list: [{ ...nativeRecord }],
    total: 1,
  });
  host = document.createElement('div');
  document.body.append(host);
  modalProps = reactive({ readOnly: false });
  app = createApp({
    render: () => h(InstanceModal, {
      readOnly: modalProps.readOnly,
      ref: (value: unknown) => { if (value) instance = value as typeof instance; },
    }),
  });
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
  vi.useRealTimers();
});
describe('原生实例权限与停止确认', () => {
  it('仅运行权限可看元信息，不能读取原生日志或停止', async () => {
    permissions.add('dataforge:dev-task:run');
    await instance.open(nativeRecord.taskId!);
    await flush();
    expect(host.textContent).toContain('原生执行需单独授权');
    expect(host.textContent).not.toContain('查看日志');
    expect(host.textContent).not.toContain('下载日志');
    expect(
      [...host.querySelectorAll('button')].some(
        (button) => button.textContent?.trim() === '停止',
      ),
    ).toBe(false);
    expect(devApi.getDevRecord).not.toHaveBeenCalled();
    expect(devApi.stopDevRecord).not.toHaveBeenCalled();
  });
  it('只读详情允许查看实例信息，但已有运行和原生授权也不能停止', async () => {
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    modalProps.readOnly = true;
    await instance.open(nativeRecord.taskId!);
    await flush();
    expect(host.textContent).toContain('查看日志');
    expect([...host.querySelectorAll('button')].some((button) => button.textContent?.trim() === '停止')).toBe(false);
    expect(Modal.confirm).not.toHaveBeenCalled();
    expect(devApi.stopDevRecord).not.toHaveBeenCalled();
  });
  it('停止确认期间转为只读后不再提交停止请求', async () => {
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    await instance.open(nativeRecord.taskId!);
    await flush();
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '停止')!.click();
    const confirmation = vi.mocked(Modal.confirm).mock.calls[0]![0];
    modalProps.readOnly = true;
    await flush();
    await confirmation.onOk?.();
    expect(devApi.stopDevRecord).not.toHaveBeenCalled();
    expect(message.success).not.toHaveBeenCalled();
  });
  it('运行列表自动更新到终态，关闭后不再读取', async () => {
    vi.useFakeTimers();
    permissions.add('dataforge:dev-task:execute-native');
    await instance.open(nativeRecord.taskId!);
    vi.mocked(devApi.getDevRecordPage).mockResolvedValue({ list: [{ ...nativeRecord, status: 'STOPPED' }], total: 1 });
    await vi.advanceTimersByTimeAsync(3000);
    await flush();
    expect(host.textContent).toContain('已停止');
    const calls = vi.mocked(devApi.getDevRecordPage).mock.calls.length;
    await vi.advanceTimersByTimeAsync(9000);
    expect(devApi.getDevRecordPage).toHaveBeenCalledTimes(calls);
    instance.close();
    await vi.advanceTimersByTimeAsync(9000);
    expect(devApi.getDevRecordPage).toHaveBeenCalledTimes(calls);
  });
  it('列表自动刷新不撤销仍有效的停止确认', async () => {
    vi.useFakeTimers();
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    await instance.open(nativeRecord.taskId!);
    await flush();
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '停止')!.click();
    const confirmation = vi.mocked(Modal.confirm).mock.calls[0]![0];
    await vi.advanceTimersByTimeAsync(3000);
    await confirmation.onOk?.();
    expect(devApi.stopDevRecord).toHaveBeenCalledWith(nativeRecord.id);
  });
  it('登录会话变化关闭弹窗并停止活动实例轮询', async () => {
    vi.useFakeTimers();
    await instance.open(nativeRecord.taskId!);
    accessSession.accessToken = 'new-session';
    await flush();
    const calls = vi.mocked(devApi.getDevRecordPage).mock.calls.length;
    await vi.advanceTimersByTimeAsync(9000);
    expect(devApi.getDevRecordPage).toHaveBeenCalledTimes(calls);
    expect(host.textContent).not.toContain('运行实例');
  });
  it('活动实例日志按服务端游标跟随并在终态读尽后停止', async () => {
    vi.useFakeTimers();
    permissions.add('dataforge:dev-task:execute-native');
    vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...nativeRecord, log: '平台脱敏诊断' });
    vi.mocked(devApi.getDevRecordTaskLog).mockResolvedValue({ available: true, message: '第一段日志', readLines: 1, nextSkipLineNum: 7, mayHaveMore: true, truncated: false, source: 'DOLPHINSCHEDULER' });
    await instance.open(nativeRecord.taskId!);
    await flush();
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '查看日志')!.click();
    await flush();
    expect(devApi.getDevRecordTaskLog).toHaveBeenCalledWith({ id: nativeRecord.id, skipLineNum: 0, limit: 300 });
    vi.mocked(devApi.getDevRecord).mockResolvedValue({ ...nativeRecord, status: 'STOPPED' });
    vi.mocked(devApi.getDevRecordTaskLog).mockResolvedValue({ available: true, message: '第二段日志', readLines: 1, nextSkipLineNum: 9, mayHaveMore: false, truncated: false, source: 'DOLPHINSCHEDULER' });
    await vi.advanceTimersByTimeAsync(3000);
    await flush();
    expect(devApi.getDevRecordTaskLog).toHaveBeenLastCalledWith({ id: nativeRecord.id, skipLineNum: 7, limit: 300 });
    expect(host.textContent).toContain('第一段日志');
    expect(host.textContent).toContain('第二段日志');
    const calls = vi.mocked(devApi.getDevRecordTaskLog).mock.calls.length;
    await vi.advanceTimersByTimeAsync(9000);
    expect(devApi.getDevRecordTaskLog).toHaveBeenCalledTimes(calls);
  });
  it('原生停止保留无损ID并等待UNKNOWN核查，不宣称引擎已终止', async () => {
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    await instance.open(nativeRecord.taskId!);
    await flush();
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '停止')!
      .click();
    const confirmation = vi.mocked(Modal.confirm).mock.calls[0]![0];
    vi.mocked(devApi.getDevRecordPage).mockResolvedValue({
      list: [{ ...nativeRecord, status: 'UNKNOWN' }],
      total: 1,
    });
    await confirmation.onOk?.();
    await flush();
    expect(devApi.stopDevRecord).toHaveBeenCalledWith('9007199254740993');
    expect(message.success).toHaveBeenCalledWith('已请求停止，等待核对结果');
    expect(host.textContent).toContain('结果待核查');
    expect(host.textContent).not.toContain('已停止');
  });
});
