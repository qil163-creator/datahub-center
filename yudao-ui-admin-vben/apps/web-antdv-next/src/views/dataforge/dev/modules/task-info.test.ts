/* eslint-disable vue/one-component-per-file -- 表单回归保留真实树选择器，其余控件使用轻量替身。 */
import type { App, Component } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import TaskForm from './task-form.vue';
import TaskInfo from './task-info.vue';

const validateForm = vi.hoisted(() => vi.fn(async () => undefined));

vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 1 }) }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => []),
}));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  createDevTask: vi.fn(async () => '99'),
  updateDevTask: vi.fn(async () => undefined),
  getDevTask: vi.fn(),
  getDevDirList: vi.fn(async () => []),
  getDevTaskListByDir: vi.fn(async () => []),
  runDevTask: vi.fn(),
  updateDevTaskSchedule: vi.fn(),
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
vi.mock('./cron-preview.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('./program-config-fields.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('./quality-task-field.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('./task-detail-view.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('./task-type-icon.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('antdv-next', async (importActual) => {
  const controls = await importActual<typeof import('antdv-next')>();
  const slot = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    ...controls,
    Form: defineComponent({
      setup: (_props, { expose, slots }) => {
        expose({ validate: validateForm });
        return () => h('div', slots.default?.());
      },
    }),
    FormItem: defineComponent({
      props: {
        label: { type: String, default: '' },
        name: { type: [String, Array], default: undefined },
      },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-field': props.name }, [
            props.label,
            slots.default?.(),
          ]),
    }),
    Modal: defineComponent({
      props: { open: Boolean },
      emits: ['ok'],
      setup:
        (props, { emit, slots }) =>
        () =>
          props.open
            ? h('section', [
                slots.default?.(),
                slots.footer?.() ??
                  h('button', { onClick: () => emit('ok') }, '保存'),
              ])
            : null,
    }),
    Spin: slot,
    message: { success: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
let form: { open: (id?: DataforgeDevApi.Id) => Promise<void> };

async function flush() {
  for (let count = 0; count < 12; count++) await Promise.resolve();
  await nextTick();
}

async function mount(
  component: Component = TaskInfo,
  props: { workloadMode?: DataforgeDevApi.WorkloadMode } = {},
) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(component, props);
  form = app.mount(host) as unknown as typeof form;
  await form.open('99');
  await flush();
}

function directoryLabel() {
  return host.querySelector('[data-field="dirId"] .ant-select-content')
    ?.textContent;
}

async function save() {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => ['仅保存', '保存'].includes(item.textContent?.replaceAll(/\s+/g, '') ?? ''),
  );
  expect(button).toBeDefined();
  button!.click();
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  validateForm.mockResolvedValue(undefined);
  vi.mocked(devApi.getDevDirList).mockResolvedValue([]);
});

afterEach(async () => {
  app?.unmount();
  app = undefined;
  await flush();
  host?.remove();
});

