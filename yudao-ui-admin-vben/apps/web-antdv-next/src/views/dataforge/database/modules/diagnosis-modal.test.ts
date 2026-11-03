/* eslint-disable vue/one-component-per-file -- 生命周期回归需要分别挂载普通弹窗与 KeepAlive 宿主。 */
import type { App, SetupContext } from 'vue';

import type { DiagnosisModalData } from '../diagnosis';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { createApp, h, KeepAlive, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DiagnosisModal from './diagnosis-modal.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  data: {} as DiagnosisModalData,
  options: undefined as
    | undefined
    | { onConfirm: () => Promise<void>; onOpenChange: (open: boolean) => void },
  execute: vi.fn(),
  completed: vi.fn(),
  closed: vi.fn(),
  setState: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof harness.options>) => {
    harness.options = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      {
        getData: () => harness.data,
        setData: (data: DiagnosisModalData) => {
          harness.data = data;
        },
        setState: harness.setState,
        close: () => options.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: (props: { description?: string; message?: string }) =>
    h('aside', [props.message, props.description]),
  Spin: () => h('span', { class: 'test-spinner' }, '等待'),
}));
vi.mock('./type-icon-cell.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
function response(count = 1): FoundationDatabaseApi.Diagnosis {
  return {
    diagnosisId: `diagnosis-${count}`,
    overallStatus: 'PASS',
    connectionSuccessful: true,
    checkedAt: 1_792_000_000_000,
    durationMs: 22,
    normalCount: 2,
    warningCount: 0,
    errorCount: 0,
    skippedCount: 1,
    totalCount: 3,
    groups: [
      {
        groupCode: 'BASIC',
        groupName: '基础连接',
        description: '验证连接配置和认证',
        status: 'PASS',
        normalCount: 2,
        warningCount: 0,
        errorCount: 0,
        skippedCount: 0,
        totalCount: 2,
        items: [
          {
            checkCode: 'CONFIG',
            checkName: '连接配置',
            checkContent: '检查连接配置',
            status: 'PASS',
            message: '',
            impact: '',
            durationMs: 10,
          },
          {
            checkCode: 'AUTH',
            checkName: '账号认证',
            checkContent: '检查账号认证',
            status: 'PASS',
            message: '',
            impact: '',
            durationMs: 12,
          },
        ],
      },
      {
        groupCode: 'WRITE',
        groupName: '写入能力',
        description: '验证写入',
        status: 'SKIPPED',
        normalCount: 0,
        warningCount: 0,
        errorCount: 0,
        skippedCount: 1,
        totalCount: 1,
        items: [
          {
            checkCode: 'INSERT',
            checkName: '临时表写入',
            checkContent: '验证临时表写入',
            status: 'SKIPPED',
            message: '当前类型不支持临时表写入',
            impact: '写入能力未验证',
            durationMs: 0,
          },
        ],
      },
    ],
  };
}
function deferred() {
  let resolve!: (value: unknown) => void;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await nextTick();
  await nextTick();
}
async function open() {
  harness.data = {
    summary: {
      name: '业务数据库',
      typeName: 'PostgreSQL',
      host: 'db.test',
      port: '5432',
      userName: 'reader',
    },
    execute: harness.execute,
  };
  harness.options!.onOpenChange(true);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(window, 'matchMedia').mockReturnValue({
    matches: true,
  } as MediaQueryList);
  harness.access = reactive({
    accessCodes: ['dataforge:database:query', 'dataforge:database:update'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  host = document.createElement('div');
  app = createApp(DiagnosisModal, {
    onCompleted: harness.completed,
    onClosed: harness.closed,
  });
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('分组连接诊断弹窗', () => {
  it('减少动态效果时首测直接请求写检查，完整返回后展示真实结果', async () => {
    const pending = deferred();
    harness.execute.mockReturnValueOnce(pending.promise);
    await open();
    expect(harness.execute).toHaveBeenCalledWith(
      true,
      expect.any(AbortSignal),
    );
    expect(harness.data).toEqual({});
    expect(host.textContent).toContain('正在诊断');
    expect(host.querySelector('tbody')).toBeNull();
    pending.resolve(response());
    await flush();
    expect(host.textContent).toContain('诊断通过');
    expect(host.textContent).not.toContain('全部通过');
    expect(host.textContent).toContain('连接正常');
    expect(host.querySelector('th[rowspan="2"]')?.textContent).toContain(
      '基础连接',
    );
    expect(host.textContent).toContain('当前类型不支持临时表写入');
    expect(harness.completed).toHaveBeenCalledExactlyOnceWith(response());
    expect(
      host.querySelector<HTMLElement>('.diagnosis-panel')?.dataset.phase,
    ).toBe('complete');
  });

  it('真实结果校验后逐项揭示，不提前显示结论，计数只累加已展示结果', async () => {
    vi.useFakeTimers();
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: false,
    } as MediaQueryList);
    const actual = response();
    actual.overallStatus = 'FAIL';
    actual.normalCount = 1;
    actual.errorCount = 1;
    actual.groups[0]!.status = 'FAIL';
    actual.groups[0]!.normalCount = 1;
    actual.groups[0]!.errorCount = 1;
    actual.groups[0]!.items[1]!.status = 'FAIL';
    actual.groups[0]!.items[1]!.message = '认证检查失败';
    harness.execute.mockResolvedValue(actual);
    await open();
    const phase = () =>
      host.querySelector<HTMLElement>('.diagnosis-panel')?.dataset.phase;
    const running = () =>
      host.querySelectorAll('td .diagnosis-status--running').length;
    expect(phase()).toBe('revealing');
    expect(running()).toBe(3);
    expect(host.querySelectorAll('td .diagnosis-spinner')).toHaveLength(3);
    expect(host.querySelector('.count-pass')?.textContent).toBe('0');
    expect(host.textContent).not.toContain('诊断发现错误');
    expect(harness.completed).not.toHaveBeenCalled();
    await harness.options!.onConfirm();
    expect(harness.execute).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(299);
    expect(running()).toBe(3);
    await vi.advanceTimersByTimeAsync(1);
    expect(running()).toBe(2);
    expect(host.querySelector('.count-pass')?.textContent).toBe('1');
    expect(host.querySelector('.count-fail')?.textContent).toBe('0');
    await vi.advanceTimersByTimeAsync(300);
    expect(running()).toBe(1);
    expect(host.querySelector('.count-fail')?.textContent).toBe('1');
    expect(host.querySelector('.diagnosis-group__top')?.textContent).toContain(
      '1/2 错误',
    );
    expect(host.textContent).toContain('认证检查失败');
    expect(harness.completed).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    expect(phase()).toBe('complete');
    expect(running()).toBe(0);
    expect(host.querySelector('.diagnosis-spinner')).toBeNull();
    expect(host.textContent).toContain('诊断发现错误');
    expect(host.textContent).toContain('当前类型不支持临时表写入');
    expect(harness.completed).toHaveBeenCalledExactlyOnceWith(actual);
    expect(harness.setState).toHaveBeenLastCalledWith({
      confirmLoading: false,
      confirmDisabled: false,
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['close', 'session', 'unmount'] as const)(
    '播放途中 %s 立即清除计时器，旧播放不能完成或影响重开的请求',
    async (action) => {
      vi.useFakeTimers();
      vi.mocked(window.matchMedia).mockReturnValue({
        matches: false,
      } as MediaQueryList);
      harness.execute.mockResolvedValueOnce(response());
      await open();
      await vi.advanceTimersByTimeAsync(300);
      expect(vi.getTimerCount()).toBe(1);
      if (action === 'close') harness.options!.onOpenChange(false);
      if (action === 'session') harness.access.accessToken = 'session-b';
      if (action === 'unmount') {
        app?.unmount();
        app = undefined;
      }
      expect(vi.getTimerCount()).toBe(0);
      if (action !== 'unmount') {
        harness.execute.mockRejectedValueOnce(new Error('network-failure'));
        await open();
      }
      expect(
        host.querySelector<HTMLElement>('.diagnosis-panel')?.dataset.phase,
      ).toBe(action === 'unmount' ? undefined : 'error');
      await vi.advanceTimersByTimeAsync(3000);
      expect(host.querySelector('table')).toBeNull();
      expect(harness.completed).not.toHaveBeenCalled();
      expect(vi.getTimerCount()).toBe(0);
    },
  );

  it('有写组时首次、重新诊断和重开均自动请求写检查，不显示勾选项', async () => {
    harness.execute.mockResolvedValue(response());
    await open();
    expect(host.querySelector('input[type="checkbox"]')).toBeNull();
    expect(host.querySelector('.diagnosis-options')).toBeNull();
    expect(host.textContent).not.toContain('勾选后');
    expect(harness.execute).toHaveBeenLastCalledWith(
      true,
      expect.any(AbortSignal),
    );
    await harness.options!.onConfirm();
    expect(harness.execute).toHaveBeenLastCalledWith(
      true,
      expect.any(AbortSignal),
    );
    harness.options!.onOpenChange(false);
    await open();
    expect(harness.execute).toHaveBeenLastCalledWith(
      true,
      expect.any(AbortSignal),
    );
    expect(harness.execute).toHaveBeenCalledTimes(3);
  });

  it('缺少修改权限时首次和重新诊断均保持只读', async () => {
    harness.execute.mockResolvedValue(response());
    harness.access.accessCodes = ['dataforge:database:query'];
    await open();
    expect(host.querySelector('input[type="checkbox"]')).toBeNull();
    expect(harness.execute).toHaveBeenLastCalledWith(
      false,
      expect.any(AbortSignal),
    );
    await harness.options!.onConfirm();
    expect(harness.execute).toHaveBeenLastCalledWith(
      false,
      expect.any(AbortSignal),
    );
  });

  it.each([true, false])(
    '文件诊断结果重试按修改权限 %s 决定写检查',
    async (canUpdate) => {
      const fileResult = response();
      fileResult.groups[1]!.groupCode = 'FILE_CONNECTION';
      fileResult.groups[1]!.items[0]!.checkCode = 'FILE_WRITE';
      harness.access.accessCodes = canUpdate
        ? ['dataforge:database:query', 'dataforge:database:update']
        : ['dataforge:database:query'];
      harness.execute.mockResolvedValue(fileResult);
      await open();
      await harness.options!.onConfirm();
      expect(harness.execute).toHaveBeenCalledTimes(2);
      expect(harness.execute).toHaveBeenLastCalledWith(
        canUpdate,
        expect.any(AbortSignal),
      );
    },
  );

  it('首次返回无写组后，重新诊断不再请求不支持的写检查', async () => {
    // 仅用于验证无 WRITE 组的界面边界，不代表 MongoDB 实际检查目录。
    const mongodb = response();
    const basic = mongodb.groups[0]!;
    mongodb.diagnosisId = 'fixture-mongodb';
    mongodb.normalCount = 7;
    mongodb.skippedCount = 0;
    mongodb.totalCount = 7;
    mongodb.groups = [
      {
        ...basic,
        normalCount: 7,
        totalCount: 7,
        items: Array.from({ length: 7 }, (_, index) => ({
          ...basic.items[0]!,
          checkCode: `MONGO_CHECK_${index}`,
          checkName: `MongoDB 检查 ${index + 1}`,
        })),
      },
    ];
    harness.execute.mockResolvedValue(mongodb);
    await open();
    expect(harness.execute).toHaveBeenLastCalledWith(
      true,
      expect.any(AbortSignal),
    );
    await harness.options!.onConfirm();
    await flush();
    expect(harness.execute).toHaveBeenLastCalledWith(
      false,
      expect.any(AbortSignal),
    );
  });

  it('同一弹窗重开另一产品后按新响应展示三组八项，不残留六组十七项', async () => {
    vi.useFakeTimers();
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: false,
    } as MediaQueryList);
    // 明确的多产品协议 fixture，不代表真实产品能力或诊断结果。
    const productFixture = (
      product: string,
      groupSizes: number[],
    ): FoundationDatabaseApi.Diagnosis => {
      const base = response();
      const totalCount = groupSizes.reduce((total, count) => total + count, 0);
      return {
        ...base,
        diagnosisId: `fixture-${product}`,
        normalCount: totalCount,
        skippedCount: 0,
        totalCount,
        groups: groupSizes.map((size, groupIndex) => ({
          ...base.groups[0]!,
          groupCode: `${product}_GROUP_${groupIndex}`,
          groupName: `${product} 分组 ${groupIndex + 1}`,
          normalCount: size,
          totalCount: size,
          items: Array.from({ length: size }, (_, itemIndex) => ({
            ...base.groups[0]!.items[0]!,
            checkCode: `${product}_CHECK_${groupIndex}_${itemIndex}`,
            checkName: `${product} 检查 ${groupIndex + 1}-${itemIndex + 1}`,
          })),
        })),
      };
    };
    const firstProduct = productFixture('产品A', [3, 2, 3, 2, 4, 3]);
    const nextProduct = productFixture('Hive', [3, 2, 3]);
    harness.execute
      .mockResolvedValueOnce(firstProduct)
      .mockResolvedValueOnce(nextProduct);
    await open();
    expect(host.querySelectorAll('tbody')).toHaveLength(6);
    expect(host.textContent).toContain('正在展示诊断结果 0/17');
    await vi.advanceTimersByTimeAsync(5100);
    expect(harness.completed).toHaveBeenLastCalledWith(firstProduct);
    harness.options!.onOpenChange(false);
    await open();
    expect(host.querySelectorAll('tbody')).toHaveLength(3);
    expect(host.querySelectorAll('td .diagnosis-status')).toHaveLength(8);
    expect(host.querySelectorAll('td .diagnosis-spinner')).toHaveLength(8);
    expect(host.textContent).toContain('Hive 检查 3-3');
    expect(host.textContent).not.toContain('产品A');
    expect(host.textContent).toContain('正在展示诊断结果 0/8');
    expect(host.textContent).not.toContain('/17');
    await vi.advanceTimersByTimeAsync(300);
    expect(host.textContent).toContain('正在展示诊断结果 1/8');
    expect(host.querySelector('.count-pass')?.textContent).toBe('1');
    await vi.advanceTimersByTimeAsync(2100);
    expect(
      host.querySelector<HTMLElement>('.diagnosis-panel')?.dataset.phase,
    ).toBe('complete');
    expect(host.querySelector('.count-pass')?.textContent).toBe('8');
    expect(host.querySelector('.diagnosis-spinner')).toBeNull();
    expect(harness.completed).toHaveBeenLastCalledWith(nextProduct);
  });

  it('关闭与重新打开阻止旧结果覆盖，旧请求不能结束新等待状态', async () => {
    const old = deferred();
    const current = deferred();
    harness.execute
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    await open();
    const firstSignal = harness.execute.mock.calls[0]![1] as AbortSignal;
    harness.options!.onOpenChange(false);
    await open();
    expect(firstSignal.aborted).toBe(true);
    old.resolve(response(77));
    await flush();
    expect(host.textContent).toContain('正在诊断');
    expect(harness.completed).not.toHaveBeenCalled();
    current.resolve(response(88));
    await flush();
    expect(harness.completed).toHaveBeenCalledExactlyOnceWith(response(88));
  });

  it('会话或权限切换立即清除摘要与结果，回到原会话也不接收旧响应', async () => {
    const pending = deferred();
    harness.execute.mockReturnValueOnce(pending.promise);
    await open();
    harness.access.accessToken = 'session-b';
    harness.access.accessToken = 'session-a';
    pending.resolve(response());
    await flush();
    expect(host.textContent).not.toContain('业务数据库');
    expect(host.querySelector('table')).toBeNull();
    expect(harness.completed).not.toHaveBeenCalled();
    expect(harness.data).toEqual({});
  });

  it('空结果和网络失败显示失败，错误对象原文不会泄露到弹窗', async () => {
    harness.execute
      .mockResolvedValueOnce(null)
      .mockRejectedValueOnce(new Error('private-sql-and-password'));
    await open();
    expect(host.textContent).toContain('诊断返回数据不完整或不一致');
    expect(host.querySelector('table')).toBeNull();
    await harness.options!.onConfirm();
    await flush();
    expect(host.textContent).toContain('诊断未完成');
    expect(host.textContent).not.toContain('private-sql-and-password');
    expect(harness.completed).not.toHaveBeenCalled();
  });

  it('缓存页面停用时取消前端等待并清空上下文，恢复后不接收旧诊断', async () => {
    vi.useFakeTimers();
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: false,
    } as MediaQueryList);
    app?.unmount();
    const visible = ref(true);
    app = createApp({
      render: () =>
        h(KeepAlive, null, {
          default: () =>
            visible.value
              ? h(DiagnosisModal, { onCompleted: harness.completed })
              : null,
        }),
    });
    app.mount(host);
    const pending = deferred();
    harness.execute.mockReturnValueOnce(pending.promise);
    await open();
    const signal = harness.execute.mock.calls[0]![1] as AbortSignal;
    pending.resolve(response());
    await flush();
    expect(vi.getTimerCount()).toBe(1);
    visible.value = false;
    await flush();
    expect(signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(3000);
    visible.value = true;
    await flush();
    expect(host.querySelector('table')).toBeNull();
    expect(host.textContent).not.toContain('业务数据库');
    expect(harness.completed).not.toHaveBeenCalled();
  });
});
