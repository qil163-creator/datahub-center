import type { App, ShallowRef } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, shallowRef } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import TaskDetailView from './task-detail-view.vue';

vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: number) => `时间 ${value}`,
}));
vi.mock('./task-type-icon.vue', () => ({
  default: defineComponent({
    props: { taskType: String },
    setup: (props) => () => h('span', { 'data-task-icon': props.taskType }),
  }),
}));
vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    props: {
      modelValue: String,
      readonly: Boolean,
      language: String,
      height: String,
    },
    setup: (props) => () =>
      h(
        'pre',
        {
          'data-readonly': props.readonly,
          'data-language': props.language,
          'data-editor-height': props.height,
        },
        props.modelValue,
      ),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
let currentTask: ShallowRef<DataforgeDevApi.Task>;
async function mount(
  task: DataforgeDevApi.Task,
  presentation: 'config' | 'dialog' | 'page' = 'dialog',
  onBack?: () => void,
) {
  host = document.createElement('div');
  document.body.append(host);
  currentTask = shallowRef(task);
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(
          TaskDetailView,
          {
            task: currentTask.value,
            directoryName: '报表与统计',
            ownerName: '任务责任人',
            creatorName: '创建人',
            presentation,
          },
          onBack
            ? {
                headingExtra: () =>
                  h('button', { 'data-back': '', onClick: onBack }, '返回'),
              }
            : undefined,
        ),
    }),
  );
  app.mount(host);
  await nextTick();
}
async function showInfo() {
  host.querySelectorAll<HTMLElement>('[role="tab"]')[1]!.click();
  await nextTick();
}
afterEach(() => {
  app?.unmount();
  host?.remove();
});