describe('任务类目回显（真实 Antdv Next TreeSelect）', () => {
  it('任务信息把真实 null 周期规范为空编辑值，历史空字段不显示成文本', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      taskType: 'SQL',
      cronExpression: null,
      legacyCronExpression: null,
      scheduleSyncStatus: null,
      scheduleSyncReason: null,
    });
    await mount();
    expect(host.querySelector<HTMLInputElement>('[data-cron]')?.value).toBe('');
    expect(host.textContent).not.toContain('null');
    expect(host.textContent).not.toContain('undefined');
    await save();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        cronExpression: undefined,
        legacyCronExpression: null,
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
  });

  it('本地表单校验失败保留弹窗并展开扩展配置，不发请求或报告保存成功', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({ id: '99', taskType: 'FLINK_SQL', programConfig: { workloadMode: 'BATCH' } });
    await mount();
    validateForm.mockRejectedValueOnce({ errorFields: [{ name: ['programConfig', 'dependencyFileIds'], errors: ['依赖资源无效'] }] });
    await save();
    expect(host.querySelector<HTMLDetailsElement>('.task-advanced')?.open).toBe(true);
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.createDevTask).not.toHaveBeenCalled();
    expect(message.success).not.toHaveBeenCalled();
  });

  it('选择模板只更新编辑内容，保存元数据使用 DS 且不执行或开启调度', async () => {
    await mount();
    await form.open();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('[data-cron]')?.dataset.weekMode,
    ).toBe('quartz');
    expect(host.querySelector('.task-template')).toBeNull();
    host.querySelector<HTMLButtonElement>('.task-template-toggle')!.click();
    await flush();
    const template = host.querySelector<HTMLButtonElement>('.task-template')!;
    expect(template).not.toBeNull();
    template.click();
    await flush();
    expect(devApi.createDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    await save();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        content: 'SELECT 1 AS result;',
        scheduler: 'DOLPHIN_SCHEDULER',
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('实时新增固定为流模式，保存草稿不自动执行或开启调度', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      taskType: 'FLINK_SQL',
      programConfig: { workloadMode: 'STREAMING' },
    });
    await mount(TaskInfo, { workloadMode: 'STREAMING' });
    await form.open();
    await flush();
    expect(host.querySelector('.task-template')).toBeNull();
    await save();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'FLINK_SQL',
        programConfig: { schemaVersion: 1, workloadMode: 'STREAMING' },
        timeoutSeconds: 0,
        status: 0,
        scheduler: 'DOLPHIN_SCHEDULER',
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('迁移前的周期仅作只读回显，不回填新 DS 周期或触发执行', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      taskType: 'SQL',
      scheduler: 'XXL_JOB',
      cronExpression: null,
      legacyCronExpression: '0 0 9 ? * 0',
    });
    await mount();
    expect(host.textContent).toContain('原周期 0 0 9 ? * 0 已保留');
    expect(host.textContent).toContain('请重新配置 DS');
    expect(host.querySelector<HTMLInputElement>('[data-cron]')?.value).toBe('');
    await save();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        scheduler: 'DOLPHIN_SCHEDULER',
        cronExpression: undefined,
        legacyCronExpression: '0 0 9 ? * 0',
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it.each(['FLINK_SQL', 'SPARK_SQL'] as const)(
    '%s 任务信息隐藏数据库选择器并保留历史绑定与执行通道',
    async (taskType) => {
      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '99',
        taskType,
        name: '历史集群任务',
        databaseId: '9007199254740995',
        programConfig: null,
        scheduler: 'XXL_JOB',
        revision: 7,
      });
      await mount();
      expect(host.textContent).not.toContain('数据源连接');
      expect(host.querySelector('[data-field="dbType"]')).toBeNull();
      await save();
      expect(devApi.updateDevTask).toHaveBeenCalledWith(
        expect.objectContaining({
          databaseId: '9007199254740995',
          programConfig: null,
          scheduler: 'DOLPHIN_SCHEDULER',
          expectedRevision: 7,
        }),
      );
    },
  );

  it('数据库SQL任务信息保留数据源选择器', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      taskType: 'SQL',
      databaseId: '9007199254740995',
      dbType: 'MYSQL',
    });
    await mount();
    expect(host.textContent).toContain('数据源连接');
    expect(host.querySelector('[data-field="dbType"]')).not.toBeNull();
  });

  it('工作台任务表单的字符串根编号也回显类目名称', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      name: '顶级任务',
      taskType: 'SQL',
      dirId: '0',
    });
    await mount(TaskForm);
    expect(directoryLabel()).toBe('数据开发类目');
  });

  it.each([0, '0', undefined])(
    '编辑顶级任务 %s 显示根类目名称，保存保留顶级归属',
    async (dirId) => {
      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '99',
        name: '顶级任务',
        taskType: 'SQL',
        dirId,
      });
      await mount();
      expect(directoryLabel()).toBe('数据开发类目');
      await save();
      expect(devApi.updateDevTask).toHaveBeenCalledWith(
        expect.objectContaining({ id: '99', dirId: '0' }),
      );
    },
  );

  it.each([
    ['任务信息', TaskInfo],
    ['工作台任务', TaskForm],
  ] as const)(
    '%s 表单匹配数字/字符串混用的目录编号并保留大编号',
    async (_name, component) => {
      vi.mocked(devApi.getDevDirList).mockResolvedValue([
        { id: 7, parentId: 0, name: '监测' },
        { id: '9007199254740993', parentId: '7', name: '统计' },
      ]);
      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '99',
        name: '统计任务',
        taskType: 'SQL',
        dirId: '9007199254740993',
      });
      await mount(component);
      expect(directoryLabel()).toBe('统计');
      await save();
      expect(devApi.updateDevTask).toHaveBeenCalledWith(
        expect.objectContaining({ dirId: '9007199254740993' }),
      );

      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '99',
        name: '监测任务',
        taskType: 'SQL',
        dirId: '7',
      });
      await form.open('99');
      await flush();
      expect(directoryLabel()).toBe('监测');
    },
  );

  it('已删除的类目显示不可用状态且不自动改变任务归属', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      name: '历史任务',
      taskType: 'SQL',
      dirId: '9007199254740993',
    });
    await mount();
    expect(directoryLabel()).toBe('类目不可用（9007199254740993）');
    await save();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({ dirId: '9007199254740993' }),
    );
  });

  it('已禁用的类目仍回显名称', async () => {
    vi.mocked(devApi.getDevDirList).mockResolvedValue([
      { id: 7, name: '停用类目', status: 1 },
    ]);
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '99',
      name: '历史任务',
      taskType: 'SQL',
      dirId: '7',
    });
    await mount();
    expect(directoryLabel()).toBe('停用类目');
  });
});
