/* eslint-disable vue/one-component-per-file -- 多个控件替身用于测试内嵌组件的真实交互 */
import type { App, ComponentPublicInstance, PropType } from 'vue';

import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { defaultApiConfig, validateApiConfig } from './api-config';
import Editor from './api-config-editor.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '1' } },
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('#/api/request', () => ({ requestClient: { post: vi.fn() } }));
vi.mock('antdv-next', () => {
  const textControl = (tag: 'input' | 'textarea') =>
    defineComponent({
      inheritAttrs: false,
      props: { value: { type: [String, Number], default: '' }, disabled: Boolean },
      emits: ['update:value'],
      setup(props, { attrs, emit }) {
        return () =>
          h(tag, {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          });
      },
    });
  const choiceControl = defineComponent({
    props: {
      value: { type: [String, Number], default: '' }, disabled: Boolean,
      options: {
        type: Array as PropType<{ label: string; value: number | string }[]>,
        default: () => [],
      },
    },
    emits: ['update:value'],
    setup(props, { emit }) {
      return () =>
        h(
          'select',
          {
            value: props.value,
            disabled: props.disabled,
            onChange: (event: Event) => {
              const value = (event.target as HTMLSelectElement).value;
              emit(
                'update:value',
                props.options.find(
                  (option: { value: number | string }) =>
                    String(option.value) === value,
                )?.value,
              );
            },
          },
          props.options.map(
            (option: { label: string; value: number | string }) =>
              h('option', { value: option.value }, option.label),
          ),
        );
    },
  });
  return {
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup(props, { attrs, slots }) {
        return () =>
          h(
            'button',
            {
              ...attrs,
              disabled: props.disabled,
              'data-loading': String(props.loading),
            },
            slots.default?.(),
          );
      },
    }),
    Input: textControl('input'),
    TextArea: textControl('textarea'),
    InputNumber: textControl('input'),
    RadioGroup: choiceControl,
    Select: choiceControl,
    Checkbox: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup(props, { emit }) {
        return () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit(
                'update:checked',
                (event.target as HTMLInputElement).checked,
              ),
          });
      },
    }),
    Table: defineComponent({
      props: {
        columns: { type: Array as PropType<{ key: string }[]>, default: () => [] },
        dataSource: { type: Array as PropType<DataforgeDataApiApi.ResponseColumnItem[]>, default: () => [] },
        rowKey: { type: Function as PropType<(column: DataforgeDataApiApi.ResponseColumnItem) => string>, required: true },
      },
      setup(props, { slots }) {
        return () =>
          h(
            'table',
            h(
              'tbody',
              props.dataSource.map(
                (
                  record: DataforgeDataApiApi.ResponseColumnItem,
                  index: number,
                ) =>
                  h(
                    'tr',
                    { key: props.rowKey(record) },
                    props.columns.map((column: { key: string }) =>
                      h('td', slots.bodyCell?.({ column, record, index })),
                    ),
                  ),
              ),
            ),
          );
      },
    }),
    message: { warning: vi.fn(), success: vi.fn() },
  };
});

const config = ref<DataforgeDataApiApi.DataApi>(defaultApiConfig());
const disabled = ref(false);
const columnsChanged = vi.fn();
let app: App | undefined;
let host: HTMLDivElement;
let editor: ComponentPublicInstance & { validate: () => boolean };