describe('数据开发任务详情', () => {
  it('作业配置弹窗展示只读 SQL 脚本、分段符和参数值', async () => {
    await mount({ id: '9007199254740993', taskType: 'SQL', content: 'SELECT 9007199254740995', dbType: 'POSTGRESQL', executionConfig: { sqlType: 'QUERY', statementSeparator: ';', localParams: [{ prop: 'business_id', type: 'LONG', value: '9007199254740995' }] } }, 'config');
    const editor = host.querySelector('pre');
    expect(editor?.textContent).toBe('SELECT 9007199254740995');
    expect(editor?.dataset.readonly).toBe('true');
    expect(host.textContent).toContain('分段执行符号');
    expect(host.textContent).toContain('9007199254740995');
    expect(host.textContent).toContain('查询');
  });
  it('配置详情按只读字段呈现类目、责任人与真实调度配置', async () => {
    await mount(
      {
        id: '9007199254740993',
        taskType: 'FLINK_SQL',
        name: '雨量实时汇总',
        status: 0,
        contactNumber: '13800000000',
        cronExpression: '0 0 2 * * ?',
        programConfig: {
          workloadMode: 'STREAMING',
          checkpointIntervalMs: 5000,
        },
      },
      'config',
    );
    expect(host.querySelector('.task-reference-detail')).not.toBeNull();
    expect(host.textContent).toContain('基本信息');
    expect(host.textContent).toContain('报表与统计');
    expect(host.textContent).toContain('任务责任人');
    expect(host.textContent).toContain('DOLPHINSCHEDULER');
    expect(host.textContent).toContain('0 0 2 * * ?');
    expect(host.textContent).toContain('5000 ms');
    expect(host.querySelectorAll('input, select, textarea')).toHaveLength(0);
  });
  it('历史任务缺少同步元数据时保持只读空态，不输出 null 或 undefined', async () => {
    await mount({
      id: '9007199254740993',
      taskType: 'SQL',
      currentVersionId: null,
      scheduleSyncStatus: null,
      scheduleSyncReason: null,
      legacyCronExpression: null,
    });
    await showInfo();
    expect(host.textContent).toContain('DolphinScheduler');
    expect(host.textContent).not.toContain('null');
    expect(host.textContent).not.toContain('undefined');
    expect(host.textContent).not.toContain('DS 已同步');
  });

  it.each([
    ['PENDING', 'DS 同步中'],
    ['ERROR', 'DS 同步失败'],
  ] as const)(
    '%s 详情保留独立同步状态与旧周期，不当作 DS 已同步',
    async (scheduleSyncStatus, label) => {
      await mount({
        id: '9007199254740993',
        taskType: 'SQL',
        scheduleStatus: 1,
        scheduleSyncStatus,
        scheduleSyncReason: '调度服务尚未确认',
        legacyCronExpression: '0 0 9 ? * 0',
      });
      await showInfo();
      expect(host.textContent).toContain('DolphinScheduler');
      expect(host.textContent).toContain(label);
      expect(host.textContent).toContain('调度服务尚未确认');
      expect(host.textContent).toContain('0 0 9 ? * 0');
      expect(host.textContent).not.toContain('DS 已同步');
      expect(host.querySelectorAll('input, select, textarea')).toHaveLength(0);
    },
  );

  it('以只读脚本与字段摘要呈现详情，保留无损任务编号及数据库方言', async () => {
    await mount({
      id: '9007199254740993',
      name: '每日水位统计',
      taskType: 'SQL',
      dbType: 'POSTGRESQL',
      content: 'SELECT 1;',
      status: 1,
      scheduleStatus: 0,
    });
    expect(host.textContent).toContain('9007199254740993');
    expect(host.textContent).toContain('每日水位统计');
    expect(host.textContent).toContain('报表与统计');
    expect(host.textContent).toContain('已上线');
    expect(host.querySelector('pre')?.dataset.readonly).toBe('true');
    expect(host.querySelector('pre')?.dataset.language).toBe('pgsql');
    expect(host.querySelectorAll('input, select, textarea')).toHaveLength(0);
  });

  it('延迟时间按分钟展示，参数值保留文本精度，真实执行策略为串行', async () => {
    const exact = '9007199254740993.123456789';
    await mount({
      id: 1,
      taskType: 'SQL',
      timeoutSeconds: 300,
      executionConfig: {
        delayMinutes: 0,
        sqlType: 'QUERY',
        localParams: [{ prop: 'amount', type: 'VARCHAR', value: exact }],
      },
    });
    await showInfo();
    expect(host.textContent).toContain('0 分钟');
    expect(host.textContent).not.toContain('1970');
    expect(host.textContent).toContain(exact);
    expect(host.textContent).toContain('串行');
    expect(host.textContent).toContain('300 秒');
    expect(host.textContent).not.toContain('Quartz');
  });

  it('原生 Flink 任务按资源分组展示，不呈现 JDBC 绑定参数', async () => {
    await mount({
      id: 2,
      taskType: 'FLINK_SQL',
      engine: 'FLINK',
      content: 'INSERT INTO sink VALUES (1);',
      timeoutSeconds: 0,
      programConfig: {
        workloadMode: 'STREAMING',
        runtimeProfileCode: 'managed-flink',
        jobManagerMemoryMb: 1024,
        taskManagerSlots: 2,
        parallelism: 4,
      },
      executionConfig: {
        localParams: [
          { prop: 'legacy-param', type: 'VARCHAR', value: 'legacy-value' },
        ],
      },
    });
    expect(
      host.querySelector<HTMLElement>('[data-task-icon]')?.dataset.taskIcon,
    ).toBe('FLINK_SQL');
    expect(host.querySelector('pre')?.dataset.language).toBe('flinksql');
    await showInfo();
    expect(host.textContent).toContain('运行环境与资源');
    expect(host.textContent).toContain('长期运行');
    expect(host.textContent).toContain('managed-flink');
    expect(host.textContent).toContain('1024 MB');
    expect(host.textContent).not.toContain('legacy-param');
    expect(host.textContent).not.toContain('分段执行符号');
  });

  it('原生 JAR 详情展示受管制品，Python 文本明确作为源码参考', async () => {
    await mount({
      taskType: 'SPARK_JAR',
      programConfig: {
        artifactFileId: '9007199254740993',
        entryPoint: 'example.Main',
        artifactSha256: 'a'.repeat(64),
      },
    });
    expect(host.textContent).toContain('执行制品');
    expect(host.textContent).toContain('9007199254740993');
    expect(host.textContent).toContain('example.Main');
    expect(host.querySelector('pre')).toBeNull();
    app?.unmount();
    host.remove();
    await mount({ taskType: 'PYFLINK', content: 'print("example")' });
    expect(host.textContent).toContain('仅作源码参考');
    expect(host.querySelector('pre')?.dataset.language).toBe('python');
  });

  it('页面以独立摘要与内容卡呈现，返回插槽可操作，脚本保持只读', async () => {
    const back = vi.fn();
    await mount(
      {
        id: '9007199254740993',
        name: '水位统计任务',
        taskType: 'SQL',
        content: 'SELECT station_code FROM water_level;',
      },
      'page',
      back,
    );
    expect(host.querySelector('.task-detail-view.is-page')).not.toBeNull();
    expect(host.querySelector('h1')?.textContent).toContain('水位统计任务');
    expect(host.querySelectorAll('.task-detail-view > section')).toHaveLength(
      2,
    );
    expect(host.querySelector('pre')?.dataset.editorHeight).toBe(
      'clamp(360px, 54vh, 720px)',
    );
    expect(host.querySelector('pre')?.dataset.readonly).toBe('true');
    host.querySelector<HTMLButtonElement>('[data-back]')!.click();
    expect(back).toHaveBeenCalledOnce();
  });

  it('详情分页切换保留只读编辑器，切换任务后展示新脚本与配置', async () => {
    await mount(
      {
        id: '9007199254740993',
        taskType: 'SQL',
        content: 'SELECT old_value;',
        executionConfig: {
          delayMinutes: 1,
          localParams: [{ prop: '旧参数', type: 'VARCHAR', value: 'old' }],
        },
      },
      'page',
    );
    const editor = host.querySelector('pre');
    await showInfo();
    host.querySelectorAll<HTMLElement>('[role="tab"]')[0]!.click();
    await nextTick();
    await showInfo();
    expect(host.querySelector('pre')).toBe(editor);
    currentTask.value = {
      id: '9007199254740995',
      name: '新的任务',
      taskType: 'SPARK_SQL',
      content: 'SELECT new_value;',
      programConfig: { runtimeProfileCode: '新部署档案', driverMemoryMb: 2048 },
    };
    await nextTick();
    expect(host.textContent).toContain('9007199254740995');
    expect(host.querySelector('pre')?.textContent).toContain(
      'SELECT new_value;',
    );
    expect(host.querySelector('pre')?.dataset.language).toBe('sparksql');
    expect(host.textContent).toContain('新部署档案');
    expect(host.textContent).toContain('2048 MB');
    expect(host.textContent).not.toContain('旧参数');
    currentTask.value = {
      ...currentTask.value,
      content: 'SELECT refreshed_value;',
      programConfig: {
        ...currentTask.value.programConfig,
        driverMemoryMb: 4096,
      },
    };
    await nextTick();
    expect(host.querySelector('pre')?.textContent).toContain(
      'SELECT refreshed_value;',
    );
    expect(host.textContent).toContain('4096 MB');
  });
});
