/* eslint-disable vue/one-component-per-file -- 生命周期回归同时覆盖普通挂载与 KeepAlive 宿主。 */
import type { App, SetupContext } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { createApp, h, KeepAlive, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CreateConnectionReview from './create-connection-review.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  diagnose: vi.fn(),
  testing: vi.fn(),
  result: vi.fn(),
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
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-10-15 00:00:00' }));
vi.mock('#/api/dataforge/database', () => ({
  diagnoseDatabase: harness.diagnose,
}));
vi.mock('./type-icon-cell.vue', () => ({ default: () => null }));
vi.mock('antdv-next', () => ({
  Alert: (props: { description?: string; message?: string }) =>
    h('aside', [props.message, props.description]),
  Spin: () => h('span', '等待'),
  Button: (
    props: { disabled?: boolean; loading?: boolean },
    { attrs, slots }: SetupContext,
  ) =>
    h(
      'button',
      {
        ...attrs,
        disabled: props.disabled || props.loading,
        type: 'button',
      },
      slots.default?.(),
    ),
}));

let app: App | undefined;
let host: HTMLDivElement;
let connection: FoundationDatabaseApi.Database & { secretKey?: string };

function response(
  status: FoundationDatabaseApi.DiagnosisStatus = 'PASS',
  connectionSuccessful = true,
): FoundationDatabaseApi.Diagnosis {
  const counts = {
    normalCount: status === 'PASS' ? 1 : 0,
    warningCount: status === 'WARN' ? 1 : 0,
    errorCount: status === 'FAIL' ? 1 : 0,
    skippedCount: status === 'SKIPPED' ? 1 : 0,
    totalCount: 1,
  };
  return {
    diagnosisId: `result-${status}`,
    overallStatus: status,
    connectionSuccessful,
    checkedAt: 1_792_000_000_000,
    durationMs: 130,
    ...counts,
    groups: [
      {
        groupCode: 'BASIC',
        groupName: '基础连接',
        description: '验证认证',
        status,
        ...counts,
        items: [
          {
            checkCode: 'AUTH',
            checkName: '认证检查',
            checkContent: '实际执行认证检查',
            status,
            message: '检查返回的说明',
            impact: '仅代表本项验证范围',
            durationMs: 130,
          },
        ],
      },
    ],
  };
}
function deferred() {
  let resolve!: (value: unknown) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(CreateConnectionReview, {
    connection,
    onTesting: harness.testing,
    onResult: harness.result,
  });
  app.mount(host);
}
async function testConnection() {
  host.querySelector<HTMLButtonElement>('button')!.click();
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['dataforge:database:query'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['reader'] });
  connection = reactive({
    id: '9007199254740993',
    name: '业务数据库',
    databaseType: 2,
    databaseTypeName: 'PostgreSQL',
    databaseIp: 'db.example',
    databasePort: '5432',
    userName: 'reader',
    password: 'synthetic-private-password',
    secretKey: 'synthetic-private-key',
    jdbcUrl: 'jdbc:postgresql://private-target/review',
    extendConfig: 'private-connection-option',
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('新增连接第三步', () => {
  it('编辑配置改变且空密码时不借用旧凭据发起诊断，仍说明可以保存未检测记录', async () => {
    app = createApp(CreateConnectionReview, {
      connection,
      testBlockedReason:
        '连接配置已改变，请返回重新输入密码；也可直接保存未检测记录。',
      onTesting: harness.testing,
    });
    app.mount(host);
    await flush();
    expect(harness.diagnose).not.toHaveBeenCalled();
    expect(host.querySelector<HTMLButtonElement>('button')!.disabled).toBe(
      true,
    );
    expect(host.textContent).toContain('可直接保存未检测记录');
    expect(harness.testing).toHaveBeenLastCalledWith(false);
  });
  it('进入第三步只读诊断一次并支持手动重试，概要不显示密码、完整连接串、扩展属性或密钥', async () => {
    const pending = deferred();
    harness.diagnose
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(response());
    mount();
    expect(harness.diagnose).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('业务数据库');
    for (const sensitive of [
      connection.password,
      connection.jdbcUrl,
      connection.extendConfig,
      connection.secretKey,
    ]) {
      expect(host.innerHTML).not.toContain(sensitive);
    }
    await testConnection();
    const [payload, signal] = harness.diagnose.mock.calls[0]!;
    expect(payload.includeWriteChecks).toBe(false);
    expect(payload).not.toHaveProperty('databaseId');
    expect(payload.connection.id).toBe('9007199254740993');
    expect(payload.connection).not.toHaveProperty('secretKey');
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(host.textContent).toContain('正在测试连接');
    expect(harness.testing).toHaveBeenLastCalledWith(true);
    pending.resolve(response());
    await flush();
    expect(harness.result).toHaveBeenLastCalledWith(response());
    expect(harness.testing).toHaveBeenLastCalledWith(false);
    expect(host.textContent).toContain('连接验证通过');
    expect(host.textContent).toContain('130 ms');
    expect(host.querySelector('th[scope="rowgroup"]')?.textContent).toContain(
      '基础连接',
    );
    expect(
      [...host.querySelectorAll('thead th')].map((cell) => cell.textContent),
    ).toEqual(['诊断详情', '诊断项', '校验内容', '结果状态', '功能影响']);
    expect(host.textContent).toContain('检查返回的说明');
    await testConnection();
    expect(harness.diagnose).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['FAIL', true, '连接验证通过'],
    ['PASS', false, '连接状态未确认'],
  ] as const)(
    '汇总状态%s不替代实际连接成功标识%s',
    async (status, successful, label) => {
      harness.diagnose.mockResolvedValueOnce(response(status, successful));
      mount();
      await testConnection();
      expect(host.textContent).toContain(label);
      expect(harness.result).toHaveBeenLastCalledWith(
        expect.objectContaining({ connectionSuccessful: successful }),
      );
    },
  );

  it('只有新增权限时不发诊断请求，明确可以保存未检测记录', async () => {
    harness.access.accessCodes = ['dataforge:database:create'];
    mount();
    await testConnection();
    expect(harness.diagnose).not.toHaveBeenCalled();
    expect(host.querySelector('button')?.disabled).toBe(true);
    expect(host.textContent).toContain('当前账号没有连接查询权限');
    expect(host.textContent).toContain('可继续保存为未检测记录');
  });

  it.each(['FTP', 'HDFS'])(
    '%s 自动只读，有修改权限时手动重试包含写检查',
    async (code) => {
      connection.typeCode = code;
      harness.access.accessCodes.push('dataforge:database:update');
      harness.diagnose.mockResolvedValue(response());
      mount();
      await flush();
      expect(harness.diagnose.mock.calls[0]![0].includeWriteChecks).toBe(false);
      expect(host.textContent).toContain('创建临时文件并尝试清理');
      await testConnection();
      expect(harness.diagnose.mock.calls[1]![0].includeWriteChecks).toBe(true);
    },
  );

  it('文件连接缺少修改权限时重试保持只读并说明跳过', async () => {
    connection.typeCode = 'FTP';
    harness.diagnose.mockResolvedValue(response());
    mount();
    await flush();
    await testConnection();
    expect(harness.diagnose.mock.calls[1]![0].includeWriteChecks).toBe(false);
    expect(host.textContent).toContain('写入检查将跳过');
  });

  it('文件连接缺少查询权限时不误报缺少修改权限', async () => {
    connection.typeCode = 'FTP';
    harness.access.accessCodes = ['dataforge:database:update'];
    mount();
    await flush();
    expect(harness.diagnose).not.toHaveBeenCalled();
    expect(host.textContent).toContain('没有连接查询权限');
    expect(host.textContent).not.toContain('没有连接修改权限');
  });

  it('变更连接类型后以当前选中的类型决定写检查', async () => {
    connection.typeCode = 'POSTGRESQL';
    harness.access.accessCodes.push('dataforge:database:update');
    harness.diagnose.mockResolvedValue(response());
    app = createApp(CreateConnectionReview, {
      connection,
      selectedType: { typeCode: 'FTP' } as any,
    });
    app.mount(host);
    await flush();
    await testConnection();
    expect(harness.diagnose.mock.calls[1]![0].includeWriteChecks).toBe(true);
  });

  it.each(['POSTGRESQL', 'SFTP', 'OSS'])(
    '%s 向导重试保持原有只读行为',
    async (code) => {
      connection.typeCode = code;
      harness.access.accessCodes.push('dataforge:database:update');
      harness.diagnose.mockResolvedValue(response());
      mount();
      await flush();
      await testConnection();
      expect(harness.diagnose.mock.calls[1]![0].includeWriteChecks).toBe(false);
    },
  );

  it('失败与无效返回不会冒充成功，均允许重新测试', async () => {
    harness.diagnose
      .mockRejectedValueOnce(new Error('synthetic-private-error'))
      .mockResolvedValueOnce(true)
      .mockResolvedValueOnce(response());
    mount();
    await testConnection();
    expect(host.textContent).toContain('测试未完成');
    expect(host.textContent).not.toContain('synthetic-private-error');
    expect(host.querySelector('button')?.disabled).toBe(false);
    await testConnection();
    expect(host.textContent).toContain('诊断返回数据不完整或不一致');
    await testConnection();
    expect(harness.diagnose).toHaveBeenCalledTimes(3);
    expect(
      harness.result.mock.calls.filter(([value]) => value !== undefined),
    ).toEqual([[response()]]);
  });

  it('连接变化后即使恢复原值，旧响应也不能覆盖新测试或解除新loading', async () => {
    const previous = deferred();
    const current = deferred();
    harness.diagnose
      .mockReturnValueOnce(previous.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    await testConnection();
    const signal = harness.diagnose.mock.calls[0]![1] as AbortSignal;
    connection.databaseIp = 'other.example';
    connection.databaseIp = 'db.example';
    await flush();
    expect(signal.aborted).toBe(true);
    await testConnection();
    previous.resolve(response('FAIL', false));
    await flush();
    expect(host.textContent).toContain('正在测试连接');
    expect(harness.testing).toHaveBeenLastCalledWith(true);
    current.resolve(response());
    await flush();
    expect(
      harness.result.mock.calls.filter(([value]) => value !== undefined),
    ).toEqual([[response()]]);
  });

  it.each(['token', 'refresh', 'identity', 'roles', 'permission'] as const)(
    '%s上下文改变会取消请求并丢弃旧结果',
    async (change) => {
      const pending = deferred();
      harness.diagnose.mockReturnValueOnce(pending.promise);
      mount();
      await testConnection();
      const signal = harness.diagnose.mock.calls[0]![1] as AbortSignal;
      if (change === 'token') {
        harness.access.accessToken = 'session-b';
        harness.access.accessToken = 'session-a';
      } else if (change === 'refresh') harness.access.refreshToken = 'renewed';
      else if (change === 'identity') harness.user.userInfo.userId = '2';
      else if (change === 'roles') harness.user.userRoles.push('changed');
      else harness.access.accessCodes = [];
      await flush();
      expect(signal.aborted).toBe(true);
      pending.resolve(response());
      await flush();
      expect(
        harness.result.mock.calls.filter(([value]) => value !== undefined),
      ).toEqual([]);
      expect(harness.testing).toHaveBeenLastCalledWith(false);
    },
  );

  it('卸载会取消请求，完成后不再发出结果', async () => {
    const pending = deferred();
    harness.diagnose.mockReturnValueOnce(pending.promise);
    mount();
    await testConnection();
    const signal = harness.diagnose.mock.calls[0]![1] as AbortSignal;
    app!.unmount();
    app = undefined;
    expect(signal.aborted).toBe(true);
    pending.resolve(response());
    await flush();
    expect(
      harness.result.mock.calls.filter(([value]) => value !== undefined),
    ).toEqual([]);
  });

  it('keepAlive 停用会取消请求，重新激活后等待用户重新测试', async () => {
    const pending = deferred();
    const visible = ref(true);
    harness.diagnose.mockReturnValueOnce(pending.promise);
    app = createApp({
      render: () =>
        h(KeepAlive, () =>
          visible.value
            ? h(CreateConnectionReview, {
                connection,
                onResult: harness.result,
                onTesting: harness.testing,
              })
            : null,
        ),
    });
    app.mount(host);
    await testConnection();
    const signal = harness.diagnose.mock.calls[0]![1] as AbortSignal;
    visible.value = false;
    await flush();
    expect(signal.aborted).toBe(true);
    pending.resolve(response());
    visible.value = true;
    await flush();
    expect(
      harness.result.mock.calls.filter(([value]) => value !== undefined),
    ).toEqual([]);
    expect(harness.diagnose).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('尚未测试连接');
  });
});
