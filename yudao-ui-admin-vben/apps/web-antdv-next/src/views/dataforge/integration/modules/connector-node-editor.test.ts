/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身仅模拟本组件依赖的输入事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { Modal } from 'antdv-next';
import { afterEach, describe, expect, it, vi } from 'vitest';

import * as databaseApi from '#/api/dataforge/database';

import ConnectorNodeEditor from './connector-node-editor.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
  getDatabaseTableList: vi.fn(async () => []),
  getDatabaseTableMeta: vi.fn(async () => ({ columns: [] })),
}));
vi.mock('antdv-next', () => {
  const Box = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.popupRender?.()]),
  });
  const Input = defineComponent({
    props: { value: [String, Number], options: Array },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          'data-options': JSON.stringify(props.options),
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const Modal = Object.assign(
    defineComponent({
      props: { open: Boolean },
      emits: ['ok', 'cancel'],
      setup:
        (props, { emit, slots }) =>
        () =>
          props.open
            ? h('section', { role: 'dialog' }, [
                slots.default?.(),
                h('button', { onClick: () => emit('ok') }, '保存属性'),
                h('button', { onClick: () => emit('cancel') }, '关闭属性'),
              ])
            : null,
    }),
    { confirm: vi.fn() },
  );
  return {
    Checkbox: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['change'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) => emit('change', event),
          }),
    }),
    Input,
    TextArea: Input,
    InputNumber: Input,
    Select: Input,
    AutoComplete: Input,
    RadioGroup: Input,
    Modal,
    Button: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    MenuItem: Box,
    Menu: Box,
    Dropdown: Box,
    Empty: Box,
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    message: { warning: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});

function mount(
  toolCode: string,
  config: Record<string, any>,
  executionReason?: string,
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled: false, executionReason });
  app = createApp({
    render: () =>
      h(ConnectorNodeEditor, {
        toolCode,
        config: state.config,
        disabled: state.disabled,
        executionReason: state.executionReason,
      }),
  });
  app.mount(host);
  return state;
}
async function click(label: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) =>
      item.textContent?.trim() === label ||
      item.getAttribute('aria-label') === label,
  );
  expect(button).toBeTruthy();
  button!.click();
  await nextTick();
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
  )!;
  expect(element).toBeTruthy();
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}

