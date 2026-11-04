import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { downloadFileFromBlobPart, downloadFileFromUrl } from '@vben/utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getIntegrationFileSchema } from '#/api/dataforge/integration';
import { uploadFileWithInfo } from '#/api/infra/file';

import ReferenceNodeEditor from './reference-node-editor.vue';

const harness = vi.hoisted(() => ({
  upload: undefined as ((file: File) => Promise<boolean>) | undefined,
  confirm: undefined as (() => void) | undefined,
  success: vi.fn(),
}));
vi.mock('@vben/utils', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/utils')>()),
  downloadFileFromBlobPart: vi.fn(),
  downloadFileFromUrl: vi.fn(async () => {}),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => [
    { id: '9007199254740993', name: '消息连接' },
  ]),
}));
vi.mock('#/api/dataforge/integration', () => ({
  getIntegrationFileSchema: vi.fn(async () => ({ fields: [] })),
}));
vi.mock('#/api/infra/file', () => ({
  uploadFileWithInfo: vi.fn(async () => ({
    id: '9007199254740995',
    name: 'source.jsonl',
    url: '/unused',
  })),
}));
vi.mock('antdv-next', () => {
  const Box = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.popupRender?.()]),
  });
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
    AutoComplete: Input,
    Select: Input,
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    MenuItem: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    Menu: Box,
    Form: Box,
    FormItem: defineComponent({
      props: { label: String, help: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.label, slots.default?.(), props.help]),
    }),
    message: { success: harness.success },
    Modal: Object.assign(
      defineComponent({
        props: { open: Boolean },
        emits: ['ok', 'cancel'],
        setup:
          (props, { emit, slots }) =>
          () =>
            props.open
              ? h('div', { class: 'field-editor' }, [
                  slots.default?.(),
                  h('button', { onClick: () => emit('ok') }, '保存属性字段'),
                  h(
                    'button',
                    { onClick: () => emit('cancel') },
                    '关闭属性字段',
                  ),
                ])
              : null,
      }),
      {
        confirm: (options: { onOk: () => void }) => {
          harness.confirm = options.onOk;
        },
      },
    ),
    Dropdown: Box,
    Popover: Box,
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    Upload: defineComponent({
      props: { beforeUpload: Function },
      setup: (props, { slots }) => {
        harness.upload = props.beforeUpload as (file: File) => Promise<boolean>;
        return () => h('div', slots.default?.());
      },
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  harness.confirm = undefined;
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(
  code:
    | 'column-map'
    | 'read-csv'
    | 'read-excel'
    | 'read-json'
    | 'read-kafka'
    | 'write-kafka',
  config: Record<string, any>,
  inputFields = [{ name: 'ID', dataType: 'BIGINT' }],
  disabled = false,
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({
    render: () =>
      h(ReferenceNodeEditor, {
        ref: editor,
        toolCode: code,
        config: state.config,
        disabled: state.disabled,
        inputFields,
      }),
  });
  app.mount(host);
  return { state, editor };
}
async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) =>
      item.textContent?.trim() === text ||
      item.getAttribute('aria-label') === text,
  )!;
  button.click();
  await flush();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('qData补齐配置的真实交互', () => {
  it('kafka新增字段要求类型与JSON解析值，保存原始大写类型，旧路径别名同步且取消不回写', async () => {
    const { state, editor } = mount('read-kafka', {
      databaseId: '1',
      topic: 'levels',
    });
    await flush();
    // Kafka input follows the reference "+ 新增" button.
    await click('+ 新增');
    const name = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑名称"]',
    )!;
    name.value = 'value';
    name.dispatchEvent(new Event('input'));
    await flush();
    const type = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑类型"]',
    )!;
    expect(type.value).toBe('');
    type.value = 'BOOL';
    type.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(host.textContent).toContain('JSON解析值不能为空');
    expect(state.config.columns).toBeUndefined();
    const key = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑取值"]',
    )!;
    key.value = 'info.aga';
    key.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns).toEqual([
      { name: 'value', type: 'BOOL', key: 'info.aga' },
    ]);
    expect(editor.value!.validate()).toBe(true);
    state.config.columns = [
      { name: 'LEGACY', type: 'Integer', jsonPath: '$.old' },
    ];
    await flush();
    await click('修改');
    const legacy = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑取值"]',
    )!;
    expect(legacy.value).toBe('$.old');
    legacy.value = '$.new';
    legacy.dispatchEvent(new Event('input'));
    await flush();
    await click('关闭属性字段');
    expect(state.config.columns).toEqual([
      { name: 'LEGACY', type: 'Integer', jsonPath: '$.old' },
    ]);
    await click('修改');
    const current = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑取值"]',
    )!;
    current.value = '$.new';
    current.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns).toEqual([
      { name: 'LEGACY', type: 'Integer', jsonPath: '$.new', key: '$.new' },
    ]);
  });

  it('jsonl主区保持只读七列表与三条格式说明，不能上传其他后缀冒充JSONL', async () => {
    mount('read-json', {});
    const panel = host.querySelector('.qi-reference-jsonl-upload')!;
    expect(panel.querySelectorAll('.qi-reference-file-tip')).toHaveLength(3);
    expect(host.querySelector('.qi-reference-json-guide')).not.toBeNull();
    expect(host.querySelectorAll('.qi-reference-json-rules p')).toHaveLength(3);
    expect(
      [...host.querySelectorAll('[aria-label="JSONL属性字段"] th')]
        .slice(0, 5)
        .map((cell) => cell.textContent),
    ).toEqual(['序号', '字段名称', '中文名称', 'JSON路径', '字段类型']);
    expect(
      host.querySelector('[aria-label="JSONL属性字段"] td button'),
    ).toBeNull();
    await harness.upload!(new File(['{}'], 'data.ndjson'));
    expect(uploadFileWithInfo).not.toHaveBeenCalled();
    expect(host.textContent).toContain('请选择 jsonl 文件');
    expect(host.textContent).not.toContain('高级字段配置');
    expect(host.querySelector('.qi-reference-fields-table')).toBeNull();
  });

  it('文件字段新增默认string，日期格式联动必填，旧显式类型和元信息原样保存', async () => {
    const { state } = mount('read-excel', { startRow: 1, startColumn: 1 });
    await click('+ 新增');
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="属性字段编辑类型"]')!
        .value,
    ).toBe('string');
    expect(host.querySelector('[aria-label="属性字段编辑格式"]')).toBeNull();
    const name = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑名称"]',
    )!;
    name.value = 'created_at';
    name.dispatchEvent(new Event('input'));
    await flush();
    const type = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑类型"]',
    )!;
    type.value = 'date';
    type.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(host.textContent).toContain('日期格式不能为空');
    expect(state.config.columns).toBeUndefined();
    const format = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑格式"]',
    )!;
    format.value = 'yyyy-MM-dd';
    format.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns).toEqual([
      { name: 'created_at', type: 'date', format: 'yyyy-MM-dd' },
    ]);
    expect(
      [...host.querySelectorAll('tbody button')].map((button) =>
        button.textContent?.trim(),
      ),
    ).toEqual(['修改']);
    state.config = {
      columns: [
        {
          name: 'LEGACY',
          type: 'Integer',
          format: 'keep',
          length: '9007199254740997',
        },
      ],
    };
    await flush();
    await click('修改');
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="属性字段编辑类型"]')!
        .value,
    ).toBe('Integer');
    expect(host.querySelector('[aria-label="属性字段编辑格式"]')).toBeNull();
    await click('保存属性字段');
    expect(state.config.columns).toEqual([
      {
        name: 'LEGACY',
        type: 'Integer',
        format: 'keep',
        length: '9007199254740997',
      },
    ]);
  });

  it('附件回显真实文件名与本次日期，下载使用已选文件或登记URL，未知历史地址不假成功', async () => {
    const { state } = mount('read-csv', {});
    const file = new File(['id\n1'], 'source.csv');
    vi.mocked(uploadFileWithInfo).mockResolvedValueOnce({
      id: '9007199254740997',
      name: 'registered.csv',
      url: 'https://storage.example/registered.csv',
    });
    await harness.upload!(file);
    await flush();
    expect(state.config.fileUrl).toBe('https://storage.example/registered.csv');
    expect(host.querySelector('time')?.textContent).toMatch(
      /^\d{4}-\d{2}-\d{2}$/u,
    );
    await click('下载附件');
    expect(downloadFileFromBlobPart).toHaveBeenCalledWith({
      fileName: 'registered.csv',
      source: file,
    });
    state.config = JSON.parse(JSON.stringify(state.config));
    await flush();
    expect(host.querySelector('time')?.textContent).toBe('-');
    await click('下载附件');
    expect(downloadFileFromUrl).toHaveBeenCalledWith({
      fileName: 'registered.csv',
      source: 'https://storage.example/registered.csv',
    });
    state.config.fileUrl = 'javascript:alert(1)';
    await flush();
    expect(
      host.querySelector<HTMLButtonElement>('[aria-label="下载附件"]')!
        .disabled,
    ).toBe(true);
    state.config.fileUrl = undefined;
    await flush();
    expect(
      host.querySelector<HTMLButtonElement>('[aria-label="下载附件"]')!
        .disabled,
    ).toBe(true);
  });

  it('新Excel起始行留空必填、起始列为1，显式保存的行列仍按原值回显', async () => {
    vi.mocked(getIntegrationFileSchema).mockResolvedValueOnce({
      fields: [{ name: 'ID', dataType: 'INTEGER' }],
    });
    const { state, editor } = mount('read-excel', { fileId: '11' });
    expect(state.config.startRow).toBeUndefined();
    expect(state.config.startColumn).toBe(1);
    const parse = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === '解析Excel',
    )!;
    expect(parse.disabled).toBe(true);
    expect(editor.value!.validate()).toBe(false);
    state.config.startRow = 1;
    await flush();
    await click('解析Excel');
    expect(getIntegrationFileSchema).toHaveBeenCalledWith({
      fileId: '11',
      format: 'EXCEL',
      startRow: 1,
      startColumn: 1,
    });
    expect(editor.value!.validate()).toBe(true);
    expect(harness.success).toHaveBeenCalledWith(
      'Excel解析成功，请确认属性字段类型！',
    );
    state.config = JSON.parse(
      JSON.stringify({
        ...state.config,
        startRow: 5,
        startColumn: 3,
        schemaFileKey: '11/EXCEL/5/3',
      }),
    );
    await flush();
    expect(state.config.startRow).toBe(5);
    expect(state.config.startColumn).toBe(3);
    expect(editor.value!.validate()).toBe(true);
  });

  it('移除附件使解析和迟到上传失效，不能重新关联已移除的文件', async () => {
    const uploaded = deferred<Awaited<ReturnType<typeof uploadFileWithInfo>>>();
    vi.mocked(uploadFileWithInfo).mockReturnValueOnce(uploaded.promise);
    const { state, editor } = mount('read-csv', {
      fileId: '11',
      fileName: 'old.csv',
      fileFormat: 'CSV',
      schemaFileKey: '11/CSV/1/1',
      columns: [{ name: 'OLD', type: 'STRING' }],
    });
    const pendingUpload = harness.upload!(new File(['id\n1'], 'new.csv'));
    await click('移除');
    expect(state.config.columns).toEqual([]);
    uploaded.resolve({ id: '12', name: 'new.csv', url: '/unused' });
    await pendingUpload;
    await flush();
    expect(state.config.fileId).toBeUndefined();
    expect(state.config.schemaFileKey).toBeUndefined();
    expect(editor.value!.validate()).toBe(false);
  });

  it('jsonl格式说明在主区可见，长度排序精确保留字段顺序与零精度', async () => {
    const { state } = mount('read-json', {
      fileId: '11',
      fileFormat: 'JSONL',
      columns: [
        {
          name: 'BIG',
          type: 'STRING',
          length: '9007199254740993',
          precision: 0,
        },
        { name: 'SMALL', type: 'STRING', length: '9007199254740992' },
        { name: 'UNKNOWN', type: 'STRING' },
      ],
    });
    expect(
      host.querySelector('.qi-reference-json-format')?.closest('details'),
    ).toBeNull();
    host
      .querySelector<HTMLButtonElement>('[aria-label="按字段长度排序"]')!
      .click();
    await flush();
    const cells = [
      ...host.querySelectorAll('[aria-label="JSONL属性字段"] tbody tr'),
    ];
    expect(cells.map((row) => row.children[1]?.textContent)).toEqual([
      'SMALL',
      'BIG',
      'UNKNOWN',
    ]);
    expect(cells[1]?.children[6]?.textContent).toBe('0');
    expect(
      state.config.columns.map((field: { name: string }) => field.name),
    ).toEqual(['BIG', 'SMALL', 'UNKNOWN']);
    host
      .querySelector<HTMLButtonElement>('[aria-label="按字段长度排序"]')!
      .click();
    await flush();
    expect(
      [...host.querySelectorAll('[aria-label="JSONL属性字段"] tbody tr')].map(
        (row) => row.children[1]?.textContent,
      ),
    ).toEqual(['BIG', 'SMALL', 'UNKNOWN']);
  });

  it('附件只接受非空且不超过50MiB的文件，替换后必须重新解析，未登记上传不覆盖原附件', async () => {
    const { state, editor } = mount('read-excel', {
      fileId: '11',
      fileName: 'old.xlsx',
      fileFormat: 'EXCEL',
      startRow: 1,
      startColumn: 1,
      schemaFileKey: '11/EXCEL/1/1',
      columns: [{ name: 'OLD', type: 'STRING' }],
    });
    await harness.upload!(new File([], 'empty.xlsx'));
    expect(uploadFileWithInfo).not.toHaveBeenCalled();
    const oversized = new File(['data'], 'big.xlsx');
    Object.defineProperty(oversized, 'size', { value: 50 * 1024 * 1024 + 1 });
    await harness.upload!(oversized);
    expect(uploadFileWithInfo).not.toHaveBeenCalled();
    expect(state.config.fileId).toBe('11');
    vi.mocked(uploadFileWithInfo).mockResolvedValueOnce({
      name: 'missing.xlsx',
      url: '/unused',
    } as Awaited<ReturnType<typeof uploadFileWithInfo>>);
    await harness.upload!(new File(['data'], 'missing.xlsx'));
    expect(state.config.fileId).toBe('11');
    expect(host.textContent).toContain('上传未返回已登记的文件编号');
    const maximum = new File(['data'], 'allowed.XLSX');
    Object.defineProperty(maximum, 'size', { value: 50 * 1024 * 1024 });
    vi.mocked(uploadFileWithInfo).mockResolvedValueOnce({
      id: '9007199254740997',
      name: 'allowed.XLSX',
      url: '/unused',
    });
    await harness.upload!(maximum);
    await flush();
    expect(state.config.fileId).toBe('9007199254740997');
    expect(state.config.columns).toEqual([]);
    expect(state.config.schemaFileKey).toBeUndefined();
    expect(editor.value!.validate()).toBe(false);
  });

  it('移除JSONL附件后迟到的解析不能恢复字段或已解析标记', async () => {
    const result =
      deferred<Awaited<ReturnType<typeof getIntegrationFileSchema>>>();
    vi.mocked(getIntegrationFileSchema).mockReturnValueOnce(result.promise);
    const { state } = mount('read-json', {
      fileId: '11',
      fileFormat: 'JSONL',
      columns: [{ name: 'OLD', type: 'STRING' }],
    });
    await click('解析JSONL');
    await click('移除');
    result.resolve({ fields: [{ name: 'LATE', dataType: 'STRING' }] });
    await flush();
    expect(state.config.columns).toEqual([]);
    expect(state.config.schemaFileKey).toBeUndefined();
    expect(state.config.fileId).toBeUndefined();
  });

  it('kafka只接收Kafka数据源并保留未知ID回显，属性表顺序和JSON取值保存一致', async () => {
    vi.mocked(getDatabaseSimpleList).mockResolvedValueOnce([
      { id: '11', name: '业务库', productId: 'MYSQL' },
      { id: '9007199254740997', name: '消息源', productId: 'KAFKA' },
    ]);
    const { state, editor } = mount('read-kafka', {
      databaseId: '11',
      topic: 'levels',
      configParameters: '{}',
      columns: [{ name: 'VALUE', type: 'String', key: 'payload.level' }],
    });
    await flush();
    expect(editor.value!.validate()).toBe(false);
    expect(host.textContent).toContain(
      '当前数据源并非Kafka类型，请重新选择Kafka连接。',
    );
    state.config.databaseId = '9007199254740997';
    await flush();
    expect(editor.value!.validate()).toBe(true);
    const titles = [
      ...host.querySelectorAll('.qi-reference-fields-table th'),
    ].map((cell) => cell.textContent);
    expect(titles).toEqual([
      '序号',
      '字段名称',
      '字段类型',
      'JSON解析取值',
      '操作',
    ]);
    await click('修改');
    const input = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑取值"]',
    )!;
    input.value = 'payload.measured';
    input.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns[0].key).toBe('payload.measured');
    expect(state.config.databaseId).toBe('9007199254740997');
    state.config.databaseId = 'unavailable';
    await flush();
    expect(state.config.databaseId).toBe('unavailable');
  });

  it.each([
    ['read-csv', 'CSV'],
    ['read-excel', 'EXCEL'],
    ['read-json', 'JSONL'],
  ] as const)(
    '%s只保留上传解析和字段配置，已解析附件仍能校验保存',
    async (toolCode, format) => {
      const { state, editor } = mount(toolCode, {
        fileId: '1',
        fileName: 'sample',
        fileFormat: format,
        startRow: 2,
        startColumn: 1,
        schemaFileKey: `1/${format}/2/1`,
        columns: [{ name: 'id', type: 'Integer' }],
      });
      await flush();
      expect(host.textContent).toContain('选择文件');
      expect(host.textContent).toContain('属性字段');
      expect(host.textContent).not.toContain('文件用途');
      expect(host.textContent).not.toContain('执行资源');
      expect(host.querySelector('[aria-label="相对文件路径"]')).toBeNull();
      expect(editor.value!.validate()).toBe(true);
      expect(state.config.fileId).toBe('1');
      expect(state.config.columns).toEqual([{ name: 'id', type: 'Integer' }]);
    },
  );

  it('历史JSON数组配置不自动切换JSONL，未知属性完整保留', async () => {
    const { state, editor } = mount('read-json', {
      fileUrl: 'https://history.invalid/array.json',
      vendor: { keep: true },
    });
    expect(state.config.fileFormat).toBeUndefined();
    expect(host.textContent).toContain('JSON数组与JSONL语义不同');
    expect(editor.value?.validate()).toBe(false);
    await click('改用上传文件读取');
    expect(state.config.fileFormat).toBe('JSONL');
    expect(state.config.fileUrl).toBe('https://history.invalid/array.json');
    expect(state.config.vendor).toEqual({ keep: true });
  });

  it('kafka仅引用平台连接，旧凭据必须显式清除；嵌套秘密参数不能保存', async () => {
    const { state, editor } = mount('read-kafka', {
      bootstrapServers: 'legacy',
      saslPassword: 'redacted',
      databaseId: '9007199254740993',
      topic: 'level',
      columns: [{ name: 'ID', type: 'LONG' }],
      configParameters: '{}',
    });
    await flush();
    expect(getDatabaseSimpleList).toHaveBeenCalledOnce();
    expect(host.querySelector('input[type="password"]')).toBeNull();
    expect(editor.value?.validate()).toBe(false);
    await click('清除旧连接参数');
    expect(state.config.saslPassword).toBeUndefined();
    expect(state.config.databaseId).toBe('9007199254740993');
    expect(editor.value?.validate()).toBe(true);
    state.config.configParameters = '{"nested":{"password":"redacted"}}';
    await flush();
    expect(host.textContent).toContain('认证参数请在平台数据连接中配置');
    expect(editor.value?.validate()).toBe(false);
  });

  it('文件解析携带无损ID及一基行列，未知类型保持未知并要求用户确认', async () => {
    vi.mocked(getIntegrationFileSchema).mockResolvedValueOnce({
      fields: [
        { name: 'VALUE', dataType: null, length: '9007199254740993', scale: 0 },
      ],
      warnings: ['类型不一致'],
    });
    const { state, editor } = mount('read-excel', {
      fileId: '9007199254740997',
      fileFormat: 'EXCEL',
      startRow: 2,
      startColumn: 3,
    });
    await click(
      host.querySelector('[data-tool-code="read-excel"]')
        ? '解析Excel'
        : '解析JSONL',
    );
    expect(getIntegrationFileSchema).toHaveBeenCalledExactlyOnceWith({
      fileId: '9007199254740997',
      format: 'EXCEL',
      startRow: 2,
      startColumn: 3,
    });
    expect(state.config.columns).toEqual([
      {
        name: 'VALUE',
        type: undefined,
        length: '9007199254740993',
        precision: 0,
      },
    ]);
    expect(editor.value?.validate()).toBe(false);
    state.config.columns[0].type = 'String';
    expect(editor.value?.validate()).toBe(true);
    state.config.startColumn = 4;
    expect(editor.value?.validate()).toBe(false);
  });

  it('迟到的上传和解析结果不能写入另一节点草稿', async () => {
    const parsed =
      deferred<Awaited<ReturnType<typeof getIntegrationFileSchema>>>();
    vi.mocked(getIntegrationFileSchema).mockReturnValueOnce(parsed.promise);
    const { state } = mount('read-json', { fileId: '11', fileFormat: 'JSONL' });
    await click(
      host.querySelector('[data-tool-code="read-excel"]')
        ? '解析Excel'
        : '解析JSONL',
    );
    state.config = {
      fileId: '22',
      fileFormat: 'JSONL',
      columns: [{ name: 'NEW', type: 'String' }],
    };
    await flush();
    parsed.resolve({ fields: [{ name: 'OLD', dataType: 'STRING' }] });
    await flush();
    expect(state.config.columns[0].name).toBe('NEW');
    const uploaded = deferred<Awaited<ReturnType<typeof uploadFileWithInfo>>>();
    vi.mocked(uploadFileWithInfo).mockReturnValueOnce(uploaded.promise);
    const pending = harness.upload!(new File(['{}'], 'data.jsonl'));
    state.config = { fileId: '33', fileFormat: 'JSONL' };
    await flush();
    uploaded.resolve({ id: '44', name: 'data.jsonl', url: '/unused' });
    await pending;
    expect(state.config.fileId).toBe('33');
  });

  it('字段获取保留用户别名，新增模式不自动写类型转换，拖拽支持键盘排序', async () => {
    const { state } = mount('column-map', {
      columnMappingMode: 'FIELDS',
      fieldSelections: [{ name: 'OLD', alias: 'old', custom: true }],
      removedFields: [],
      vendor: 42,
    });
    await click('获取字段');
    await click('增加新的');
    expect(state.config.fieldSelections).toEqual([
      { name: 'OLD', alias: 'old', custom: true },
      { name: 'ID' },
    ]);
    host.querySelector('tbody tr')?.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
      }),
    );
    await flush();
    expect(
      state.config.fieldSelections.map((field: { name: string }) => field.name),
    ).toEqual(['ID', 'OLD']);
    expect(state.config.vendor).toBe(42);
  });

  it('仅保留Kafka输出主字段选择及写入字节，选择上游字段保留已有映射配置', async () => {
    const { state } = mount('write-kafka', {
      databaseId: '1',
      topic: 'topic',
      columns: [{ name: 'OLD', type: 'String', format: 'keep' }],
      fieldMappings: [{ source: 'OLD', target: 'OLD', custom: 'keep' }],
      batchSize: 2048,
      resourceBindingId: '9007199254740993',
    });
    await flush();
    expect(host.textContent).not.toContain('高级字段配置');
    expect(host.textContent).not.toContain('同名自动映射');
    expect(host.querySelector('.qi-reference-fields-table')).toBeNull();
    const select = host.querySelector<HTMLInputElement>(
      '[aria-label="选择属性字段ID"]',
    )!;
    select.checked = true;
    select.dispatchEvent(new Event('change'));
    await flush();
    expect(state.config.columns).toEqual([
      { name: 'OLD', type: 'String', format: 'keep' },
      { name: 'ID', key: 'ID', type: 'BIGINT', index: 1 },
    ]);
    expect(state.config.fieldMappings[0]).toEqual({
      source: 'OLD', target: 'OLD', custom: 'keep', enabled: true,
    });
    const bytes = host.querySelector<HTMLInputElement>(
      '.qi-reference-form-row input[value="2048"]',
    )!;
    bytes.value = '4096';
    bytes.dispatchEvent(new Event('input'));
    await flush();
    expect(Number(state.config.batchSize)).toBe(4096);
    expect(state.config.resourceBindingId).toBe('9007199254740993');
  });

  it('删除需要确认且旧确认不能影响新节点，保留至少一个属性字段', async () => {
    const { state } = mount('column-map', {
      columnMappingMode: 'FIELDS',
      fieldSelections: [{ name: 'A' }, { name: 'B' }],
    });
    await click('删除');
    expect(harness.confirm).toBeTypeOf('function');
    state.config = {
      columnMappingMode: 'FIELDS',
      fieldSelections: [{ name: 'NEW' }],
    };
    await flush();
    harness.confirm!();
    expect(state.config.fieldSelections).toEqual([{ name: 'NEW' }]);
    harness.confirm = undefined;
    await click('删除');
    expect(harness.confirm).toBeUndefined();
    expect(host.textContent).toContain('请至少保留一个属性字段');
  });

  it('只读不初始化配置、不上传附件，Excel属性字段不提供删除', async () => {
    const { state } = mount('read-excel', { vendor: 42 }, [], true);
    expect(state.config).toEqual({ vendor: 42 });
    await harness.upload!(new File(['example'], 'example.xlsx'));
    expect(uploadFileWithInfo).not.toHaveBeenCalled();
    state.disabled = false;
    state.config = {
      fileFormat: 'EXCEL',
      fileId: '1',
      columns: [
        { name: 'A', type: 'String' },
        { name: 'B', type: 'String' },
      ],
    };
    await flush();
    // The qData reference only offers "修改" per field; fields are regenerated by parsing.
    expect(
      [...host.querySelectorAll('button')].some(
        (button) => button.textContent?.trim() === '删除',
      ),
    ).toBe(false);
    expect(state.config.columns).toHaveLength(2);
  });

  it('jsonl只展示解析结果，不再提供重复的字段增删编辑区', async () => {
    const { state } = mount('read-json', {
      fileFormat: 'JSONL',
      fileId: '1',
      columns: [{ name: 'A', type: 'String' }, { name: 'B', type: 'String' }],
    });
    await flush();
    expect(host.querySelectorAll('[aria-label="JSONL属性字段"] tbody tr')).toHaveLength(2);
    expect(host.textContent).not.toContain('高级字段配置');
    expect(host.querySelector('.qi-reference-fields-table')).toBeNull();
    expect([...host.querySelectorAll('button')].some((button) => ['修改', '删除', '新增'].includes(button.textContent?.trim() ?? ''))).toBe(false);
    expect(state.config.columns).toEqual([{ name: 'A', type: 'String' }, { name: 'B', type: 'String' }]);
  });

  it('csv只接受受控上传，解析文本类型，属性弹窗隔离编辑、关闭不回写且旧上下文不能保存', async () => {
    vi.mocked(getIntegrationFileSchema).mockResolvedValueOnce({
      fields: [{ name: '日期', dataType: 'STRING' }],
      partial: true,
    });
    const { state, editor } = mount('read-csv', { vendor: 7 });
    expect(state.config.fileFormat).toBe('CSV');
    await harness.upload!(new File(['bad'], 'bad.json'));
    expect(uploadFileWithInfo).not.toHaveBeenCalled();
    vi.mocked(uploadFileWithInfo).mockResolvedValueOnce({
      id: '9007199254740995',
      name: 'sample.csv',
      url: '/unused',
    });
    await harness.upload!(new File(['日期\n2026-11-01'], 'sample.csv'));
    await flush();
    expect(state.config.fileId).toBe('9007199254740995');
    expect(state.config.url).toBeUndefined();
    expect(editor.value!.validate()).toBe(false);
    await click('解析csv');
    expect(getIntegrationFileSchema).toHaveBeenCalledWith({
      fileId: '9007199254740995',
      format: 'CSV',
      startRow: 1,
      startColumn: 1,
    });
    expect(state.config.columns[0]).toMatchObject({
      name: '日期',
      type: 'STRING',
    });
    expect(editor.value!.validate()).toBe(true);
    await click('修改');
    const input = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑名称"]',
    )!;
    input.value = 'CHANGED';
    input.dispatchEvent(new Event('input'));
    await flush();
    expect(state.config.columns[0].name).toBe('日期');
    await click('关闭属性字段');
    expect(state.config.columns[0].name).toBe('日期');
    await click('修改');
    const type = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑类型"]',
    )!;
    type.value = 'date';
    type.dispatchEvent(new Event('input'));
    await flush();
    const format = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑格式"]',
    )!;
    format.value = 'yyyy-MM-dd';
    format.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns[0].format).toBe('yyyy-MM-dd');
    expect(state.config.vendor).toBe(7);
    await click('修改');
    state.config = {
      fileId: 'new',
      fileFormat: 'CSV',
      columns: [{ name: 'NEW', type: 'STRING' }],
    };
    await flush();
    expect(host.querySelector('.field-editor')).toBeNull();
    expect(state.config.columns).toEqual([{ name: 'NEW', type: 'STRING' }]);
  });

  it('属性字段保存拦截重名、空类型和只读变化，不丢零精度或未知属性', async () => {
    const { state } = mount('read-excel', {
      columns: [
        { name: 'A', type: 'STRING', precision: 0, vendor: 42 },
        { name: 'B', type: 'STRING' },
      ],
    });
    await click('修改');
    const input = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑名称"]',
    )!;
    input.value = 'B';
    input.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns[0].name).toBe('A');
    expect(host.textContent).toContain('输出字段名称重复');
    input.value = 'C';
    input.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns[0]).toMatchObject({
      name: 'C',
      precision: 0,
      vendor: 42,
    });
    await click('修改');
    state.disabled = true;
    await flush();
    expect(host.querySelector('.field-editor')).toBeNull();
    expect(state.config.columns[0].name).toBe('C');
  });

  it('文件解析的两个未知类型字段可在独立弹窗逐个确认，节点最终校验仍要求全表类型', async () => {
    vi.mocked(getIntegrationFileSchema).mockResolvedValueOnce({
      fields: [
        { name: 'A', dataType: null },
        { name: 'B', dataType: null },
      ],
      partial: true,
      warnings: ['混合类型，需手动确认'],
    });
    const { state, editor } = mount('read-csv', {
      fileId: '1',
      fileFormat: 'CSV',
    });
    await click('解析csv');
    expect(editor.value!.validate()).toBe(false);
    await click('修改');
    const firstType = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑类型"]',
    )!;
    firstType.value = 'String';
    firstType.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(
      state.config.columns.map((field: { type?: string }) => field.type),
    ).toEqual(['String', undefined]);
    expect(host.querySelector('.field-editor')).toBeNull();
    expect(editor.value!.validate()).toBe(false);
    [
      ...host.querySelectorAll<HTMLButtonElement>(
        'tbody tr:nth-child(2) button',
      ),
    ]
      .find((button) => button.textContent?.trim() === '修改')!
      .click();
    await flush();
    const secondType = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑类型"]',
    )!;
    secondType.value = 'Integer';
    secondType.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(
      state.config.columns.map((field: { type?: string }) => field.type),
    ).toEqual(['String', 'Integer']);
    expect(host.querySelector('.field-editor')).toBeNull();
    expect(editor.value!.validate()).toBe(true);
  });

  it('旧Excel工作表名原样传递，切换工作表后的迟到解析不能覆盖当前字段或解析标记', async () => {
    const first =
      deferred<Awaited<ReturnType<typeof getIntegrationFileSchema>>>();
    const second =
      deferred<Awaited<ReturnType<typeof getIntegrationFileSchema>>>();
    vi.mocked(getIntegrationFileSchema)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { state, editor } = mount('read-excel', {
      fileId: '1',
      fileFormat: 'EXCEL',
      startRow: 1,
      sheetName: '原工作表',
      columns: [{ name: 'KEPT', type: 'STRING' }],
    });
    await click(
      host.querySelector('[data-tool-code="read-excel"]')
        ? '解析Excel'
        : '解析JSONL',
    );
    expect(getIntegrationFileSchema).toHaveBeenNthCalledWith(1, {
      fileId: '1',
      format: 'EXCEL',
      startRow: 1,
      startColumn: 1,
      sheetName: '原工作表',
    });
    const input = host.querySelector<HTMLInputElement>(
      '[aria-label="读取工作表"]',
    )!;
    input.value = ' 当前工作表 ';
    input.dispatchEvent(new Event('input'));
    await flush();
    await click(
      host.querySelector('[data-tool-code="read-excel"]')
        ? '解析Excel'
        : '解析JSONL',
    );
    expect(getIntegrationFileSchema).toHaveBeenNthCalledWith(2, {
      fileId: '1',
      format: 'EXCEL',
      startRow: 1,
      startColumn: 1,
      sheetName: ' 当前工作表 ',
    });
    second.resolve({
      fields: [{ name: 'CURRENT', dataType: 'STRING' }],
      partial: true,
    });
    await flush();
    const currentKey = state.config.schemaFileKey;
    expect(currentKey).toContain(' 当前工作表 ');
    expect(editor.value!.validate()).toBe(true);
    first.resolve({
      fields: [{ name: 'OLD', dataType: 'STRING' }],
      partial: true,
    });
    await flush();
    expect(state.config.columns[0].name).toBe('CURRENT');
    expect(state.config.schemaFileKey).toBe(currentKey);
    input.value = '原工作表';
    input.dispatchEvent(new Event('input'));
    await flush();
    expect(editor.value!.validate()).toBe(false);
  });

  it('kafka单次写入字节保留源batchSize契约，新字段解析值写key且不写密码', async () => {
    const { state } = mount('read-kafka', {
      databaseId: '1',
      topic: 'topic',
      columns: [{ name: 'A', type: 'STRING' }],
    });
    await flush();
    await click('修改');
    const input = host.querySelector<HTMLInputElement>(
      '[aria-label="属性字段编辑取值"]',
    )!;
    input.value = 'payload.level';
    input.dispatchEvent(new Event('input'));
    await flush();
    await click('保存属性字段');
    expect(state.config.columns[0].key).toBe('payload.level');
    expect(state.config.columns[0].jsonPath).toBeUndefined();
    app!.unmount();
    host.remove();
    const output = mount('write-kafka', {
      databaseId: '1',
      topic: 'topic',
      columns: [{ name: 'A', type: 'STRING' }],
    });
    expect(output.state.config.batchSize).toBe(10_000);
    expect(output.state.config.batchBytes).toBeUndefined();
  });
});