function deferred<T>() {
  let resolve!: (result: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
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

async function mount() {
  app = createApp(
    defineComponent(
      () => () =>
        h(Editor, {
          modelValue: config.value,
          'onUpdate:modelValue': (value: DataforgeDataApiApi.DataApi) => {
            config.value = value;
          },
          disabled: disabled.value,
          onColumnsChange: columnsChanged,
          ref: (instance) => {
            editor = instance as typeof editor;
          },
        }),
    ),
  );
  host = document.createElement('div');
  app.mount(host);
  await flush();
}

function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
  if (!result) throw new Error(`未找到按钮：${label}`);
  return result;
}

async function click(label: string) {
  button(label).click();
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '1' } });
  config.value = defaultApiConfig({
    requestUrl: 'https://api.example.com/data',
  });
  disabled.value = false;
  vi.mocked(requestClient.post).mockReset();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('内嵌接口配置与试调', () => {
  it('直接编辑地址、参数、JSON请求体，并通过平台试调接口发送完整配置', async () => {
    vi.mocked(requestClient.post).mockResolvedValue('{"id":9007199254740993}');
    config.value = defaultApiConfig({
      apiName: '独立节点',
      requestUrl: 'https://api.example.com/data',
      requestMethod: 'POST',
      bodyType: 'raw',
      bodyRaw: '{"limit":20}',
      dataKey: 'data.list',
      ifPage: 1,
      pageKey: 'page',
      pageSizeKey: 'size',
    });
    await mount();
    await click('添加参数');
    const parameterBlock = host.querySelector('.parameter-block');
    const fields = parameterBlock?.querySelectorAll('input');
    if (!fields?.[0] || !fields[1]) throw new Error('参数行没有渲染');
    fields[0].value = 'X-Sample';
    fields[0].dispatchEvent(new Event('input'));
    fields[1].value = 'value';
    fields[1].dispatchEvent(new Event('input'));
    await click('测试请求');
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/data-api/test',
      expect.objectContaining({
        apiName: '独立节点',
        requestMethod: 'POST',
        bodyRaw: '{"limit":20}',
        requestHeader: [{ name: 'X-Sample', value: 'value', description: '' }],
        dataKey: 'data.list',
        ifPage: 1,
        pageKey: 'page',
        pageSizeKey: 'size',
      }),
    );
    expect(host.querySelector('pre')?.textContent).toBe(
      '{"id":9007199254740993}',
    );
  });

  it('试调名称为空时满足现有VO名称校验，但不修改任务配置', async () => {
    vi.mocked(requestClient.post).mockResolvedValue('[]');
    await mount();
    await click('测试请求');
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/data-api/test',
      expect.objectContaining({ apiName: '接口试调' }),
    );
    expect(config.value.apiName).toBe('');
  });

  it('获取字段同步父模型并通知映射消费者，不创建独立API资源', async () => {
    const columns = [{ fieldName: 'id', fieldType: 12, nullable: true }];
    vi.mocked(requestClient.post).mockResolvedValue({
      columns,
      rows: [{ id: '9007199254740993' }],
    });
    await mount();
    await click('获取返回字段');
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/data-api/fetch-data',
      expect.objectContaining({ requestUrl: 'https://api.example.com/data' }),
    );
    expect(config.value.dataColumns).toEqual(columns);
    expect(columnsChanged).toHaveBeenCalledWith(columns);
    expect(editor.validate()).toBe(true);
    expect(host.textContent).toContain('本次解析到 1 条样例数据');
    expect(requestClient.post).toHaveBeenCalledTimes(1);
  });

  it('取字段请求使用独立快照，编辑后旧响应不覆盖当前模型', async () => {
    const pending = deferred<DataforgeDataApiApi.FetchResult>();
    vi.mocked(requestClient.post).mockReturnValue(pending.promise);
    config.value.requestHeader = [{ name: 'X-Sample', value: 'first' }];
    await mount();
    await click('获取返回字段');
    config.value.requestHeader[0]!.value = 'second';
    await flush();
    pending.resolve({ columns: [{ fieldName: 'stale' }], rows: [] });
    await flush();
    expect(config.value.dataColumns).toEqual([]);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/data-api/fetch-data',
      expect.objectContaining({
        requestHeader: [{ name: 'X-Sample', value: 'first' }],
      }),
    );
    expect(message.success).not.toHaveBeenCalled();
  });

  it('相同取数定义重新获取字段保留同名字段声明，新增和移除跟随响应', async () => {
    const inferred = { fieldName: 'id', fieldType: 12, displaySize: 100, precisionSize: 0, pk: false, nullable: true };
    vi.mocked(requestClient.post).mockResolvedValueOnce({ columns: [inferred, { fieldName: 'removed', fieldType: 12 }], rows: [{ id: 'sample' }] });
    await mount(); await click('获取返回字段');
    Object.assign(config.value.dataColumns![0]!, { fieldType: 4, displaySize: 32, precisionSize: 6, pk: true, nullable: false, description: '唯一标识' });
    const declarations = { ...config.value.dataColumns![0] };
    vi.mocked(requestClient.post).mockResolvedValueOnce({ columns: [inferred, { fieldName: 'added', fieldType: 12, pk: false, nullable: true }], rows: [{ id: 'sample', added: 'value' }] });
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([declarations, { fieldName: 'added', fieldType: 12, pk: false, nullable: true }]);
    expect(columnsChanged).toHaveBeenLastCalledWith(config.value.dataColumns);
    expect(config.value.dataColumns![0]).not.toBe(inferred);
    expect(inferred).toEqual({ fieldName: 'id', fieldType: 12, displaySize: 100, precisionSize: 0, pk: false, nullable: true });
    config.value.dataColumns![0]!.description = '后续编辑';
    expect(inferred).not.toHaveProperty('description');
    expect(host.textContent).not.toContain('已按新响应重新生成字段');
  });

  it('零值、false和空说明是有效声明，名称与参数说明变化不重置字段', async () => {
    const declared = { fieldName: 'id', fieldType: 8, displaySize: 18, precisionSize: 0, pk: false, nullable: false, description: '' };
    config.value.requestHeader = [{ name: 'X-Sample', value: 'value', description: '原说明' }];
    config.value.dataColumns = [declared];
    await mount();
    config.value.apiName = '新名称'; config.value.description = '新描述';
    config.value.requestHeader![0]!.description = '修改说明';
    config.value.bodyType = 'raw'; config.value.bodyRaw = '{"hidden":true}';
    vi.mocked(requestClient.post).mockResolvedValue({ columns: [{ fieldName: 'id', fieldType: 12, displaySize: 100, precisionSize: 3, pk: true, nullable: true, description: '推断说明' }], rows: [] });
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([declared]);
    expect(host.textContent).not.toContain('已按新响应重新生成字段');
  });

  it.each([
    { requestUrl: 'https://api.example.com/new' },
    { requestMethod: 'GET' },
    { requestHeader: [{ name: 'X-Sample', value: 'new' }] },
    { requestParam: [{ name: 'filter', value: 'new' }] },
    { bodyRaw: '{"filter":"new"}' },
    { bodyType: 'urlencoded', bodyUrlencoded: [{ name: 'filter', value: 'new' }] },
    { dataKey: 'result.items' },
    { dataType: 1 },
    { ifPage: 0 },
    { pageKey: 'page' },
    { pageSizeKey: 'size' },
  ])('取数定义改变后不继承旧字段类型和主键：%o', async (patch) => {
    config.value = defaultApiConfig({ requestUrl: 'https://api.example.com/data', requestMethod: 'POST', requestHeader: [{ name: 'X-Sample', value: 'old' }], requestParam: [{ name: 'filter', value: 'old' }], bodyType: 'raw', bodyRaw: '{"filter":"old"}', dataKey: 'data.list', dataType: 2, ifPage: 1, pageKey: 'pageNo', pageSizeKey: 'pageSize', dataColumns: [{ fieldName: 'id', fieldType: 4, displaySize: 32, precisionSize: 2, pk: true, nullable: false, description: '旧定义的主键' }] });
    await mount(); Object.assign(config.value, patch); await flush();
    const inferred = { fieldName: 'id', fieldType: 12, displaySize: 100, precisionSize: 0, pk: false, nullable: true };
    vi.mocked(requestClient.post).mockResolvedValue({ columns: [inferred], rows: [{ id: 'sample' }] });
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([inferred]);
    expect(host.textContent).toContain('已按新响应重新生成字段');
    expect(host.textContent).toContain('重新确认类型、主键和可空设置');
    Object.assign(config.value.dataColumns![0]!, { fieldType: 4, pk: true, nullable: false, description: '新定义的主键' });
    await click('获取返回字段');
    expect(config.value.dataColumns![0]).toMatchObject({ fieldType: 4, pk: true, nullable: false, description: '新定义的主键' });
    expect(host.textContent).not.toContain('已按新响应重新生成字段');
  });

  it('替换节点配置后同名字段沿用新节点声明，不继承前一个节点', async () => {
    config.value.dataColumns = [{ fieldName: 'id', fieldType: 4, pk: true, nullable: false }];
    await mount();
    config.value = defaultApiConfig({ requestUrl: 'https://api.example.com/second-node', dataColumns: [{ fieldName: 'id', fieldType: 8, pk: false, nullable: true }] });
    await flush();
    vi.mocked(requestClient.post).mockResolvedValue({ columns: [{ fieldName: 'id', fieldType: 12, pk: false, nullable: true }], rows: [] });
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([{ fieldName: 'id', fieldType: 8, pk: false, nullable: true }]);
  });

  it('过期取字段响应不改变新定义基准，最新请求结束前保持loading', async () => {
    const old = deferred<DataforgeDataApiApi.FetchResult>();
    const latest = deferred<DataforgeDataApiApi.FetchResult>();
    config.value.dataColumns = [{ fieldName: 'id', fieldType: 4, pk: true, nullable: false }];
    vi.mocked(requestClient.post).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    await mount(); await click('获取返回字段');
    config.value.requestUrl = 'https://api.example.com/new'; await flush();
    await click('获取返回字段');
    old.resolve({ columns: [{ fieldName: 'old' }], rows: [] }); await flush();
    expect(button('获取返回字段').dataset.loading).toBe('true');
    expect(config.value.dataColumns![0]!.fieldName).toBe('id');
    latest.resolve({ columns: [{ fieldName: 'id', fieldType: 12, pk: false, nullable: true }], rows: [] }); await flush();
    expect(button('获取返回字段').dataset.loading).toBe('false');
    expect(config.value.dataColumns).toEqual([{ fieldName: 'id', fieldType: 12, pk: false, nullable: true }]);
    Object.assign(config.value.dataColumns![0]!, { fieldType: 4, pk: true, nullable: false });
    vi.mocked(requestClient.post).mockResolvedValue({ columns: [{ fieldName: 'id', fieldType: 12, pk: false, nullable: true }], rows: [] });
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([{ fieldName: 'id', fieldType: 4, pk: true, nullable: false }]);
  });

  it('两次并发试调只有最新结果可展示，旧请求不关闭新请求loading', async () => {
    const old = deferred<string>();
    const latest = deferred<string>();
    vi.mocked(requestClient.post)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    await mount();
    await click('测试请求');
    await click('测试请求');
    old.resolve('old');
    await flush();
    expect(host.querySelector('pre')).toBeNull();
    expect(button('测试请求').dataset.loading).toBe('true');
    latest.resolve('latest');
    await flush();
    expect(host.querySelector('pre')?.textContent).toBe('latest');
    expect(button('测试请求').dataset.loading).toBe('false');
  });

  it.each(['replace', 'session', 'user', 'unmount'] as const)(
    '节点切换或身份变化丢弃旧试调结果：%s',
    async (change) => {
      const pending = deferred<string>();
      vi.mocked(requestClient.post).mockReturnValue(pending.promise);
      await mount();
      await click('测试请求');
      if (change === 'replace')
        config.value = defaultApiConfig({
          requestUrl: 'https://api.example.com/new',
        });
      else if (change === 'session') harness.access.refreshToken = '2';
      else if (change === 'user') harness.user.userInfo.userId = '2';
      else {
        app?.unmount();
        app = undefined;
      }
      await flush();
      pending.resolve('old identity response');
      await flush();
      expect(host.querySelector('pre')).toBeNull();
      expect(message.success).not.toHaveBeenCalled();
    },
  );

  it('试调失败在组件内保留可重试状态，不重复全局错误提示', async () => {
    vi.mocked(requestClient.post).mockRejectedValue(
      new Error('synthetic failure'),
    );
    await mount();
    await click('测试请求');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '测试请求失败',
    );
    expect(button('测试请求').dataset.loading).toBe('false');
    vi.mocked(requestClient.post).mockResolvedValue('retry succeeded');
    await click('测试请求');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('pre')?.textContent).toBe('retry succeeded');
  });

  it('取字段为空提示解析条件问题并保留已配置字段', async () => {
    config.value.dataColumns = [{ fieldName: 'existing' }];
    vi.mocked(requestClient.post).mockResolvedValue({ columns: [], rows: [] });
    await mount();
    await click('获取返回字段');
    expect(config.value.dataColumns).toEqual([{ fieldName: 'existing' }]);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '未解析到返回字段',
    );
  });

  it('字段可以编辑、配置主键并强制不可空，字段key不随名称改变', async () => {
    config.value.dataColumns = [{ fieldName: 'first', nullable: true }];
    await mount();
    const row = host.querySelector('tr');
    const field = row?.querySelector('input');
    if (!field) throw new Error('字段未渲染');
    field.value = 'updated';
    field.dispatchEvent(new Event('input'));
    await flush();
    expect(host.querySelector('tr')).toBe(row);
    const primaryKey = row?.querySelector<HTMLInputElement>(
      'input[type="checkbox"]',
    );
    if (!primaryKey) throw new Error('主键开关未渲染');
    primaryKey.checked = true;
    primaryKey.dispatchEvent(new Event('change'));
    await flush();
    expect(config.value.dataColumns).toEqual([
      { fieldName: 'updated', nullable: false, pk: true },
    ]);
    expect(
      row?.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1]
        ?.disabled,
    ).toBe(true);
  });

  it('disabled时禁用编辑、添加和试调操作', async () => {
    disabled.value = true;
    await mount();
    await click('测试请求');
    await click('获取返回字段');
    await click('手工添加');
    expect(requestClient.post).not.toHaveBeenCalled();
    expect(config.value.dataColumns).toEqual([]);
    expect(
      [...host.querySelectorAll('input, textarea, select, button')].every(
        (control) => (control as HTMLInputElement).disabled,
      ),
    ).toBe(true);
  });
});