describe('连接器属性字段编辑与即时反馈', () => {
  it('执行提示使用当前任务的后端能力原因，更新后不保留旧DataX限制', async () => {
    const state = mount(
      'read-mongodb',
      { columns: [] },
      'Spark 支持受管集合读取。',
    );
    expect(host.textContent).toContain('Spark 支持受管集合读取。');
    expect(host.textContent).not.toContain('当前 DataX 发行包');
    state.executionReason = '当前实时模式暂不支持集合读取。';
    await nextTick();
    expect(host.textContent).toContain('当前实时模式暂不支持集合读取。');
    expect(host.textContent).not.toContain('Spark 支持受管集合读取。');
  });
  it('编辑 Hive 输入字段别名时保留物理字段与已有配置', async () => {
    const state = mount('read-hive', {
      databaseId: '1',
      tableName: 't',
      columns: [{ name: 'name', type: 'STRING', custom: '保留' }],
    });
    await input('第1行输出字段名', 'label');
    expect(state.config.columns[0]).toMatchObject({
      name: 'name',
      alias: 'label',
      custom: '保留',
    });
  });
  it('受管 FTP 无需选择 SQL 数据连接，历史连接仅在明确操作后移除', async () => {
    const state = mount('write-ftp', {
      databaseId: '9007199254740993',
      resourceBindingId: 'ftp.fixture',
      credentialRef: 'ftp.fixture',
      path: 'out',
      columns: [{ name: 'id', type: 'LONG' }],
    });
    expect(host.querySelector('[aria-label="连接器数据连接"]')).toBeNull();
    expect(state.config.databaseId).toBe('9007199254740993');
    await click('移除历史数据连接');
    expect(state.config.databaseId).toBeUndefined();
    expect(state.config).toMatchObject({
      resourceBindingId: 'ftp.fixture',
      credentialRef: 'ftp.fixture',
      path: 'out',
    });
  });

  it('输出字段改名保留来源并生成与后端一致的显式映射', async () => {
    const state = mount('write-rocketmq', {
      databaseId: '1',
      topic: 'events',
      groupId: 'group',
      columns: [{ name: 'name', key: 'name', type: 'STRING', index: 0 }],
    });
    await input('第1行字段名称', '标签');
    expect(state.config.columns[0]).toMatchObject({
      name: '标签',
      key: 'name',
    });
    expect(state.config.fieldMappings).toEqual([
      { source: 'name', target: '标签', enabled: true },
    ]);
  });

  it('非文件字段重排时同步内部顺序编号并保留来源映射', async () => {
    const editor = mount('write-rocketmq', {
      databaseId: '1',
      topic: 'events',
      groupId: 'group',
      columns: [
        { name: 'id', key: 'id', type: 'LONG', index: 0 },
        { name: '标签', key: 'name', type: 'STRING', index: 1 },
      ],
    });
    host
      .querySelector('tr[tabindex="0"]')!
      .dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'ArrowDown',
          altKey: true,
          bubbles: true,
        }),
      );
    await nextTick();
    expect(editor.config.columns).toEqual([
      { name: '标签', key: 'name', type: 'STRING', index: 0 },
      { name: 'id', key: 'id', type: 'LONG', index: 1 },
    ]);
    expect(editor.config.fieldMappings).toEqual([
      { source: 'name', target: '标签', enabled: true },
      { source: 'id', target: 'id', enabled: true },
    ]);
  });

  it.each(['read-hdfs', 'read-ftp'])(
    '%s 重排和删除后仍读取原文件的物理列位置',
    async (toolCode) => {
      const state = mount(toolCode, {
        columns: [
          { name: 'id', type: 'LONG', index: 0 },
          { name: 'name', type: 'STRING', index: 5 },
        ],
      });
      host
        .querySelector('tr[tabindex="0"]')!
        .dispatchEvent(
          new KeyboardEvent('keydown', {
            key: 'ArrowDown',
            altKey: true,
            bubbles: true,
          }),
        );
      await nextTick();
      expect(state.config.columns).toEqual([
        { name: 'name', type: 'STRING', index: 5 },
        { name: 'id', type: 'LONG', index: 0 },
      ]);
      await click('删除第2行字段');
      const confirmation = vi.mocked(Modal.confirm).mock.calls.at(-1)![0] as {
        onOk: () => void;
      };
      confirmation.onOk();
      await nextTick();
      expect(state.config.columns).toEqual([
        { name: 'name', type: 'STRING', index: 5 },
      ]);
    },
  );

  it('关闭不改草稿，保存验证重名/精度并保留原附加参数', async () => {
    const state = mount('read-mongodb', {
      databaseId: '9007199254740993',
      tableName: 't',
      columns: [
        {
          name: 'a',
          type: 'DECIMAL',
          precision: '2',
          format: '0.00',
          custom: '保留',
        },
        { name: 'b', type: 'STRING' },
      ],
    });
    await click('修改第1行属性字段');
    await input('属性字段编辑名称', 'cancelled');
    expect(state.config.columns[0].name).toBe('a');
    await click('关闭属性');
    expect(state.config.columns[0].name).toBe('a');
    await click('修改第1行属性字段');
    await input('属性字段编辑名称', 'b');
    await click('保存属性');
    expect(host.textContent).toContain('字段b重复');
    expect(state.config.columns[0].name).toBe('a');
    await input('属性字段编辑名称', 'amount');
    await input('属性字段编辑精度', 'invalid');
    await click('保存属性');
    expect(host.textContent).toContain('小数精度必须为非负整数');
    await input('属性字段编辑精度', '4');
    await click('保存属性');
    expect(state.config.columns[0]).toMatchObject({
      name: 'amount',
      type: 'DECIMAL',
      precision: '4',
      format: '0.00',
      custom: '保留',
    });
    expect(host.querySelector('[role="dialog"]')).toBeNull();
  });

  it('节点切换和只读变更使未保存属性编辑失效', async () => {
    const oldConfig = {
      databaseId: '1',
      tableName: 'a',
      columns: [{ name: 'a', type: 'STRING' }],
    };
    const state = mount('read-mongodb', oldConfig);
    await click('修改第1行属性字段');
    await input('属性字段编辑名称', 'stale');
    state.config = {
      databaseId: '2',
      tableName: 'b',
      columns: [{ name: 'fresh', type: 'LONG' }],
    };
    await nextTick();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(oldConfig.columns[0]!.name).toBe('a');
    await click('修改第1行属性字段');
    state.disabled = true;
    await nextTick();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(state.config.columns[0].name).toBe('fresh');
  });

  it('json错误和嵌套凭据输入后立即反馈，无需失焦或保存', async () => {
    const state = mount('read-rabbitmq', {
      databaseId: '1',
      topic: 'events',
      config: '{}',
      columns: [{ name: 'id', type: 'LONG' }],
    });
    expect(host.textContent).toContain('JSON对象格式有效');
    await input('配置参数', '{');
    expect(host.textContent).toContain('配置参数必须为有效的JSON对象');
    await input('配置参数', '{"nested":{"token":"placeholder"}}');
    expect(host.textContent).toContain('配置参数不可保存凭据');
    await input('配置参数', '{"queue":"events"}');
    expect(host.textContent).toContain('JSON对象格式有效');
    expect(state.config.config).toBe('{"queue":"events"}');
  });
  it('受管服务凭据引用即时校验且仅保存引用编号', async () => {
    const state = mount('read-webservice', {
      columns: [],
      headerSecretRefs: '{}',
    });
    await input('请求头凭据引用', '{"Authorization":"service.auth"}');
    expect(host.textContent).toContain('凭据引用格式有效');
    expect(state.config.headerSecretRefs).toBe(
      '{"Authorization":"service.auth"}',
    );
    await input('请求头凭据引用', '{"Authorization":"Bearer plaintext"}');
    expect(host.textContent).toContain(
      '请求头凭据引用只能填写请求头名称和已授权凭据引用编号',
    );
  });
  it('历史订阅节点仅在明确操作后清理表读取参数，保留订阅和未知配置', async () => {
    const state = mount('read-tdengine', {
      databaseId: '1',
      topic: 'events',
      groupId: 'group',
      tableName: 'old',
      whereCondition: 'id > 1',
      custom: 42,
      columns: [{ name: 'id', type: 'LONG' }],
    });
    expect(host.textContent).toContain('历史表读取参数不适用于持续订阅');
    expect(state.config.tableName).toBe('old');
    expect(host.querySelector('[aria-label="选择表"]')).toBeNull();
    await click('清除历史表读取参数');
    expect(state.config.tableName).toBeUndefined();
    expect(state.config.whereCondition).toBeUndefined();
    expect(state.config).toMatchObject({
      topic: 'events',
      groupId: 'group',
      custom: 42,
    });
  });
  it('表目录与字段解析使用真实辅助契约，零精度和未知属性无损保留', async () => {
    vi.mocked(databaseApi.getDatabaseTableList).mockResolvedValueOnce([
      { tableName: 'events', remarks: '事件' },
    ]);
    vi.mocked(databaseApi.getDatabaseTableMeta).mockResolvedValueOnce({
      tableName: 'events',
      columns: [
        {
          fieldName: 'amount',
          fieldTypeName: 'DECIMAL',
          scale: 0,
          length: 30,
          pk: false,
        },
      ],
    });
    const state = mount('read-hive', {
      databaseId: '9007199254740993',
      tableName: 'events',
      columns: [{ name: 'amount', type: 'OLD', custom: 42 }],
    });
    await nextTick();
    await click('解析');
    await nextTick();
    expect(databaseApi.getDatabaseTableMeta).toHaveBeenLastCalledWith(
      '9007199254740993',
      'events',
    );
    expect(state.config.columns[0]).toMatchObject({
      name: 'amount',
      type: 'DECIMAL',
      precision: 0,
      length: 30,
      primaryKey: false,
      custom: 42,
    });
  });
  it('切换连接或草稿后迟到的元数据不能覆盖当前字段', async () => {
    let finish!: (
      value: databaseApi.FoundationDatabaseApi.DatabaseTableMeta,
    ) => void;
    vi.mocked(databaseApi.getDatabaseTableMeta).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const original = {
      databaseId: '1',
      tableName: 'old',
      columns: [{ name: 'old', type: 'LONG' }],
    };
    const state = mount('read-hive', original);
    await click('解析');
    state.config = {
      databaseId: '2',
      tableName: 'new',
      columns: [{ name: 'new', type: 'STRING' }],
    };
    await nextTick();
    finish({
      tableName: 'old',
      columns: [{ fieldName: 'stale', fieldTypeName: 'LONG' }],
    });
    await nextTick();
    await nextTick();
    expect(state.config.columns).toEqual([{ name: 'new', type: 'STRING' }]);
    expect(original.columns).toEqual([{ name: 'old', type: 'LONG' }]);
  });
  it.each(['read-rabbitmq', 'read-rocketmq', 'read-hdfs', 'read-ftp', 'read-redis'])(
    '%s 新增属性在保存前隔离，取消不留下空行',
    async (toolCode) => {
      const original = toolCode.endsWith('redis')
        ? { name: 'id', type: 'LONG', key: '@key' }
        : { name: 'id', type: 'LONG', index: 0 };
      const state = mount(toolCode, { columns: [original] });
      await click('新增');
      expect(state.config.columns).toEqual([original]);
      await input('属性字段编辑名称', 'draft');
      await click('关闭属性');
      expect(state.config.columns).toEqual([original]);
      await click('新增');
      await input('属性字段编辑名称', 'newField');
      if (toolCode.endsWith('redis')) await input('属性字段编辑取值', '@key');
      await click('保存属性');
      expect(state.config.columns).toHaveLength(2);
      expect(state.config.columns[1]).toMatchObject({ name: 'newField', type: 'STRING' });
      expect(host.querySelector('[role="dialog"]')).toBeNull();
    },
  );
  it('缓存Redis历史redisField回显和修改与现有key同步，数据类型切换不丢失字段', async () => {
    const state = mount('read-redis', { databaseId: '1', dataType: 'hash', keyPattern: 'sensor:*', columns: [{ name: 'value', type: 'STRING', redisField: 'old', custom: 42 }] });
    expect(host.querySelector('table[aria-label="属性字段"]')?.textContent).toContain('old');
    await click('修改第1行属性字段');
    expect(host.querySelector<HTMLInputElement>('[aria-label="属性字段编辑取值"]')?.value).toBe('old');
    await input('属性字段编辑取值', '@index');
    await click('保存属性');
    expect(host.textContent).toContain('解析值与Redis数据类型不一致');
    await input('属性字段编辑取值', 'new');
    await click('保存属性');
    expect(state.config.columns[0]).toMatchObject({ redisField: 'new', key: 'new', custom: 42 });
    await input('数据类型', 'list');
    expect(state.config.columns[0]).toMatchObject({ redisField: 'new', key: 'new', custom: 42 });
  });
  it('连接选项按当前输入类型筛选且保留未知已选编号，失败重试后恢复', async () => {
    vi.mocked(databaseApi.getDatabaseSimpleList).mockRejectedValueOnce(new Error('offline'));
    const state = mount('read-redis', { databaseId: '9007199254740993', columns: [] });
    await nextTick();
    await nextTick();
    expect(host.textContent).toContain('数据连接加载失败');
    let select = host.querySelector<HTMLInputElement>('[aria-label="连接器数据连接"]')!;
    expect(JSON.parse(select.dataset.options!)).toContainEqual({ label: '已选连接（9007199254740993）', value: '9007199254740993' });
    vi.mocked(databaseApi.getDatabaseSimpleList).mockResolvedValueOnce([
      { id: '2', name: '缓存', productId: 'REDIS' },
      { id: '3', name: '消息', databaseTypeName: 'RabbitMQ' },
    ]);
    await click('重试');
    await nextTick();
    expect(host.textContent).not.toContain('数据连接加载失败');
    select = host.querySelector('[aria-label="连接器数据连接"]')!;
    expect(JSON.parse(select.dataset.options!)).toEqual([
      { label: '已选连接（9007199254740993）', value: '9007199254740993' },
      { label: '缓存', value: '2' },
    ]);
    state.disabled = true;
    await nextTick();
    expect(select.disabled).toBe(true);
    expect([...host.querySelectorAll('.qi-connector-advanced input')].every((input) => (input as HTMLInputElement).disabled)).toBe(true);
    await input('连接器数据连接', '2');
    expect(state.config.databaseId).toBe('9007199254740993');
  });
  it('文件FTP输入可关联平台连接且保留独立执行资源，输出继续无需连接', async () => {
    const state = mount('read-ftp', { resourceBindingId: 'ftp.files', credentialRef: 'ftp.credentials', columns: [] });
    expect(host.querySelector('[aria-label="连接器数据连接"]')).toBeTruthy();
    await input('连接器数据连接', '9007199254740993');
    expect(state.config).toMatchObject({ databaseId: '9007199254740993', resourceBindingId: 'ftp.files', credentialRef: 'ftp.credentials' });
  });

});
