import type { App, PropType } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import TaskForm from './task-form.vue';

vi.mock('@vben/utils', () => ({ handleTree: (items: unknown) => items }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 1 }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => [
    { id: '9007199254740999', nickname: '配置责任人', mobile: '13800000000' },
  ]),
}));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  createDevTask: vi.fn(async () => '9007199254740993'),
  updateDevTask: vi.fn(async () => undefined),
  getDevTask: vi.fn(),
  getDevDirList: vi.fn(async () => []),
  getDevTaskListByDir: vi.fn(async () => []),
  runDevTask: vi.fn(),
  updateDevTaskSchedule: vi.fn(),
  getDevRuntimeProfiles: vi.fn(async () =>
    ['FLINK', 'SPARK'].map((engine) => ({
      code: `${engine.toLowerCase()}-managed`,
      name: `${engine} 受管部署`,
      engine,
      engineVersion: 'test-version',
      deployMode: 'YARN_ATTACHED',
      queue: 'managed',
      allowedTaskTypes:
        engine === 'FLINK'
          ? ['FLINK_SQL', 'FLINK_JAR', 'PYFLINK']
          : ['SPARK_SQL', 'SPARK_JAR', 'PYSPARK'],
      artifacts: [
        {
          fileId: '9007199254740993',
          name: 'example.py',
          sha256: 'a'.repeat(64),
          programTypes: ['PYTHON', 'JAR'],
        },
      ],
      dependencyResources: ['9007199254740995', '9007199254740997'].map(
        (fileId) => ({ fileId, name: `依赖${fileId}`, sha256: 'b'.repeat(64) }),
      ),
    })),
  ),
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
vi.mock('./quality-task-field.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('antdv-next', () => {
  const Input = defineComponent({
    props: { value: [String, Number], placeholder: String },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          placeholder: props.placeholder,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const Select = defineComponent({
    props: {
      value: [String, Number, Array],
      disabled: Boolean,
      mode: String,
      options: Array as PropType<
        { disabled?: boolean; label: string; value: string }[]
      >,
    },
    emits: ['update:value', 'change'],
    setup:
      (props, { emit }) =>
      () =>
        props.mode === 'tags'
          ? h('input', {
              onInput: (event: Event) =>
                emit(
                  'update:value',
                  (event.target as HTMLInputElement).value.split(','),
                ),
            })
          : h(
              'select',
              {
                value: props.value,
                disabled: props.disabled,
                multiple: props.mode === 'multiple',
                onChange: (event: Event) => {
                  const select = event.target as HTMLSelectElement;
                  const value =
                    props.mode === 'multiple'
                      ? Array.from(
                          select.selectedOptions,
                          (option) => option.value,
                        )
                      : select.value;
                  emit('update:value', value);
                  emit('change', value);
                },
              },
              [
                h('option', { value: '' }, '请选择'),
                ...(props.options?.map((option) =>
                  h(
                    'option',
                    { value: option.value, disabled: option.disabled },
                    option.label,
                  ),
                ) ?? []),
              ],
            ),
  });
  return {
    Input,
    InputNumber: Input,
    TextArea: Input,
    Select,
    TreeSelect: Select,
    Spin: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('div', slots.default?.()),
    }),
    Switch: defineComponent({
      props: { checked: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            onChange: (event: Event) =>
              emit(
                'update:checked',
                (event.target as HTMLInputElement).checked,
              ),
          }),
    }),
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', props.message),
    }),
    Form: defineComponent({
      setup: (_props, { expose, slots }) => {
        expose({ validate: async () => undefined });
        return () => h('div', slots.default?.());
      },
    }),
    FormItem: defineComponent({
      props: { label: String, name: [String, Array] },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'label',
            {
              'data-field': Array.isArray(props.name)
                ? props.name.join('.')
                : props.name,
            },
            [props.label, slots.default?.()],
          ),
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
                h(
                  'button',
                  { 'data-save': '', onClick: () => emit('ok') },
                  '保存',
                ),
              ])
            : null,
    }),
    message: { success: vi.fn() },
  };
});

let app: App;
let host: HTMLDivElement;
let form: {
  open: (
    id?: DataforgeDevApi.Id,
    dirId?: DataforgeDevApi.Id,
    preset?: Partial<DataforgeDevApi.Task>,
  ) => Promise<void>;
};
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}
async function selectType(type: DataforgeDevApi.TaskType) {
  const select = host.querySelector<HTMLSelectElement>(
    '[data-field="taskType"] select',
  )!;
  select.value = type;
  select.dispatchEvent(new Event('change'));
  await flush();
}
async function selectField(field: string, values: string[]) {
  const select = host.querySelector<HTMLSelectElement>(
    `[data-field="${field}"] select`,
  )!;
  for (const option of select.options)
    option.selected = values.includes(option.value);
  select.dispatchEvent(new Event('change'));
  await flush();
}
function input(field: string, value: string) {
  const control = host.querySelector<HTMLInputElement>(
    `[data-field="${field}"] input`,
  )!;
  control.value = value;
  control.dispatchEvent(new Event('input'));
}

beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(TaskForm);
  form = app.mount(host) as unknown as typeof form;
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('双引擎程序配置表单', () => {
  it('实时SparkSQL保存页面归属STREAMING但程序固定BATCH并恢复正超时', async () => {
    app.unmount();
    app = createApp(TaskForm, { workloadMode: 'STREAMING' });
    form = app.mount(host) as unknown as typeof form;
    await form.open();
    await selectType('SPARK_SQL');
    expect(
      host.querySelector<HTMLSelectElement>(
        '[data-field="programConfig.workloadMode"] select',
      )?.disabled,
    ).toBe(true);
    expect(
      Number(
        host.querySelector<HTMLInputElement>(
          '[data-field="timeoutSeconds"] input',
        )?.value,
      ),
    ).toBeGreaterThan(0);
    input('name', '实时分类SparkSQL批任务');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'SPARK_SQL',
        timeoutSeconds: 300,
        executionConfig: expect.objectContaining({ workloadMode: 'STREAMING' }),
        programConfig: expect.objectContaining({ workloadMode: 'BATCH' }),
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
  });
  it('实时页面的普通SQL保存列表分类与正超时，不保留原生流配置', async () => {
    app.unmount();
    app = createApp(TaskForm, { workloadMode: 'STREAMING' });
    form = app.mount(host) as unknown as typeof form;
    await form.open(undefined, undefined, {
      taskType: 'SQL',
      name: '实时页面单次SQL',
      databaseId: '9007199254740995',
      timeoutSeconds: 0,
      executionConfig: {
        localParams: [{ prop: 'station', type: 'VARCHAR', value: 'A01' }],
      },
    });
    expect(
      host.querySelector('[data-field="programConfig.workloadMode"]'),
    ).toBeNull();
    expect(host.querySelector('[data-field="databaseId"]')).not.toBeNull();
    expect(host.textContent).toContain('每次运行执行一次');
    expect(
      host.querySelector<HTMLInputElement>(
        '[data-field="timeoutSeconds"] input',
      )?.value,
    ).toBe('60');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'SQL',
        databaseId: '9007199254740995',
        programConfig: undefined,
        timeoutSeconds: 60,
        executionConfig: expect.objectContaining({
          workloadMode: 'STREAMING',
          localParams: [{ prop: 'station', type: 'VARCHAR', value: 'A01' }],
        }),
      }),
    );
  });
  it('实时新任务从Flink切为SQL时清除原生程序配置并恢复单次执行超时', async () => {
    app.unmount();
    app = createApp(TaskForm, { workloadMode: 'STREAMING' });
    form = app.mount(host) as unknown as typeof form;
    await form.open();
    await selectType('SQL');
    expect(
      host.querySelector('[data-field="programConfig.workloadMode"]'),
    ).toBeNull();
    expect(
      host.querySelector<HTMLInputElement>(
        '[data-field="timeoutSeconds"] input',
      )?.value,
    ).toBe('60');
    input('name', '切换后的SQL');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'SQL',
        programConfig: undefined,
        timeoutSeconds: 60,
        executionConfig: expect.objectContaining({ workloadMode: 'STREAMING' }),
      }),
    );
  });
  it('修改责任人后按用户资料带出电话，保存仍携带无损编号', async () => {
    await form.open(undefined, undefined, { name: '责任人任务' });
    await selectField('ownerId', ['9007199254740999']);
    expect(
      host.querySelector<HTMLInputElement>('[data-field="contactNumber"] input')
        ?.value,
    ).toBe('13800000000');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        ownerId: '9007199254740999',
        contactNumber: '13800000000',
      }),
    );
  });
  it('编辑和预填任务的真实 null 周期均规范为空值，不提交字符串 null', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      taskType: 'SQL',
      name: '历史任务',
      cronExpression: null,
      legacyCronExpression: null,
      scheduleSyncStatus: null,
      scheduleSyncReason: null,
    });
    await form.open('9007199254740993');
    expect(host.querySelector<HTMLInputElement>('[data-cron]')?.value).toBe('');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        cronExpression: undefined,
        legacyCronExpression: null,
      }),
    );
    await form.open(undefined, undefined, {
      name: '预填任务',
      cronExpression: null,
      legacyCronExpression: null,
    });
    expect(host.querySelector<HTMLInputElement>('[data-cron]')?.value).toBe('');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        cronExpression: undefined,
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('保存新任务只写 DS 配置，即使填写周期也不手动执行或自动开启调度', async () => {
    await form.open(undefined, undefined, {
      name: '只保存的任务',
      content: 'SELECT 1;',
      cronExpression: '0 0 9 ? * 1',
      scheduler: 'XXL_JOB',
    });
    expect(
      host.querySelector<HTMLInputElement>('[data-cron]')?.dataset.weekMode,
    ).toBe('quartz');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        scheduler: 'DOLPHIN_SCHEDULER',
        cronExpression: '0 0 9 ? * 1',
      }),
    );
    expect(devApi.runDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTaskSchedule).not.toHaveBeenCalled();
  });

  it('任务详情加载失败时不提交空表单，重开后可恢复保存', async () => {
    vi.mocked(devApi.getDevTask).mockRejectedValueOnce(new Error('加载失败'));
    await form.open('9007199254740993');
    expect(host.textContent).not.toContain('任务信息加载失败');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).not.toHaveBeenCalled();
    expect(devApi.updateDevTask).not.toHaveBeenCalled();
    await form.open();
    input('name', '新任务');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({ name: '新任务' }),
    );
  });

  it('快速切换任务忽略旧详情响应，保存最新任务及修订号', async () => {
    let resolveOld: (task: DataforgeDevApi.Task) => void = () => {};
    vi.mocked(devApi.getDevTask).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    const firstOpen = form.open('9007199254740993');
    vi.mocked(devApi.getDevTask).mockResolvedValueOnce({
      id: '9007199254740995',
      name: '最新任务',
      taskType: 'SQL',
      revision: 9,
    });
    await form.open('9007199254740995');
    resolveOld({
      id: '9007199254740993',
      name: '旧任务',
      taskType: 'SQL',
      revision: 1,
    });
    await firstOpen;
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740995',
        name: '最新任务',
        expectedRevision: 9,
      }),
    );
  });

  it.each([
    'FLINK_SQL',
    'SPARK_SQL',
    'FLINK_JAR',
    'SPARK_JAR',
    'PYFLINK',
    'PYSPARK',
  ] as const)('%s 默认使用原生配置，部署与制品来自受管资源', async (type) => {
    await form.open();
    await selectType(type);
    expect(
      host.querySelector('[data-field="programConfig.workloadMode"]'),
    ).not.toBeNull();
    expect(host.textContent).toContain('任务配置未完成，完善后保存才可运行');
    const engine = devApi.getDevTaskEngine(type);
    expect(
      host.querySelector(
        `[data-field="programConfig.${engine === 'FLINK' ? 'parallelism' : 'driverMemoryMb'}"]`,
      ),
    ).not.toBeNull();
    expect(
      host.querySelector('[data-field="programConfig.pythonEnvironment"]') !==
        null,
    ).toBe(false);
    expect(
      host.querySelector('[data-field="programConfig.artifactFileId"]') !==
        null,
    ).toBe(!type.endsWith('_SQL'));
    expect(host.textContent).not.toContain('DS 数据源编号');
  });

  it.each(['FLINK_SQL', 'SPARK_SQL'] as const)(
    '%s 隐藏历史数据库选择器并保留空配置与平台绑定',
    async (taskType) => {
      vi.mocked(devApi.getDevTask).mockResolvedValue({
        id: '9007199254740993',
        taskType,
        name: '原有任务',
        databaseId: '9007199254740995',
        programConfig: null,
        scheduler: 'XXL_JOB',
        revision: 7,
      });
      await form.open('9007199254740993');
      expect(host.querySelector('[data-field="databaseId"]')).toBeNull();
      expect(
        host.querySelector('[data-field="programConfig.workloadMode"]'),
      ).toBeNull();
      host.querySelector<HTMLButtonElement>('[data-save]')!.click();
      await flush();
      expect(devApi.updateDevTask).toHaveBeenCalledWith(
        expect.objectContaining({
          id: '9007199254740993',
          databaseId: '9007199254740995',
          programConfig: null,
          scheduler: 'DOLPHIN_SCHEDULER',
          expectedRevision: 7,
        }),
      );
    },
  );

  it('数据库SQL仍提供数据源选择器，改为集群任务清理新任务绑定', async () => {
    await form.open(undefined, undefined, {
      name: '待选择引擎',
      taskType: 'SQL',
      databaseId: '9007199254740995',
    });
    expect(host.querySelector('[data-field="databaseId"]')).not.toBeNull();
    await selectType('SPARK_SQL');
    expect(host.querySelector('[data-field="databaseId"]')).toBeNull();
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'SPARK_SQL',
        databaseId: undefined,
        programConfig: { schemaVersion: 1, workloadMode: 'BATCH' },
      }),
    );
  });

  it('保存Python制品、依赖及参数时保留无损ID与原始参数', async () => {
    await form.open();
    await selectType('PYFLINK');
    await selectField('programConfig.runtimeProfileCode', ['flink-managed']);
    await selectField('programConfig.artifactFileId', ['9007199254740993']);
    await selectField('programConfig.dependencyFileIds', [
      '9007199254740995',
      '9007199254740997',
    ]);
    input('programConfig.arguments', '--name,hello world');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        taskType: 'PYFLINK',
        programConfig: expect.objectContaining({
          artifactFileId: '9007199254740993',
          dependencyFileIds: ['9007199254740995', '9007199254740997'],
          arguments: ['--name', 'hello world'],
          runtimeProfileCode: 'flink-managed',
          artifactSha256: 'a'.repeat(64),
          queue: 'managed',
        }),
      }),
    );
  });

  it('显式迁移旧SQL通道清除JDBC配置，防止被误用为原生参数', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      taskType: 'FLINK_SQL',
      databaseId: '9007199254740995',
      programConfig: null,
      executionConfig: {
        priority: 'HIGH',
        localParams: [{ prop: 'row_id', type: 'INTEGER', value: '1' }],
      },
    });
    await form.open('9007199254740993');
    const toggle = host.querySelector<HTMLInputElement>(
      'input[type=checkbox]',
    )!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    await flush();
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        databaseId: undefined,
        programConfig: expect.objectContaining({
          schemaVersion: 1,
          workloadMode: 'BATCH',
        }),
        executionConfig: { priority: 'HIGH', localParams: [] },
      }),
    );
  });

  it('重开数据库为空的集群任务再保存，保持空值而不是字符串null', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      taskType: 'FLINK_SQL',
      name: '空连接任务',
      databaseId: null,
    });
    await form.open('9007199254740993');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({ databaseId: undefined }),
    );
  });

  it('未填写制品的原生配置接收null响应，重开再保存保留空制品', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      taskType: 'FLINK_JAR',
      name: '待填写制品',
      programConfig: {
        schemaVersion: 1,
        workloadMode: 'BATCH',
        artifactFileId: null,
      },
    });
    await form.open('9007199254740993');
    expect(
      host.querySelector<HTMLSelectElement>(
        '[data-field="programConfig.artifactFileId"] select',
      )!.value,
    ).toBe('');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        programConfig: expect.objectContaining({ artifactFileId: undefined }),
      }),
    );
  });

  it('流任务切换为长期运行，保留0超时并可切回批任务默认超时', async () => {
    await form.open();
    await selectType('FLINK_SQL');
    await selectField('programConfig.workloadMode', ['STREAMING']);
    expect(host.textContent).toContain('超时设为 0');
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        timeoutSeconds: 0,
        programConfig: expect.objectContaining({ workloadMode: 'STREAMING' }),
      }),
    );
    await form.open();
    await selectType('FLINK_SQL');
    await selectField('programConfig.workloadMode', ['STREAMING']);
    await selectField('programConfig.workloadMode', ['BATCH']);
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.createDevTask).toHaveBeenLastCalledWith(
      expect.objectContaining({ timeoutSeconds: 300 }),
    );
  });

  it('原生 Spark SQL 不提供流模式，清楚显示真实 client 执行位置', async () => {
    await form.open();
    await selectType('SPARK_SQL');
    await selectField('programConfig.runtimeProfileCode', ['spark-managed']);
    const options = host.querySelector<HTMLSelectElement>(
      '[data-field="programConfig.workloadMode"] select',
    )!.options;
    expect(
      [...options].find((option) => option.value === 'STREAMING')?.disabled,
    ).toBe(true);
    expect(host.textContent).toContain('Driver 在受管执行节点运行');
  });

  it('更换部署档案清理旧制品与依赖，不将任意平台文件ID当可用资源', async () => {
    vi.mocked(devApi.getDevTask).mockResolvedValue({
      id: '9007199254740993',
      taskType: 'FLINK_JAR',
      programConfig: {
        schemaVersion: 1,
        workloadMode: 'BATCH',
        runtimeProfileCode: 'old-profile',
        artifactFileId: '9007199254740999',
        dependencyFileIds: ['9007199254740999'],
      },
    });
    await form.open('9007199254740993');
    await flush();
    expect(host.textContent).toContain('不可用受管资源：#9007199254740999');
    await selectField('programConfig.runtimeProfileCode', ['flink-managed']);
    host.querySelector<HTMLButtonElement>('[data-save]')!.click();
    await flush();
    expect(devApi.updateDevTask).toHaveBeenCalledWith(
      expect.objectContaining({
        programConfig: expect.objectContaining({
          runtimeProfileCode: 'flink-managed',
          artifactFileId: undefined,
          dependencyFileIds: [],
        }),
      }),
    );
  });
});
