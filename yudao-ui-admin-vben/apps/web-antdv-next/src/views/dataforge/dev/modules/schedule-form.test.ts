/* eslint-disable vue/one-component-per-file -- 使用独立组件替身验证接口权限与异步生命周期。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import ScheduleForm from './schedule-form.vue';

const permissions = vi.hoisted(() => new Set<string>());
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => permissions.has(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => accessStore }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevTask: vi.fn(),
  updateDevTask: vi.fn(async () => undefined),
  updateDevTaskSchedule: vi.fn(async () => undefined),
  runDevTask: vi.fn(),
}));
vi.mock('./cron-preview.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('./task-type-icon.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('#/components/cron-tab', () => ({
  CronTab: defineComponent({
    props: {
      modelValue: { type: String, default: '* * * * * ?' },
      weekMode: { type: String, default: undefined },
    },
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          'data-cron': '',
          'data-week-mode': props.weekMode,
          value: props.modelValue,
          onInput: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLInputElement).value),
        }),
  }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', props.message),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      emits: ['click'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h(
            'button',
            {
              disabled: props.disabled || props.loading,
              onClick: () => emit('click'),
            },
            slots.default?.(),
          ),
    }),
    Form: Container,
    FormItem: Container,
    Tag: Container,
    Spin: Container,
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit('change', (event.target as HTMLInputElement).checked),
          }),
    }),
    Modal: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', [slots.default?.(), slots.footer?.()])
            : null,
    }),
    message: { success: vi.fn() },
  };
});

let app: App;
let host: HTMLDivElement;
let accessStore: { accessToken: number; refreshToken: null | number };
let form: { close: () => void; open: (id: string) => Promise<void> };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}
function changeCron() {
  const input = host.querySelector<HTMLInputElement>('[data-cron]')!;
  input.value = '0 0 2 * * ?';
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
async function save() {
  host.querySelector<HTMLButtonElement>('.schedule-save')!.click();
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  permissions.clear();
  accessStore = reactive({ accessToken: 1, refreshToken: null });
  vi.mocked(devApi.getDevTask).mockResolvedValue({
    id: '9007199254740993',
    revision: 7,
    name: 'fixture',
    status: 1,
    currentVersionId: '9007199254740995',
    scheduleStatus: 0,
    cronExpression: '0 * * * * ?',
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ScheduleForm);
  form = app.mount(host) as unknown as typeof form;
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('调度接口权限和当前版本保存', () => {
  it('真实 null 周期不会被误判成修改，仅运行权限可保存关闭而不保存任务', async () => {
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      status: 1,
      currentVersionId: '9007199254740995',
      scheduleStatus: 0,
      cronExpression: null,
      legacyCronExpression: null,
      scheduleSyncStatus: null,
      scheduleSyncReason: null,
    });
    await form.open('9007199254740993');
    await flush();
    expect(host.textContent).not.toContain('null');
    expect(
      host.querySelector<HTMLInputElement>('input[type=checkbox]')?.disabled,
    ).toBe(true);
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 0,
    });
    expect(devApi.runDevTask).not.toHaveBeenCalled();
  });

  it.each([
    ['PENDING', 'DS 同步中'],
    ['ERROR', 'DS 同步失败'],
  ] as const)(
    '%s 单独显示同步状态与原因，不假装 DS 已同步',
    async (scheduleSyncStatus, label) => {
      permissions.add('dataforge:dev-task:save');
      permissions.add('dataforge:dev-task:run');
      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '9007199254740993',
        status: 1,
        currentVersionId: '9007199254740995',
        scheduler: 'DOLPHIN_SCHEDULER',
        scheduleStatus: 1,
        scheduleSyncStatus,
        scheduleSyncReason: '服务端等待确认',
        cronExpression: '0 0 9 ? * 1',
      });
      await form.open('9007199254740993');
      await flush();
      expect(host.textContent).toContain(label);
      expect(host.textContent).toContain('服务端等待确认');
      expect(host.textContent).not.toContain('DS 已同步');
      expect(
        host.querySelector<HTMLInputElement>('[data-cron]')?.dataset.weekMode,
      ).toBe('quartz');
      const toggle = host.querySelector<HTMLInputElement>(
        'input[type=checkbox]',
      )!;
      toggle.checked = false;
      toggle.dispatchEvent(new Event('change', { bubbles: true }));
      await save();
      expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
        id: '9007199254740993',
        scheduleStatus: 0,
      });
      expect(devApi.runDevTask).not.toHaveBeenCalled();
    },
  );

  it('不兼容旧周期只显示保留说明，新的 DS 输入保持空值', async () => {
    permissions.add('dataforge:dev-task:save');
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      status: 1,
      currentVersionId: '9007199254740995',
      scheduler: 'DOLPHIN_SCHEDULER',
      scheduleStatus: 0,
      scheduleSyncStatus: 'NOT_CONFIGURED',
      cronExpression: null,
      legacyCronExpression: '0 0 9 ? * 0',
    });
    await form.open('9007199254740993');
    await flush();
    expect(host.textContent).toContain('原周期 0 0 9 ? * 0 已保留');
    expect(host.textContent).toContain('重新配置 DS 周期');
    expect(host.querySelector<HTMLInputElement>('[data-cron]')?.value).toBe('');
    expect(host.textContent).toContain('未配置');
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(true);
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    changeCron();
    await flush();
    expect(toggle.disabled).toBe(false);
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 1,
    });
  });

  it('停用后的保存版本不能开启调度，并明确显示停用状态', async () => {
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      revision: 7,
      status: 0,
      currentVersionId: '9007199254740995',
      scheduleStatus: 0,
    });
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(true);
    expect(host.textContent).toContain('已停用');
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });
  it('原生调度需要运行与独立原生权限同时成立', async () => {
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      revision: 7,
      taskType: 'FLINK_SQL',
      programConfig: { schemaVersion: 1, workloadMode: 'STREAMING' },
      status: 1,
      currentVersionId: '9007199254740995',
      executionCapability: 'NATIVE',
      scheduleStatus: 0,
    });
    await form.open('9007199254740993');
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
    permissions.add('dataforge:dev-task:execute-native');
    await form.open('9007199254740993');
    await flush();
    await save();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledTimes(1);
  });
  it('运行角色调整开关时不请求保存任务', async () => {
    permissions.add('dataforge:dev-task:run');
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 1,
    });
  });
  it('具备保存和运行权限时先保存当前Cron再切换调度', async () => {
    permissions.add('dataforge:dev-task:save');
    permissions.add('dataforge:dev-task:run');
    await form.open('9007199254740993');
    await flush();
    changeCron();
    await save();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        cronExpression: '0 0 2 * * ?',
        expectedRevision: 7,
        scheduler: 'DOLPHIN_SCHEDULER',
      }),
    );
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledTimes(1);
    expect(
      vi.mocked(devApi.updateDevTask).mock.invocationCallOrder[0],
    ).toBeLessThan(
      vi.mocked(devApi.updateDevTaskSchedule).mock.invocationCallOrder[0]!,
    );
    expect(message.success).toHaveBeenCalledWith(
      '调度设置已保存，正在同步到 DS',
    );
  });
  it('仅运行权限看到只读Cron，确认开关时不调用保存接口', async () => {
    permissions.add('dataforge:dev-task:run');
    await form.open('9007199254740993');
    await flush();
    expect(host.querySelector('[data-cron]')).toBeNull();
    expect(host.textContent).toContain('0 * * * * ?');
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledTimes(1);
  });
  it('保存权限不能替代运行权限', async () => {
    permissions.add('dataforge:dev-task:save');
    await form.open('9007199254740993');
    await flush();
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });
  it('保存时重新读取权限，避免打开弹窗后撤销权限仍发送写请求', async () => {
    permissions.add('dataforge:dev-task:save');
    permissions.add('dataforge:dev-task:run');
    await form.open('9007199254740993');
    await flush();
    permissions.clear();
    changeCron();
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });
});

describe('调度配置加载和关闭边界', () => {
  it('加载完成前显示状态并阻止写请求', async () => {
    permissions.add('dataforge:dev-task:run');
    const request = deferred<devApi.DataforgeDevApi.Task>();
    vi.mocked(devApi.getDevTask).mockReturnValue(request.promise);
    const opening = form.open('9007199254740993');
    await flush();
    expect(host.textContent).toContain('正在加载任务配置');
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
    request.resolve({ id: '9007199254740993', name: '加载完成', status: 1 });
    await opening;
    await flush();
    expect(host.textContent).toContain('加载完成');
  });

  it('打开另一任务后，较早的详情响应不覆盖新任务', async () => {
    permissions.add('dataforge:dev-task:run');
    const first = deferred<devApi.DataforgeDevApi.Task>();
    const second = deferred<devApi.DataforgeDevApi.Task>();
    vi.mocked(devApi.getDevTask)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const firstOpening = form.open('9007199254740993');
    const secondOpening = form.open('9007199254740995');
    second.resolve({ id: '9007199254740995', name: '新任务', status: 1 });
    await secondOpening;
    first.resolve({ id: '9007199254740993', name: '旧任务', status: 1 });
    await firstOpening;
    await flush();
    expect(host.textContent).toContain('新任务');
    expect(host.textContent).not.toContain('旧任务');
    await save();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740995',
      scheduleStatus: 0,
    });
  });

  it.each(['关闭', '切换登录会话'])(
    '%s后旧详情响应不会重新打开弹窗',
    async (action) => {
      const request = deferred<devApi.DataforgeDevApi.Task>();
      vi.mocked(devApi.getDevTask).mockReturnValue(request.promise);
      const opening = form.open('9007199254740993');
      await flush();
      if (action === '关闭') form.close();
      else accessStore.refreshToken = 2;
      await flush();
      request.resolve({ id: '9007199254740993', name: '旧任务', status: 1 });
      await opening;
      await flush();
      expect(host.querySelector('section')).toBeNull();
      expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
    },
  );

  it('加载失败时无横幅且不误判配置状态，重开可读取详情', async () => {
    vi.mocked(devApi.getDevTask).mockRejectedValueOnce(new Error('failed'));
    await form.open('9007199254740993');
    await flush();
    expect(host.textContent).not.toContain('任务配置加载失败');
    expect(host.textContent).not.toContain('配置未完成');
    await form.open('9007199254740993');
    await flush();
    expect(devApi.getDevTask).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('fixture');
    expect(host.textContent).not.toContain('任务配置加载失败');
  });

  it('恢复原配置会同时撤销表达式和开关变更', async () => {
    permissions.add('dataforge:dev-task:save');
    permissions.add('dataforge:dev-task:run');
    await form.open('9007199254740993');
    await flush();
    changeCron();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('恢复原配置'))!
      .click();
    await flush();
    expect(host.querySelector<HTMLInputElement>('[data-cron]')!.value).toBe(
      '0 * * * * ?',
    );
    expect(toggle.checked).toBe(false);
    await save();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 0,
    });
  });

  it('无当前版本的任务不可开启调度，提交端也拒绝伪造开启', async () => {
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      status: 0,
      scheduleStatus: 0,
    });
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(true);
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('启用后的当前版本可单独开启 DS 调度，不产生手动运行', async () => {
    permissions.add('dataforge:dev-task:run');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      currentVersionId: '9007199254740995',
      status: 1,
      scheduleStatus: 0,
      executionCapability: 'STANDARD',
      cronExpression: '0 0 9 ? * 1',
    });
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(false);
    expect(host.textContent).toContain('等待 DS 同步完成');
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 1,
    });
    expect(devApi.runDevTask).not.toHaveBeenCalled();
  });

  it('配置未完成的原生任务即使已有当前版本也不能开启调度', async () => {
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      currentVersionId: '9007199254740995',
      taskType: 'FLINK_SQL',
      programConfig: { schemaVersion: 1, workloadMode: 'STREAMING' },
      executionCapability: 'NOT_READY',
      status: 1,
      scheduleStatus: 0,
    });
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(true);
    expect(host.textContent).toContain('任务配置未完成，完善后保存才可运行');
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('运行环境失效的任务仍可关闭已有定时调度', async () => {
    permissions.add('dataforge:dev-task:run');
    permissions.add('dataforge:dev-task:execute-native');
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      currentVersionId: '9007199254740995',
      taskType: 'FLINK_SQL',
      programConfig: { schemaVersion: 1, workloadMode: 'STREAMING' },
      executionCapability: 'NOT_READY',
      status: 1,
      scheduleStatus: 1,
    });
    await form.open('9007199254740993');
    await flush();
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    expect(toggle.disabled).toBe(false);
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await save();
    expect(devApi.updateDevTaskSchedule).toHaveBeenCalledWith({
      id: '9007199254740993',
      scheduleStatus: 0,
    });
  });
});
