/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 测试控件替身仅用于驱动真实事件与草稿提交。 */
import type { App } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import WorkbenchPropertiesPanel from './WorkbenchPropertiesPanel.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'test-session' }),
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (original) => ({
  ...(await original<typeof import('#/api/dataforge/dev')>()),
  getDevRuntimeProfiles: vi.fn(async () => []),
}));
vi.mock('antdv-next', async () => {
  const { defineComponent, h } = await import('vue');
  const input = (numeric = false) =>
    defineComponent({
      inheritAttrs: false,
      props: ['value', 'disabled', 'placeholder'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value ?? '',
            disabled: props.disabled,
            placeholder: props.placeholder,
            onInput: (event: Event) =>
              emit(
                'update:value',
                numeric
                  ? Number((event.target as HTMLInputElement).value)
                  : (event.target as HTMLInputElement).value,
              ),
          }),
    });
  const Input = Object.assign(input(), { TextArea: input() });
  return {
    Input,
    InputNumber: input(true),
    Select: defineComponent({
      inheritAttrs: false,
      props: ['value', 'options', 'disabled', 'mode'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: props.value,
              disabled: props.disabled,
              multiple: props.mode === 'multiple',
              onChange: (event: Event) =>
                emit(
                  'update:value',
                  props.mode === 'multiple'
                    ? [
                        ...(event.target as HTMLSelectElement).selectedOptions,
                      ].map((item) => item.value)
                    : (event.target as HTMLSelectElement).value,
                ),
            },
            (props.options ?? []).map(
              (item: { disabled?: boolean; label: string; value: string }) =>
                h(
                  'option',
                  { value: item.value, disabled: item.disabled },
                  item.label,
                ),
            ),
          ),
    }),
    Button: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
async function mount(
  task: DataforgeDevApi.Task,
  extra: Record<string, unknown> = {},
) {
  host = document.createElement('div');
  document.body.append(host);
  const saved = vi.fn();
  const cancel = vi.fn();
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(WorkbenchPropertiesPanel, {
          task,
          runtimeProfiles: [],
          onSave: saved,
          onCancel: cancel,
          ...extra,
        }),
    }),
  );
  app.mount(host);
  await nextTick();
  return { saved, cancel };
}
function button(label: string) {
  return [...host!.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
async function input(label: string, value: string) {
  const element = host!.querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
  )!;
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}

describe('工作台属性配置草稿', () => {
  it('分别按Flink与Spark显示截图中的真实资源字段', async () => {
    await mount({
      taskType: 'FLINK_SQL',
      programConfig: { workloadMode: 'BATCH' },
    });
    expect(host!.textContent).toContain('JobManager内存数');
    expect(host!.textContent).toContain('Slot数量');
    expect(host!.textContent).not.toContain('Driver核心数');
    app!.unmount();
    host!.remove();
    await mount({
      taskType: 'SPARK_SQL',
      programConfig: { workloadMode: 'BATCH' },
    });
    expect(host!.textContent).toContain('Driver核心数');
    expect(host!.textContent).toContain('Executor核心数');
    expect(host!.textContent).not.toContain('JobManager内存数');
  });
  it('内存单位转换为真实MB契约且保存前不修改父级任务', async () => {
    const task: DataforgeDevApi.Task = {
      taskType: 'FLINK_SQL',
      programConfig: { jobManagerMemoryMb: 1024, workloadMode: 'BATCH' },
    };
    const { saved } = await mount(task);
    expect(
      host!.querySelector<HTMLInputElement>('[aria-label="JobManager内存数"]')!
        .value,
    ).toBe('1G');
    await input('JobManager内存数', '2G');
    expect(task.programConfig!.jobManagerMemoryMb).toBe(1024);
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        programConfig: expect.objectContaining({ jobManagerMemoryMb: 2048 }),
      }),
    );
  });
  it('非法内存单位不能伪装为合法配置提交', async () => {
    const { saved } = await mount({
      taskType: 'SPARK_SQL',
      programConfig: { workloadMode: 'BATCH' },
    });
    await input('Driver内存数', '1TB');
    button('保存').click();
    await nextTick();
    expect(saved).not.toHaveBeenCalled();
    expect(host!.querySelector('[role="alert"]')!.textContent).toContain(
      '请输入 128M',
    );
  });
  it('本地 LOCAL 环境在保存前校验真实单核和内存预算', async () => {
    const { saved } = await mount(
      {
        taskType: 'FLINK_SQL',
        programConfig: {
          workloadMode: 'BATCH',
          runtimeProfileCode: 'local-flink',
        },
      },
      {
        runtimeProfiles: [
          {
            code: 'local-flink',
            name: '受管本地 Flink',
            engine: 'FLINK',
            engineVersion: '1.20.3',
            deployMode: 'LOCAL',
            allowedTaskTypes: ['FLINK_SQL'],
            artifacts: [],
            dependencyResources: [],
          },
        ],
      },
    );
    await input('TaskManager内存数', '2G');
    button('保存').click();
    await nextTick();
    expect(saved).not.toHaveBeenCalled();
    expect(host!.querySelector('[role="alert"]')!.textContent).toContain(
      '当前 LOCAL 环境仅支持',
    );
    await input('TaskManager内存数', '768M');
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        programConfig: expect.objectContaining({ taskManagerMemoryMb: 768 }),
      }),
    );
  });
  it('原生参数明确禁用，遗留JDBC参数需显式清空后才能保存', async () => {
    const { saved } = await mount({
      taskType: 'FLINK_SQL',
      programConfig: { workloadMode: 'BATCH' },
      executionConfig: {
        localParams: [{ prop: 'station', type: 'VARCHAR', value: 'one' }],
      },
    });
    expect(button('＋ 添加配置项').disabled).toBe(true);
    expect(
      host!.querySelector<HTMLInputElement>('[aria-label="参数名称"]')!
        .disabled,
    ).toBe(true);
    button('保存').click();
    await nextTick();
    expect(saved).not.toHaveBeenCalled();
    button('清空不兼容参数').click();
    await nextTick();
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        executionConfig: expect.objectContaining({ localParams: [] }),
      }),
    );
  });
  it('自定义JDBC参数可以增删并保留大连接编号字符串', async () => {
    const { saved } = await mount(
      { taskType: 'SQL', databaseId: '9007199254740993', dbType: 'MYSQL' },
      {
        databases: [
          { value: '9007199254740993', label: '测试连接', productId: 'MYSQL' },
        ],
      },
    );
    button('＋ 添加配置项').click();
    await nextTick();
    await input('参数名称', 'station');
    await input('参数值', 'ST01');
    button('保存').click();
    expect(saved).toHaveBeenLastCalledWith(
      expect.objectContaining({
        databaseId: '9007199254740993',
        executionConfig: expect.objectContaining({
          localParams: [{ prop: 'station', type: 'VARCHAR', value: 'ST01' }],
        }),
      }),
    );
    host!
      .querySelector<HTMLButtonElement>('[aria-label="删除参数 1"]')!
      .click();
    await nextTick();
    expect(host!.querySelector('[aria-label="参数名称"]')).toBeNull();
  });
  it('取消丢弃局部草稿，readonly不发送保存', async () => {
    const task: DataforgeDevApi.Task = {
      taskType: 'SQL',
      executionConfig: { retryTimes: 2 },
    };
    const { saved, cancel } = await mount(task);
    await input('失败重试次数', '3');
    button('取消').click();
    await nextTick();
    expect(cancel).toHaveBeenCalledOnce();
    expect(task.executionConfig!.retryTimes).toBe(2);
    expect(
      host!.querySelector<HTMLInputElement>('[aria-label="失败重试次数"]')!
        .value,
    ).toBe('2');
    app!.unmount();
    host!.remove();
    const readOnly = await mount(task, { readonly: true });
    button('保存').click();
    expect(readOnly.saved).not.toHaveBeenCalled();
    expect(saved).not.toHaveBeenCalled();
  });
  it('高级环境选择只使用真实受管档案并同步其版本和队列', async () => {
    const profile: DataforgeDevApi.RuntimeProfile = {
      code: 'managed-flink',
      name: '受管本地环境',
      engine: 'FLINK',
      engineVersion: '1.20.3',
      deployMode: 'LOCAL',
      queue: 'default',
      allowedTaskTypes: ['FLINK_SQL'],
      artifacts: [],
      dependencyResources: [],
    };
    const { saved } = await mount(
      { taskType: 'FLINK_SQL', programConfig: { workloadMode: 'STREAMING' } },
      { runtimeProfiles: [profile] },
    );
    const select = host!.querySelector<HTMLSelectElement>(
      '[aria-label="受管运行环境"]',
    )!;
    select.value = 'managed-flink';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await nextTick();
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        programConfig: expect.objectContaining({
          runtimeProfileCode: 'managed-flink',
          engineVersion: '1.20.3',
          queue: 'default',
        }),
      }),
    );
  });
  it('实时页面 Spark SQL 保留页面归属并使用批运行模式与正超时', async () => {
    const { saved } = await mount(
      {
        taskType: 'SPARK_SQL',
        executionConfig: { workloadMode: 'STREAMING' },
        programConfig: { workloadMode: 'BATCH' },
        timeoutSeconds: 300,
      },
      { workloadMode: 'STREAMING' },
    );
    expect(
      host!.querySelector<HTMLSelectElement>('[aria-label="运行模式"]')!
        .disabled,
    ).toBe(true);
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        executionConfig: expect.objectContaining({ workloadMode: 'STREAMING' }),
        programConfig: expect.objectContaining({ workloadMode: 'BATCH' }),
        timeoutSeconds: 300,
      }),
    );
  });
});
