import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/integration/service-metadata';

import RequestEditor from './reference-request-editor.vue';
import WebServiceEditor from './reference-webservice-editor.vue';

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'test-session' }),
}));
vi.mock('#/api/dataforge/integration/service-metadata', () => ({
  getIntegrationHttpMetadata: vi.fn(),
  getIntegrationWebServiceMetadata: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number], disabled: Boolean, options: Array },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const Container = defineComponent({
    props: { message: String },
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.message, slots.default?.()]),
  });
  return {
    Input: Field,
    InputNumber: Field,
    Select: Field,
    TextArea: Field,
    Radio: Container,
    Checkbox: Container,
    Switch: Container,
    Modal: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('div', [slots.default?.(), slots.footer?.()]),
    }),
    RadioGroup: Container,
    Alert: Container,
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'button',
            { disabled: props.disabled || props.loading },
            slots.default?.(),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => vi.resetAllMocks());
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function mount(
  component: typeof RequestEditor | typeof WebServiceEditor,
  config: Record<string, any>,
  disabled = false,
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({
    render: () =>
      h(component, {
        ref: editor,
        config: state.config,
        disabled: state.disabled,
        taskId: '9007199254740993',
        revision: 3,
      }),
  });
  app.mount(host);
  return { state, editor };
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(
    `[aria-label="${label}"]`,
  )!;
  expect(element).toBeTruthy();
  element.value = value;
  element.dispatchEvent(new Event('input'));
  await flush();
}
async function click(label: string) {
  const element = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === label,
  );
  expect(element).toBeTruthy();
  element!.click();
  await flush();
}
const response = {
  services: [
    {
      name: 'Weather',
      ports: [
        {
          name: 'WeatherSoap',
          apiMethod: 'soap11' as const,
          endpoint: 'https://example.test/service',
          operations: [
            {
              name: 'Read',
              targetNamespace: 'urn:test',
              parameters: [
                { name: 'city', dataType: 'string', required: true },
              ],
              partial: true,
            },
          ],
        },
      ],
    },
  ],
  warnings: ['复杂返回结构须手动声明字段'],
};
describe('集成专属四步骤接口配置', () => {
  it('接口按步骤独立配置，保留旧请求体与未知历史字段', async () => {
    const config = {
      vendor: { legacy: true },
      apiConfig: {
        requestUrl: 'https://example.test/rows',
        requestMethod: 'POST',
        bodyType: 'raw',
        bodyRaw: '{"key":"retained"}',
        dataColumns: [{ fieldName: 'id', fieldType: -5 }],
      },
    };
    const { state, editor } = mount(RequestEditor, config);
    expect(host.querySelectorAll('.qi-service-steps button')).toHaveLength(4);
    await input('API URL', 'https://example.test/new');
    expect(state.config.apiConfig.bodyRaw).toBe('{"key":"retained"}');
    expect(state.config.vendor).toEqual({ legacy: true });
    expect(editor.value!.validate()).toBe(true);
  });
  it('受管GET采样只合并新字段且保留未知类型，不能默认为字符串', async () => {
    vi.mocked(api.getIntegrationHttpMetadata).mockResolvedValue({
      fields: [
        { name: 'id', dataType: 'LONG' },
        { name: 'unknown', dataType: null },
      ],
      partial: true,
      warnings: [],
    });
    const { state, editor } = mount(RequestEditor, {
      resourceBindingId: 'http.rows',
      apiConfig: {
        requestUrl: 'https://example.test/rows',
        requestMethod: 'GET',
        dataColumns: [
          { fieldName: 'id', fieldType: 12, description: '保留人工设置' },
        ],
      },
    });
    await click('从结果JSON中自动分析');
    expect(api.getIntegrationHttpMetadata).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        revision: 3,
        resourceBindingId: 'http.rows',
      }),
    );
    await click('增加新的');
    expect(state.config.apiConfig.dataColumns).toEqual([
      { fieldName: 'id', fieldType: 12, description: '保留人工设置' },
      { fieldName: 'unknown', fieldType: undefined },
    ]);
    expect(editor.value!.validate()).toBe(false);
    await flush();
    expect(host.textContent).toContain('请手动确认');
  });
  it('节点替换后丢弃旧API采样且只读事件不写入', async () => {
    let resolve!: (value: {
      fields: { dataType: string; name: string }[];
      partial: boolean;
      warnings: string[];
    }) => void;
    vi.mocked(api.getIntegrationHttpMetadata).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const { state } = mount(RequestEditor, {
      resourceBindingId: 'http.rows',
      apiConfig: { requestUrl: 'https://example.test/rows' },
    });
    await click('从结果JSON中自动分析');
    state.config = {
      resourceBindingId: 'http.rows',
      apiConfig: {
        requestUrl: 'https://example.test/rows',
        dataColumns: [{ fieldName: 'current', fieldType: 12 }],
      },
    };
    state.disabled = true;
    await flush();
    resolve({
      fields: [{ name: 'stale', dataType: 'STRING' }],
      partial: true,
      warnings: [],
    });
    await flush();
    await input('API URL', 'https://ignored.test/');
    expect(state.config.apiConfig.requestUrl).toBe('https://example.test/rows');
    expect(state.config.apiConfig.dataColumns).toEqual([
      { fieldName: 'current', fieldType: 12 },
    ]);
  });
  it('受管WSDL解析提供服务端口操作联动，不在解析时重写已有草稿', async () => {
    vi.mocked(api.getIntegrationWebServiceMetadata).mockResolvedValue(response);
    const { state } = mount(WebServiceEditor, {
      resourceBindingId: 'soap.weather',
      apiUrl: 'https://example.test/weather?wsdl',
      columns: [],
      vendor: '保留',
    });
    const before = JSON.stringify(state.config);
    await click('解析WSDL');
    expect(JSON.stringify(state.config)).toBe(before);
    await input('WSDL请求接口', 'Read');
    expect(state.config).toMatchObject({
      serviceName: 'Weather',
      portName: 'WeatherSoap',
      operationName: 'Read',
      apiMethod: 'soap11',
      targetNamespace: 'urn:test',
      vendor: '保留',
    });
    expect(host.querySelector('[aria-label="WSDL端点地址"]')).toBeNull();
    expect(host.textContent).not.toContain('高级执行配置');
    await click('获取接口参数');
    expect(JSON.parse(state.config.requestParams)).toEqual({ city: '' });
  });
  it('受管WSDL拒绝和迟到响应均不覆盖当前节点配置', async () => {
    let resolve!: (value: typeof response) => void;
    vi.mocked(api.getIntegrationWebServiceMetadata)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolve = done;
        }),
      )
      .mockRejectedValueOnce(new Error('未授权控制面元数据地址'));
    const { state } = mount(WebServiceEditor, {
      resourceBindingId: 'soap.weather',
      apiUrl: 'https://example.test/weather?wsdl',
      serviceName: '原服务',
    });
    await click('解析WSDL');
    state.config = {
      resourceBindingId: 'soap.weather',
      apiUrl: 'https://example.test/weather?wsdl',
      serviceName: '新节点服务',
    };
    await flush();
    resolve(response);
    await flush();
    expect(host.querySelector('[aria-label="WSDL端点地址"]')).toBeNull();
    await click('解析WSDL');
    expect(host.textContent).toContain('未授权控制面元数据地址');
    expect(state.config.serviceName).toBe('新节点服务');
  });
  it('切换到其它端口独有的接口时同步方式和命名空间，同名接口不猜测端口', async () => {
    const secondPort = {
      name: 'WeatherSoap12',
      apiMethod: 'soap12' as const,
      endpoint: 'https://example.test/service12',
      operations: [
        {
          name: 'Read12',
          targetNamespace: 'urn:test:12',
          parameters: [],
          partial: true,
        },
      ],
    };
    vi.mocked(api.getIntegrationWebServiceMetadata).mockResolvedValue({
      ...response,
      services: [
        {
          ...response.services[0]!,
          ports: [response.services[0]!.ports[0]!, secondPort],
        },
      ],
    });
    const { state } = mount(WebServiceEditor, {
      resourceBindingId: 'soap.weather',
      apiUrl: 'https://example.test/weather?wsdl',
      serviceName: 'Weather',
      portName: 'WeatherSoap',
      operationName: 'Read',
      apiMethod: 'soap11',
      targetNamespace: 'urn:test',
    });
    await click('解析WSDL');
    await input('WSDL请求接口', 'Read12');
    expect(state.config).toMatchObject({
      portName: 'WeatherSoap12',
      operationName: 'Read12',
      apiMethod: 'soap12',
      targetNamespace: 'urn:test:12',
    });
    secondPort.operations[0]!.name = 'Read';
    state.config.portName = '';
    state.config.operationName = '';
    await click('解析WSDL');
    await input('WSDL请求接口', 'Read');
    expect(state.config.portName).toBe('');
    expect(state.config.operationName).toBe('');
    expect(host.textContent).toContain('存在同名接口');
    await input(
      'WSDL请求接口',
      JSON.stringify(['Weather', 'WeatherSoap12', 'Read']),
    );
    expect(state.config).toMatchObject({
      serviceName: 'Weather',
      portName: 'WeatherSoap12',
      operationName: 'Read',
      apiMethod: 'soap12',
      targetNamespace: 'urn:test:12',
    });
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="WSDL请求接口"]')
        ?.value,
    ).toBe(JSON.stringify(['Weather', 'WeatherSoap12', 'Read']));
  });
});
