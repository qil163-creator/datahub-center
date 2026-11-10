import type { App, PropType } from 'vue';

import type { SupplyApiTestApi } from '#/api/supply/apitest';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getApiTestCatalog,
  getApiTestDetail,
  sendApiTest,
} from '#/api/supply/apitest';

import Workbench from './index.vue';

const harness = vi.hoisted(() => ({
  state: undefined as
    | undefined
    | {
        accessCodes: string[];
        accessToken: string;
        refreshToken: string;
        userInfo: { userId: string };
      },
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessToken: '1',
    refreshToken: '',
    accessCodes: ['supply:data-api:invoke'],
    userInfo: { userId: '9' },
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.state!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/plugins/code-editor', () => ({
  CodeEditor: defineComponent({
    props: {
      value: String,
      autoFormat: Boolean,
      mode: String,
      wordWrap: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLTextAreaElement).value),
        }),
  }),
}));
vi.mock('#/api/supply/apitest', () => ({
  getApiTestCatalog: vi.fn(),
  getApiTestDetail: vi.fn(),
  sendApiTest: vi.fn(),
}));
vi.mock('./components/api-tree.vue', () => ({
  default: defineComponent({
    props: {
      catalog: Object as PropType<SupplyApiTestApi.Catalog>,
      loading: Boolean,
    },
    emits: ['select', 'refresh'],
    setup:
      (props, { emit }) =>
      () =>
        h('aside', [
          h('button', { onClick: () => emit('refresh') }, '刷新目录'),
          ...(props.catalog?.apis ?? []).map((api) =>
            h(
              'button',
              { onClick: () => emit('select', String(api.id)) },
              `选择 ${api.name}`,
            ),
          ),
        ]),
  }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Card: Container,
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Input: defineComponent({
      props: {
        value: String,
        readonly: Boolean,
        type: String,
        disabled: Boolean,
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            readOnly: props.readonly,
            disabled: props.disabled,
            type: props.type ?? 'text',
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    InputNumber: Container,
    Select: defineComponent({
      props: {
        value: String,
        options: {
          type: Array as PropType<{ label: string; value: string }[]>,
          default: () => [],
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            props.options.map((option) =>
              h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    Checkbox: defineComponent({
      props: { checked: Boolean },
      emits: ['update:checked', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            onChange: (event: Event) => {
              emit(
                'update:checked',
                (event.target as HTMLInputElement).checked,
              );
              emit('change', event);
            },
          }),
    }),
    Radio: Container,
    RadioGroup: Container,
    Tag: Container,
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('section', { role: 'alert' }, props.message),
    }),
    Empty: defineComponent({
      props: { description: String },
      setup: (props) => () => h('p', props.description),
    }),
    message: { warning: vi.fn(), success: vi.fn() },
  };
});

const apiId = '9007199254740993';
const catalog: SupplyApiTestApi.Catalog = {
  publicBaseUrl: '',
  categories: [],
  apis: [
    {
      id: apiId,
      name: '测试接口',
      method: 'POST',
      path: '/sample',
      version: 'v1',
      authType: 'NONE',
      configType: 'TABLE',
      resultFormat: 'LIST',
      status: 1,
    },
  ],
};
const detail: SupplyApiTestApi.Detail = {
  ...catalog.apis[0]!,
  publicUrl: 'https://api.example.test/services/v1/sample',
  requestParams: [
    {
      name: 'afterRow',
      paramType: 'INTEGER',
      defaultValue: '9223372036854775806',
    },
  ],
};
const response: SupplyApiTestApi.Response = {
  statusCode: 418,
  statusText: 'Teapot',
  headers: [
    { name: 'Set-Cookie', value: 'a=1' },
    { name: 'Set-Cookie', value: 'b=2' },
  ],
  body: '{"id":9007199254740993}',
  // oxlint-disable-next-line unicorn/text-encoding-identifier-case -- 验证真实后端枚举。
  bodyEncoding: 'UTF8',
  bodyBytes: 23,
  durationMs: 12,
  truncated: false,
  url: detail.publicUrl,
  redirectChain: [],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Workbench);
  app.mount(host);
  await flush();
}
function button(text: string) {
  const found = [...host.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === text,
  );
  if (!found) throw new Error(`没有按钮 ${text}`);
  return found;
}
async function click(text: string) {
  button(text).click();
  await flush();
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
  )!;
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(harness.state!, {
    accessToken: '1',
    refreshToken: '',
    accessCodes: ['supply:data-api:invoke'],
    userInfo: { userId: '9' },
  });
  vi.mocked(getApiTestCatalog).mockResolvedValue(catalog);
  vi.mocked(getApiTestDetail).mockResolvedValue(detail);
  vi.mocked(sendApiTest).mockResolvedValue(response);
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});

describe('接口测试工作台', () => {
  it('收起目录后可重新展开并继续使用', async () => {
    await mount();
    const catalogCard = host.querySelector<HTMLElement>('.api-workbench__catalog')!;
    host.querySelector<HTMLButtonElement>('[aria-label="收起 API 目录"]')!.click();
    await flush();
    expect(catalogCard.style.display).toBe('none');
    host.querySelector<HTMLButtonElement>('[aria-label="展开 API 目录"]')!.click();
    await flush();
    expect(catalogCard.style.display).not.toBe('none');
    expect(catalogCard.textContent).toContain('选择 测试接口');
  });

  it('响应区收起后可展开并保留返回结果', async () => {
    await mount();
    await input('接口地址', 'https://example.test/echo');
    await click('发送');
    host.querySelector<HTMLButtonElement>('[aria-label="收起返回响应"]')!.click();
    await flush();
    expect(host.querySelector('.api-workbench__response--collapsed')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('[aria-label="展开返回响应"]')!.click();
    await flush();
    expect(host.querySelector('.api-workbench__response--collapsed')).toBeNull();
    expect(host.textContent).toContain('418 Teapot');
  });

  it('错误身份的定义不显示加载提示条，并阻止发送', async () => {
    vi.mocked(getApiTestDetail).mockResolvedValue({ ...detail, id: '999' });
    await mount();
    await click('选择 测试接口');
    expect(host.textContent).not.toContain('API 定义加载失败');
    await click('发送');
    expect(sendApiTest).not.toHaveBeenCalled();
  });
  it('不同原文模式切换分别保留内容，发送使用当前模式的请求体', async () => {
    await mount();
    await input('接口地址', 'https://example.test/echo');
    const method = host.querySelector<HTMLSelectElement>(
      'select[aria-label="请求方式"]',
    )!;
    method.value = 'POST';
    method.dispatchEvent(new Event('change'));
    await flush();
    await click('Body');
    await click('JSON');
    const json = '{"id":9007199254740993,"price":1.200000000000000001}';
    let textarea = host.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = json;
    textarea.dispatchEvent(new Event('input'));
    await flush();
    await click('XML');
    textarea = host.querySelector('textarea')!;
    expect(textarea.value).toBe('');
    textarea.value = '<data>hello</data>';
    textarea.dispatchEvent(new Event('input'));
    await flush();
    await click('JSON');
    expect(host.querySelector('textarea')?.value).toBe(json);
    await click('发送');
    expect(vi.mocked(sendApiTest).mock.calls[0]![0].body).toBe(json);
  });
  it('登记接口无损预填，发送固定 API ID，并展示真实 HTTP 失败状态和完整响应', async () => {
    await mount();
    await click('选择 测试接口');
    expect(getApiTestDetail).toHaveBeenCalledWith(
      apiId,
      expect.any(AbortSignal),
    );
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="接口地址"]')
        ?.readOnly,
    ).toBe(true);
    await click('发送');
    expect(sendApiTest).toHaveBeenCalledWith(
      expect.objectContaining({
        mode: 'REGISTERED',
        apiId,
        query: [{ name: 'afterRow', value: '9223372036854775806' }],
      }),
      expect.any(AbortSignal),
    );
    expect(vi.mocked(sendApiTest).mock.calls[0]![0]).not.toHaveProperty('url');
    expect(host.textContent).toContain('418 Teapot');
    expect(host.textContent).toContain('9007199254740993');
    await click('Cookies (2)');
    expect(host.textContent).toContain('a=1');
    expect(host.textContent).toContain('b=2');
  });
  it('外部 URL 走平台测试接口；每个标签保留独立输入和响应', async () => {
    await mount();
    await input('接口地址', 'https://example.test/echo');
    await click('发送');
    expect(sendApiTest).toHaveBeenCalledWith(
      expect.objectContaining({
        mode: 'EXTERNAL',
        url: 'https://example.test/echo',
        method: 'GET',
      }),
      expect.any(AbortSignal),
    );
    host
      .querySelector<HTMLButtonElement>('button[aria-label="新建测试"]')!
      .click();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="接口地址"]')
        ?.value,
    ).toBe('');
    expect(host.textContent).not.toContain('418 Teapot');
    host.querySelector<HTMLButtonElement>('button[role="tab"]')!.click();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="接口地址"]')
        ?.value,
    ).toBe('https://example.test/echo');
    expect(host.textContent).toContain('418 Teapot');
  });
  it('取消后丢弃迟到结果，重新发送也不被旧请求覆盖', async () => {
    const old = deferred<SupplyApiTestApi.Response>();
    vi.mocked(sendApiTest).mockReturnValueOnce(old.promise);
    await mount();
    await input('接口地址', 'https://example.test/echo');
    await click('发送');
    const signal = vi.mocked(sendApiTest).mock.calls[0]![1]!;
    await click('取消');
    expect(signal.aborted).toBe(true);
    await click('发送');
    old.resolve({ ...response, body: '旧请求响应' });
    await flush();
    expect(host.textContent).not.toContain('旧请求响应');
    expect(host.textContent).toContain('418 Teapot');
  });
  it.each(['session', 'user', 'permission'])(
    '%s 变化清除凭据、响应并取消在途请求',
    async (kind) => {
      const pending = deferred<SupplyApiTestApi.Response>();
      vi.mocked(sendApiTest).mockReturnValue(pending.promise);
      await mount();
      await input('接口地址', 'https://example.test/echo');
      await click('Auth');
      const select = host.querySelector<HTMLSelectElement>(
        'select[aria-label="认证类型"]',
      )!;
      select.value = 'bearer';
      select.dispatchEvent(new Event('change'));
      await flush();
      await input('Token', 'fixture-secret');
      await click('发送');
      expect(vi.mocked(sendApiTest).mock.calls[0]![0].headers).toContainEqual({
        name: 'Authorization',
        value: 'Bearer fixture-secret',
      });
      const signal = vi.mocked(sendApiTest).mock.calls[0]![1]!;
      if (kind === 'session') harness.state!.refreshToken = '2';
      else if (kind === 'user') harness.state!.userInfo = { userId: '10' };
      else harness.state!.accessCodes = [];
      await flush();
      expect(signal.aborted).toBe(true);
      pending.resolve(response);
      await flush();
      expect(host.textContent).not.toContain('418 Teapot');
      expect(
        [...host.querySelectorAll('input')].some(
          (element) => element.value === 'fixture-secret',
        ),
      ).toBe(false);
      expect(host.textContent).toContain(
        kind === 'permission' ? '没有接口测试权限' : '新请求',
      );
      expect(
        host.querySelector<HTMLInputElement>('input[aria-label="接口地址"]')
          ?.value ?? '',
      ).toBe('');
    },
  );
  it('关闭加载中的标签不会让旧 API 定义污染新标签', async () => {
    const pending = deferred<SupplyApiTestApi.Detail>();
    vi.mocked(getApiTestDetail).mockReturnValue(pending.promise);
    await mount();
    await click('选择 测试接口');
    const signal = vi.mocked(getApiTestDetail).mock.calls[0]![1]!;
    host
      .querySelector<HTMLButtonElement>('button[aria-label="关闭 测试接口"]')!
      .click();
    await flush();
    expect(signal.aborted).toBe(true);
    pending.resolve(detail);
    await flush();
    expect(
      host.querySelector<HTMLInputElement>('input[aria-label="接口地址"]')
        ?.value,
    ).toBe('');
  });
  it('目录失败可重试，且旧会话的迟到目录不会替换新目录', async () => {
    const old = deferred<SupplyApiTestApi.Catalog>();
    vi.mocked(getApiTestCatalog)
      .mockReturnValueOnce(old.promise)
      .mockRejectedValueOnce(new Error('failed'))
      .mockResolvedValueOnce(catalog);
    await mount();
    harness.state!.refreshToken = '2';
    await flush();
    expect(host.textContent).not.toContain('API 目录加载失败');
    await click('刷新目录');
    old.resolve({
      ...catalog,
      apis: [{ ...catalog.apis[0]!, name: '旧会话接口' }],
    });
    await flush();
    expect(host.textContent).not.toContain('旧会话接口');
    expect(host.textContent).toContain('选择 测试接口');
  });
});
