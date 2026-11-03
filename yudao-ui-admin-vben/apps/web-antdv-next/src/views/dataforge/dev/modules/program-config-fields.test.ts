/* eslint-disable vue/one-component-per-file -- 保留真实表单和规则，Select替身驱动控件值变更。 */
import type { FormInstance } from 'antdv-next';

import type { App } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { Form } from 'antdv-next';
import { afterEach, describe, expect, it, vi } from 'vitest';

import ProgramConfigFields from './program-config-fields.vue';

const selection = vi.hoisted(() => ({ update: undefined as ((value: unknown) => void) | undefined }));

vi.mock('antdv-next', async (importActual) => {
  const controls = await importActual<typeof import('antdv-next')>();
  return {
    ...controls,
    Select: defineComponent({
      props: { placeholder: { type: String, default: '' } },
      emits: ['update:value'],
      setup: (props, { emit }) => {
        if (props.placeholder === '请选择部署档案中的受管依赖') selection.update = (value) => emit('update:value', value);
        return () => h('span');
      },
    }),
  };
});

vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'session' }) }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevRuntimeProfiles: vi.fn(async () => []),
}));

let app: App | undefined;
let host: HTMLDivElement | undefined;
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });

async function mount(dependencyFileIds: DataforgeDevApi.ProgramConfig['dependencyFileIds']) {
  const model = reactive({ programConfig: { workloadMode: 'BATCH' as const, dependencyFileIds } });
  const formRef = ref<FormInstance>();
  host = document.createElement('div'); document.body.append(host);
  app = createApp(defineComponent({
    setup: () => () => h(Form, { model, ref: formRef }, {
      default: () => h(ProgramConfigFields, {
        taskType: 'FLINK_SQL',
        modelValue: model.programConfig,
      }),
    }),
  }));
  app.mount(host);
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
  return { validate: () => formRef.value!.validate(), model };
}

describe('原生配置依赖资源的真实表单校验', () => {
  it.each([undefined, null, [], ['9007199254740993'], [1, '9007199254740995']].map((value) => ({ value })))('选填依赖资源 $value 可通过完整表单校验并保留原值', async ({ value }) => {
    const form = await mount(value);
    await expect(form.validate()).resolves.toBeDefined();
    expect(form.model.programConfig.dependencyFileIds).toEqual(value);
  });
  it.each([[0], [-1], ['0'], ['001'], ['1.5'], ['not-an-id'], ['']].map((value) => ({ value })))('非法依赖资源 $value 被完整表单拒绝', async ({ value }) => {
    const form = await mount(value);
    await expect(form.validate()).rejects.toMatchObject({ errorFields: [expect.objectContaining({ name: ['programConfig', 'dependencyFileIds'] })] });
  });
  it('控件变更保持大ID字符串，清空值规范为undefined', async () => {
    const form = await mount(null);
    selection.update?.(['9007199254740993', '9007199254740995']);
    expect(form.model.programConfig.dependencyFileIds).toEqual(['9007199254740993', '9007199254740995']);
    await expect(form.validate()).resolves.toBeDefined();
    selection.update?.(null);
    expect(form.model.programConfig.dependencyFileIds).toBeUndefined();
    await expect(form.validate()).resolves.toBeDefined();
    selection.update?.(undefined);
    expect(form.model.programConfig.dependencyFileIds).toBeUndefined();
  });
  it('控件的标量、标签对象和不安全数值不替换现有无损ID', async () => {
    const form = await mount(['9007199254740993']);
    for (const value of ['9007199254740995', [{ value: '9007199254740995' }], [9_007_199_254_740_992], [true]]) {
      selection.update?.(value);
      expect(form.model.programConfig.dependencyFileIds).toEqual(['9007199254740993']);
    }
  });

});
