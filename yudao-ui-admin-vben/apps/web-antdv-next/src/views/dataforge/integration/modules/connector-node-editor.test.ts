/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身仅模拟本组件依赖的输入事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { Modal } from 'antdv-next';
import { afterEach, describe, expect, it, vi } from 'vitest';

import * as databaseApi from '#/api/dataforge/database';

import ConnectorNodeEditor from './connector-node-editor.vue';
import { connectorDefaults, validateConnectorConfig } from './connector-schema';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
  getDatabaseTableList: vi.fn(async () => []),
  getDatabaseTableMeta: vi.fn(async () => ({ columns: [] })),
}));
// The categorized picker is covered separately; here it only needs to emit the chosen id.
vi.mock('./database-connection-picker.vue', () => ({
  default: defineComponent({
    props: { databases: Array, typeCodes: Array, value: [String, Number] },
    emits: ['change'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          'aria-label': '连接器数据连接',
          'data-databases': JSON.stringify(
            (props.databases as { id: string }[] | undefined)?.map((item) => item.id),
          ),
          'data-type-codes': JSON.stringify(props.typeCodes),
          value: props.value,
          onInput: (event: Event) =>
            emit('change', (event.target as HTMLInputElement).value),
        }),
  }),
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
    Form: Box,
    FormItem: defineComponent({
      props: { help: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [slots.default?.(), props.help ? h('small', props.help) : null]),
    }),
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
const editor = ref<{ validate: () => boolean }>();
afterEach(() => {
  app?.unmount();
  host?.remove();
});

function mount(
  toolCode: string,
  config: Record<string, any>,
  inputFields: { dataType: string; name: string }[] = [],
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled: false });
  app = createApp({
    render: () =>
      h(ConnectorNodeEditor, {
        ref: editor,
        toolCode,
        config: state.config,
        disabled: state.disabled,
        inputFields,
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
  it('新FTP填写主配置和属性字段后可以保存，执行检查仍要求授权资源', async () => {
    const state = mount('read-ftp', { columns: [] });
    await input('文件路径', 'incoming/rows.csv');
    await click('+ 新增');
    await input('属性字段编辑名称', 'id');
    await input('属性字段编辑索引', '0');
    await click('保存属性');
    expect(editor.value!.validate()).toBe(true);
    expect(host.querySelector('.qi-connector-advanced')).toBeNull();
    expect(state.config.columns).toMatchObject([{ name: 'id', type: 'STRING', index: 0 }]);
    expect(state.config.resourceBindingId).toBeUndefined();
    expect(state.config.credentialRef).toBeUndefined();
    expect(validateConnectorConfig('read-ftp', state.config).map((issue) => issue.field)).toEqual(['resourceBindingId', 'credentialRef']);
  });

  it('ftp缺失高级必填项不阻止保存，非法历史值与明文凭据仍被拒绝', async () => {
    const state = mount('read-ftp', { path: 'incoming/rows.csv', columns: [{ name: 'id', type: 'LONG', index: 0 }] });
    expect(editor.value!.validate()).toBe(true);
    for (const [key, value] of [
      ['resourceBindingId', '_invalid'],
      ['credentialRef', 'Bearer plaintext'],
      ['maxRows', 0],
      ['ftpConfig', '{"nested":{"password":"plaintext"}}'],
    ] as const) {
      const original = state.config[key];
      state.config[key] = value;
      expect(editor.value!.validate()).toBe(false);
      state.config[key] = original;
    }
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.resourceBindingId).toBeUndefined();
    expect(state.config.credentialRef).toBeUndefined();
    state.config.credentialRef = null;
    state.config.resourceBindingId = '';
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.credentialRef).toBeNull();
    expect(state.config.resourceBindingId).toBe('');
    await nextTick();
  });

  it.each([['read-hdfs', 0], ['write-hdfs', 0], ['read-rocketmq', 1], ['write-rocketmq', 1]] as const)(
    '%s 主配置可保存，保留严格执行校验与已存在的高级参数', async (toolCode, groupIssues) => {
      const state = mount(toolCode, { databaseId: '1', path: 'incoming/rows.csv', topic: 'events', columns: [{ name: 'id', type: 'LONG', index: 0 }], vendor: 42 });
      expect(editor.value!.validate()).toBe(true);
      expect(state.config.vendor).toBe(42);
      expect(state.config.groupId).toBeUndefined();
      expect(validateConnectorConfig(toolCode, state.config).filter((issue) => issue.field === 'groupId')).toHaveLength(groupIssues);
      await nextTick();
    },
  );

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

  it('文件输出主属性改名保留来源并同步显式映射', async () => {
    const state = mount('write-ftp', {
      resourceBindingId: 'ftp.fixture', path: 'out',
      columns: [{ name: 'name', key: 'source_name', type: 'STRING', custom: 42 }],
    });
    await click('修改第1行属性字段');
    await input('属性字段编辑名称', 'label');
    await click('保存属性');
    expect(state.config.columns[0]).toMatchObject({ name: 'label', key: 'source_name', custom: 42 });
    expect(state.config.fieldMappings).toEqual([{ source: 'source_name', target: 'label', enabled: true }]);
    expect(state.config.fieldMappingColumns[0]).toMatchObject({ name: 'label', key: 'source_name', custom: 42 });
  });

  it.each(['write-odps', 'write-hive', 'write-hdfs', 'write-mongodb', 'write-redis', 'write-rabbitmq', 'write-rocketmq', 'write-ftp'])(
    '%s 删除高级面板后保留主要映射、输出配置和历史参数', async (toolCode) => {
      const columns = [{ name: 'id', type: 'LONG', key: 'source_id', custom: 7 }];
      const mappings = [{ source: 'source_id', target: 'id', enabled: true, vendor: 9 }];
      const state = mount(toolCode, { databaseId: '9007199254740993', resourceBindingId: 'resource.fixture', credentialRef: 'credential.fixture', maxRows: 1000, columns, fieldMappings: mappings, custom: 42 });
      await nextTick();
      expect(host.querySelector('.qi-connector-advanced')).toBeNull();
      expect(host.textContent).not.toContain('高级字段配置');
      expect(host.textContent).not.toContain('执行配置与高级参数');
      expect(host.textContent).toContain('输出配置');
      expect(host.querySelector('.qi-reference-output-selection, .qi-output-mapping')).toBeTruthy();
      expect(state.config).toMatchObject({ databaseId: '9007199254740993', resourceBindingId: 'resource.fixture', credentialRef: 'credential.fixture', maxRows: 1000, columns, fieldMappings: mappings, custom: 42 });
    },
  );

  it('ODPS输出对齐参考：连接类型与实例回显、选择表在实例之后、追加在前，空映射不显示操作按钮和暂无记录', async () => {
    const state = mount('write-odps', { columns: [] }, [{ name: 'id', dataType: 'LONG' }]);
    await nextTick();
    const labels = [...host.querySelectorAll('label')].map((item) => item.textContent?.replace('*', '').trim());
    expect(labels.indexOf('数据连接类型')).toBeLessThan(labels.indexOf('数据连接实例'));
    expect(labels.indexOf('数据连接实例')).toBeLessThan(labels.indexOf('选择表'));
    expect(labels.indexOf('选择表')).toBeLessThan(labels.indexOf('写入模式'));
    const typeInput = [...host.querySelectorAll('label')].find((item) => item.textContent === '数据连接类型')!.nextElementSibling as HTMLInputElement;
    expect(typeInput.value).toBe('ODPS');
    const instance = [...host.querySelectorAll('label')].find((item) => item.textContent === '数据连接实例')!.nextElementSibling as HTMLInputElement;
    expect(instance.getAttribute('placeholder')).toBe('自动回显项目名');
    expect(host.querySelector('[aria-label="写入模式"]')!.getAttribute('data-options')).toBe(
      JSON.stringify([{ label: '追加', value: 2 }, { label: '全量', value: 1 }]),
    );
    expect(host.querySelector('.qi-connector-addon')!.textContent).toBe('条');
    expect(host.textContent).toContain('不输入默认值1024条');
    expect(host.textContent).toContain('数据写入之后执行的SQL');
    expect(host.textContent).not.toContain('数据写入之前执行的SQL');
    expect(host.textContent).not.toContain('解析');
    expect(host.textContent).not.toContain('同名自动映射');
    expect(host.textContent).not.toContain('全选');
    expect(host.querySelector('.qi-output-mapping-empty')).toBeNull();
    expect(state.config.columns).toEqual([]);
  });

  it('Hive输出对齐参考：选择表在连接实例之后、where条件为常规标签、写入模式不预选，暂不支持的参数填写后才提示', async () => {
    const state = mount('write-hive', { columns: [] }, [{ name: 'id', dataType: 'LONG' }]);
    await nextTick();
    const labels = [...host.querySelectorAll('label')].map((item) => item.textContent?.replace('*', '').trim());
    for (const [before, after] of [['数据连接类型', '数据连接实例'], ['数据连接实例', '选择表'], ['选择表', 'where条件'], ['前置SQL', '写入模式'], ['写入模式', '单次写入数据'], ['单次写入数据', '后置SQL']] as const)
      expect(labels.indexOf(before), `${before} 应在 ${after} 之前`).toBeLessThan(labels.indexOf(after));
    expect(state.config.writeModeType).toBeUndefined();
    expect(host.querySelector('[aria-label="写入模式"]')!.getAttribute('data-options')).toBe(
      JSON.stringify([{ label: '追加', value: 2, disabled: false }, { label: '全量', value: 1, disabled: true }]),
    );
    expect(host.textContent).not.toContain('暂不支持');
    expect(host.textContent).toContain('不输入默认值1000条');
    expect(host.textContent).toContain('数据同步完成后执行的SQL');
    expect(host.textContent).not.toContain('解析');
    expect(host.textContent).not.toContain('同名自动映射');
    expect(host.querySelector('.qi-connector-addon')!.textContent).toBe('条');
  });

  it('HDFS输出对齐参考：分类连接选择器、编码与文件类型必填、压缩方式无默认且可清空、配置参数留空，字段对话框只有名称和类型', async () => {
    const state = mount('write-hdfs', { ...connectorDefaults('write-hdfs'), columns: [] });
    await nextTick();
    expect(host.querySelector('[aria-label="连接器数据连接"]')!.hasAttribute('data-databases')).toBe(true);
    expect(state.config.compression).toBeUndefined();
    expect(state.config.hadoopConfig).toBeUndefined();
    const labels = [...host.querySelectorAll('label')].map((item) => item.textContent?.trim());
    for (const required of ['编码', '文件路径', '文件类型', '分隔符', '写入模式'])
      expect(labels).toContain(`*${required}`);
    expect(labels).toContain('压缩方式');
    expect(host.querySelector('[aria-label="压缩方式"]')!.getAttribute('data-options')).toBe(
      JSON.stringify([{ label: 'gzip', value: 'gzip' }, { label: 'bzip2', value: 'bzip2' }, { label: 'lzo', value: 'lzo' }, { label: 'snappy', value: 'snappy' }]),
    );
    expect(host.querySelectorAll('.is-half-row')).toHaveLength(2);
    await click('新增');
    expect(host.querySelector('[aria-label="属性字段编辑名称"]')).toBeTruthy();
    expect(host.querySelector('[aria-label="属性字段编辑类型"]')).toBeTruthy();
    expect(host.querySelector('[aria-label="属性字段编辑取值"]')).toBeNull();
    expect(host.querySelector('[aria-label="属性字段编辑索引"]')).toBeNull();
  });

  it('MongoDB输出对齐参考：限定类型的连接选择器、选择表在实例之后、写入模式与单次条数不预设', async () => {
    const state = mount('write-mongodb', { ...connectorDefaults('write-mongodb'), columns: [] });
    await nextTick();
    const picker = host.querySelector('[aria-label="连接器数据连接"]')!;
    expect(picker.getAttribute('data-type-codes')).toBe(JSON.stringify(['MONGODB']));
    const labels = [...host.querySelectorAll('label')].map((item) => item.textContent?.replace('*', '').trim());
    for (const [before, after] of [['数据连接类型', '数据连接实例'], ['数据连接实例', '选择表'], ['选择表', 'where条件'], ['写入模式', '单次写入数据']] as const)
      expect(labels.indexOf(before), `${before} 应在 ${after} 之前`).toBeLessThan(labels.indexOf(after));
    expect(state.config.writeModeType).toBeUndefined();
    expect(state.config.batchSize).toBeUndefined();
    expect(host.querySelector('.qi-connector-addon')!.textContent).toBe('条');
    expect(host.textContent).toContain('不输入默认值1000条');
    expect(host.textContent).not.toContain('前置SQL');
    expect(host.textContent).not.toContain('解析');
  });

  it.each(['write-redis', 'write-rabbitmq', 'write-rocketmq'])(
    '%s 主要输出字段选择继续保存来源和已有附加属性', async (toolCode) => {
      const state = mount(toolCode, { columns: [{ name: 'label', key: 'name', type: 'STRING', custom: 42 }] }, [
        { name: 'name', dataType: 'STRING' }, { name: 'id', dataType: 'LONG' },
      ]);
      const select = host.querySelector<HTMLInputElement>('[aria-label="选择属性字段id"]')!;
      expect(select).toBeTruthy();
      select.checked = true;
      select.dispatchEvent(new Event('change'));
      await nextTick();
      expect(state.config.columns).toMatchObject([{ name: 'label', key: 'name', custom: 42 }, { name: 'id', key: 'id', type: 'LONG' }]);
      expect(state.config.fieldMappings).toMatchObject([{ source: 'name', target: 'label', enabled: true }, { source: 'id', target: 'id', enabled: true }]);
    },
  );

  it('hdfs保留主配置与属性编辑，不再显示两块高级配置或初始化其专用参数', async () => {
    const original = { name: 'id', type: 'LONG', index: 0 };
    const state = mount('read-hdfs', { columns: [original] });
    await nextTick();
    expect(host.textContent).not.toContain('高级字段配置');
    expect(host.textContent).not.toContain('执行配置与高级参数');
    expect(host.querySelector('.qi-connector-advanced')).toBeNull();
    expect(host.querySelector('[aria-label="属性字段"]')).toBeTruthy();
    expect(state.config.nullFormat).toBeUndefined();
    expect(state.config.hasHeader).toBeUndefined();
    expect(state.config.columns).toEqual([original]);
  });

  it.each(['read-ftp'])(
    '%s 主属性字段删除后仍读取原文件的物理列位置',
    async (toolCode) => {
      const state = mount(toolCode, {
        columns: [
          { name: 'id', type: 'LONG', index: 0 },
          { name: 'name', type: 'STRING', index: 5 },
        ],
      });
      await click('删除第1行字段');
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

  it.each(['read-ftp', 'read-redis', 'read-rabbitmq', 'read-rocketmq'])(
    '%s 删除附加面板后保留主字段编辑及历史配置',
    async (toolCode) => {
      const original = toolCode === 'read-redis'
        ? { name: 'id', type: 'LONG', key: '@key', custom: 7 }
        : { name: 'id', type: 'LONG', index: 0, custom: 7 };
      const state = mount(toolCode, {
        databaseId: '9007199254740993',
        resourceBindingId: 'resource.fixture',
        credentialRef: 'credential.fixture',
        maxRows: 1234,
        custom: { retained: true },
        columns: [original],
      });
      await nextTick();
      expect(host.querySelector('.qi-connector-advanced')).toBeNull();
      expect(host.textContent).not.toContain('高级字段配置');
      expect(host.textContent).not.toContain('执行配置与高级参数');
      expect(host.querySelector('table[aria-label="属性字段"]')).toBeTruthy();
      await click('修改第1行属性字段');
      await input('属性字段编辑名称', 'renamed');
      await click('保存属性');
      expect(state.config).toMatchObject({
        databaseId: '9007199254740993',
        resourceBindingId: 'resource.fixture',
        credentialRef: 'credential.fixture',
        maxRows: 1234,
        custom: { retained: true },
        columns: [{ ...original, name: 'renamed' }],
      });
    },
  );

  it('json错误和嵌套凭据输入后立即反馈，无需失焦或保存', async () => {
    const state = mount('read-rabbitmq', {
      databaseId: '1',
      topic: 'events',
      config: '{}',
      columns: [{ name: 'id', type: 'LONG' }],
    });
    // A valid 配置参数 stays quiet in the reference layout.
    expect(host.textContent).not.toContain('配置参数必须为有效的JSON对象');
    await input('配置参数', '{');
    expect(host.textContent).toContain('配置参数必须为有效的JSON对象');
    await input('配置参数', '{"nested":{"token":"placeholder"}}');
    expect(host.textContent).toContain('配置参数不可保存凭据');
    await input('配置参数', '{"queue":"events"}');
    expect(host.textContent).not.toContain('配置参数必须为有效的JSON对象');
    expect(host.textContent).not.toContain('配置参数不可保存凭据');
    expect(state.config.config).toBe('{"queue":"events"}');
    // Clearing it leaves the key unset; an empty string is not a JSON object for the compiler.
    await input('配置参数', '');
    expect(state.config.config).toBeUndefined();
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
      // File and message inputs follow the reference dialog: "+ 新增" and an explicit type.
      const fileInput = ['read-ftp', 'read-hdfs'].includes(toolCode);
      const add = '+ 新增';
      await click(add);
      expect(state.config.columns).toEqual([original]);
      await input('属性字段编辑名称', 'draft');
      await click('关闭属性');
      expect(state.config.columns).toEqual([original]);
      await click(add);
      await input('属性字段编辑名称', 'newField');
      // The reference dialog starts with an empty type that must be chosen.
      if (!fileInput) await input('属性字段编辑类型', 'STRING');
      if (fileInput) await input('属性字段编辑索引', '1');
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
    // The categorized picker is limited to Redis types and keeps the saved selection.
    expect(JSON.parse(select.dataset.typeCodes!)).toEqual(['REDIS']);
    expect(select.value).toBe('9007199254740993');
    vi.mocked(databaseApi.getDatabaseSimpleList).mockResolvedValueOnce([
      { id: '2', name: '缓存', productId: 'REDIS' },
      { id: '3', name: '消息', databaseTypeName: 'RabbitMQ' },
    ]);
    await click('重试');
    await nextTick();
    expect(host.textContent).not.toContain('数据连接加载失败');
    select = host.querySelector('[aria-label="连接器数据连接"]')!;
    // RabbitMQ connections are filtered out of the Redis picker.
    expect(JSON.parse(select.dataset.databases!)).toEqual(['2']);
    state.disabled = true;
    await nextTick();
    expect(select.disabled).toBe(true);
    expect(host.querySelector('.qi-connector-advanced')).toBeNull();
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