describe('接口配置校验', () => {
  it('默认配置不会共享数组或修改种子配置', () => {
    const seed = { requestHeader: [{ name: 'X-Sample', value: 'seed' }] };
    const first = defaultApiConfig(seed);
    const second = defaultApiConfig(seed);
    first.requestHeader![0]!.value = 'changed';
    first.dataColumns!.push({ fieldName: 'column' });
    expect(second.requestHeader![0]!.value).toBe('seed');
    expect(seed.requestHeader[0]!.value).toBe('seed');
    expect(second.dataColumns).toEqual([]);
  });

  it.each([
    [{ requestUrl: '' }, '请求地址'],
    [{ requestUrl: 'file:///sample.json' }, 'http://'],
    [{ requestUrl: 'not a URL' }, '有效'],
    [{ requestUrl: 'https://sample:synthetic@example.com/data' }, '账号或密码'],
    [{ requestMethod: 'DELETE' }, 'GET 或 POST'],
    [{ ifPage: 1 }, '页码与每页条数'],
    [{ ifPage: 1, pageKey: 'page', pageSizeKey: 'page' }, '不能相同'],
    [{ requestHeader: [{ value: 'value' }] }, '参数名'],
    [{ requestMethod: 'POST', bodyType: 'raw', bodyRaw: '{broken' }, 'JSON'],
    [{ dataColumns: [] }, '返回字段'],
    [{ dataColumns: [{ fieldName: '' }] }, '字段名'],
    [{ dataColumns: [{ fieldName: 'id' }, { fieldName: ' id ' }] }, '不能重复'],
  ])('不完整或无效配置阻止向导继续：%o', (patch, expected) => {
    expect(
      validateApiConfig(
        defaultApiConfig({
          requestUrl: 'https://api.example.com/data',
          dataColumns: [{ fieldName: 'id' }],
          ...patch,
        }),
      ),
    ).toContain(expected);
  });

  it('试调允许尚未定义字段，保存阶段要求字段', () => {
    expect(
      validateApiConfig(config.value, { requireColumns: false }),
    ).toBeUndefined();
    expect(validateApiConfig(config.value)).toContain('返回字段');
    expect(
      validateApiConfig(config.value, {
        requireName: true,
        requireColumns: false,
      }),
    ).toContain('接口名称');
  });
});
