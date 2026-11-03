import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ManagedCryptoEditor from './managed-crypto-editor.vue';

vi.mock('antdv-next', () => {
  const Field = defineComponent({ props: { value: [String, Boolean], disabled: Boolean }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: props.value, disabled: props.disabled, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }) });
  return { Input: Field, AutoComplete: Field, Select: Field, Checkbox: defineComponent({ render: () => null }),
    Tooltip: defineComponent({ props: { title: String }, setup: (props, { slots }) => () => h('span', { 'data-help': props.title }, slots.default?.()) }),
    Button: defineComponent({ props: { disabled: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()) }),
    Alert: defineComponent({ props: { message: String }, setup: (props, { slots }) => () => h('div', [props.message, slots.action?.()]) }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });
function mount(config: Record<string, any>, disabled = false, toolCode: 'data-decrypt' | 'data-encrypt' = 'data-encrypt') {
  host = document.createElement('div'); document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({ render: () => h(ManagedCryptoEditor, { ref: editor, config: state.config, toolCode, disabled: state.disabled, inputFields: [{ name: 'name' }] }, { description: () => h('textarea', { 'aria-label': '节点描述' }) }) });
  app.mount(host); return { state, editor };
}
async function input(label: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  field.value = value; field.dispatchEvent(new Event('input')); await nextTick();
}
describe('集成受管加解密配置', () => {
  it('只收集AES或SM4密钥引用，不呈现历史明文密钥，显式清除后保留其他配置', async () => {
    const { state, editor } = mount({ vendor: 42, encryptFields: [{ fieldName: 'name', algorithm: 'AES256', secretKey: 'synthetic-old-key', keepOriginal: true, custom: 'kept' }] });
    expect(host.querySelector('input[type="password"]')).toBeNull();
    expect([...host.querySelectorAll('input')].some((field) => field.value === 'synthetic-old-key')).toBe(false);
    expect(editor.value!.validate()).toBe(false);
    [...host.querySelectorAll('button')].find((button) => button.textContent?.trim() === '清除旧密钥')!.click(); await nextTick();
    await input('第1行加解密算法', 'SM4'); await input('第1行密钥引用', 'key-reference');
    expect(editor.value!.validate()).toBe(true);
    expect(state.config.encryptFields[0]).toMatchObject({ algorithm: 'SM4', secretKeyRef: 'key-reference', custom: 'kept', keepOriginal: true });
    expect(state.config.encryptFields[0].secretKey).toBeUndefined(); expect(state.config.vendor).toBe(42);
  });
  it('只读不创建字段，切为只读后不能写入配置', async () => {
    const { state } = mount({ vendor: 1 }, true);
    expect(state.config).toEqual({ vendor: 1 });
    state.disabled = false; await nextTick();
    expect(state.config.encryptFields).toHaveLength(1);
    state.disabled = true; await nextTick();
    await input('第1行来源字段', 'ignored');
    expect(state.config.encryptFields[0].fieldName).toBe('');
  });
  it('保留已经保存的无密钥摘要配置和未知属性', () => {
    const original = { encryptFields: [{ fieldName: 'name', algorithm: 'SHA256', outputFieldName: 'hash', vendor: 7 }] };
    const { state, editor } = mount(original);
    expect(editor.value!.validate()).toBe(true); expect(state.config).toEqual(original);
    expect(host.querySelector('[aria-label="第1行密钥引用"]')).toBeNull();
  });
  it('加密主表保持算法和受管密钥、来源和输出两列，描述在字段之后且提示覆盖契约', () => {
    const { state } = mount({ vendor: 42 });
    expect(state.config.encryptFields[0]).toMatchObject({ algorithm: 'AES', secretKeyRef: '', fieldName: '', outputFieldName: '' });
    expect(host.querySelector('[aria-label="第1行密钥引用"]')!.getAttribute('placeholder')).toBe('请输入密钥引用');
    expect(host.querySelector('[aria-label="第1行来源字段"]')!.getAttribute('placeholder')).toBe('请选择明文字段');
    expect(host.querySelector('[aria-label="第1行输出字段"]')!.getAttribute('placeholder')).toBe('请输入密文字段');
    expect(host.querySelector('[aria-label="密钥引用说明"]')!.closest<HTMLElement>('[data-help]')!.dataset.help).toContain('管理员授权的密钥引用');
    expect(host.querySelector('[aria-label="密文字段说明"]')!.closest<HTMLElement>('[data-help]')!.dataset.help).toContain('留空时覆盖来源字段');
    const section = host.querySelector('.qi-crypto-field')!;
    expect([...section.children].map((child) => child.tagName)).toEqual(['DIV', 'TEXTAREA', 'DETAILS']);
    expect(host.querySelectorAll('[aria-label="节点描述"]')).toHaveLength(1);
  });
  it('解密编辑使用真实密钥引用和密文来源，保留未知属性、覆盖默认与只读保护', async () => {
    const { state, editor } = mount({ vendor: 42, decryptFields: [{ fieldName: 'cipher', algorithm: 'AES', secretKeyRef: 'managed-key', outputFieldName: '', encoding: 'HEX', custom: 7 }] }, false, 'data-decrypt');
    expect(host.querySelector('[aria-label="第1行来源字段"]')!.getAttribute('placeholder')).toBe('请选择密文字段');
    expect(host.querySelector('[aria-label="第1行输出字段"]')!.getAttribute('placeholder')).toBe('请输入明文字段');
    expect(host.querySelector('[aria-label="明文字段说明"]')!.closest<HTMLElement>('[data-help]')!.dataset.help).toContain('解密后的数据');
    expect(editor.value!.validate()).toBe(true);
    await input('第1行加解密算法', 'SM4');
    await input('第1行输出字段', 'plain');
    expect(state.config.decryptFields[0]).toMatchObject({ algorithm: 'SM4', secretKeyRef: 'managed-key', outputFieldName: 'plain', encoding: 'HEX', custom: 7 });
    const saved = JSON.stringify(state.config);
    state.disabled = true;
    await nextTick();
    await input('第1行密钥引用', 'ignored');
    await input('第1行来源字段', 'ignored');
    expect(JSON.stringify(state.config)).toBe(saved);
    expect(state.config.encryptFields).toBeUndefined();
  });
});
