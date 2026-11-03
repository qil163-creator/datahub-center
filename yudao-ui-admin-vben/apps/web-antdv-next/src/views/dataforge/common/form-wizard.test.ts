/* eslint-disable vue/one-component-per-file -- The input stub and form host belong to one runtime test harness. */
import type { App, EffectScope } from 'vue';

import type { VbenFormSchema } from '#/adapter/form';

import {
  createApp,
  defineComponent,
  effectScope,
  h,
  markRaw,
  nextTick,
} from 'vue';

import { globalShareState, setupVbenForm } from '@vben/common-ui';

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { z } from '#/adapter/form';

import { useDataforgeWizardForm } from './form-wizard';

vi.mock('antdv-next', () => ({ message: { warning: vi.fn() } }));

const TestInput = defineComponent({
  inheritAttrs: false,
  props: {
    disabled: Boolean,
    modelValue: { type: [String, Number], default: '' },
    name: { type: String, default: '' },
    visibilityToggle: { type: Boolean, default: true },
  },
  emits: ['update:modelValue'],
  setup:
    (props, { emit }) =>
    () =>
      h('input', {
        'data-control': props.name,
        'data-visibility-toggle': String(props.visibilityToggle),
        disabled: props.disabled,
        value: props.modelValue ?? '',
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value),
      }),
});

beforeAll(() => {
  globalShareState.setComponents({
    ...globalShareState.getComponents(),
    InputPassword: markRaw(TestInput),
  });
  setupVbenForm({ config: { baseModelPropName: 'modelValue' } });
});

const steps = [
  {
    title: '基本信息',
    description: '任务名称',
    hint: '填写名称',
    fields: ['jobName'],
  },
  {
    title: '连接配置',
    description: '服务地址与认证',
    hint: '设置连接',
    fields: ['sourceMode', 'host', 'password', 'lockedField'],
  },
  {
    title: '写入目标',
    description: '目标表',
    hint: '设置目标',
    fields: ['targetTable'],
  },
  {
    title: '确认提交',
    description: '核对配置',
    hint: '核对后保存',
    fields: [],
  },
];

function schema(): VbenFormSchema[] {
  return [
    {
      component: markRaw(TestInput),
      fieldName: 'jobName',
      label: '任务名称',
      defaultValue: '',
      rules: z.string().min(1, '请填写任务名称'),
    },
    {
      component: markRaw(TestInput),
      fieldName: 'sourceMode',
      label: '来源类型',
      defaultValue: 'LOCAL',
    },
    {
      component: markRaw(TestInput),
      fieldName: 'host',
      label: '服务地址',
      defaultValue: '',
      dependencies: {
        triggerFields: ['sourceMode'],
        resolve: ({ values }) => ({
          show: values.sourceMode === 'REMOTE',
          rules:
            values.sourceMode === 'REMOTE'
              ? z.string().min(1, '请填写远程服务地址')
              : null,
        }),
      },
    },
    {
      component: 'InputPassword',
      fieldName: 'password',
      label: '密码',
      defaultValue: '',
      componentProps: { visibilityToggle: true },
    },
    {
      component: markRaw(TestInput),
      fieldName: 'lockedField',
      label: '只读编号',
      defaultValue: '9007199254740993',
      disabled: true,
    },
    {
      component: markRaw(TestInput),
      fieldName: 'targetTable',
      label: '目标表',
      defaultValue: '',
      rules: z.string().min(1, '请填写目标表'),
    },
  ];
}

type Wizard = ReturnType<typeof useDataforgeWizardForm>;
type ValidateStep = NonNullable<
  Parameters<typeof useDataforgeWizardForm>[0]['validateStep']
>;
const mounted: { app: App; host: HTMLDivElement; scope: EffectScope }[] = [];

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

async function mountWizard(validateStep?: ValidateStep) {
  const scope = effectScope();
  const wizard = scope.run(() =>
    useDataforgeWizardForm({ schema: schema(), steps, validateStep }),
  )!;
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp({ render: () => h(wizard.Form) });
  mounted.push({ app, host, scope });
  app.mount(host);
  await flush();
  return { wizard, host, scope };
}

function input(host: HTMLDivElement, field: string) {
  return host.querySelector<HTMLInputElement>(`[data-control="${field}"]`)!;
}

async function fillValid(wizard: Wizard) {
  await wizard.formApi.setValues({
    jobName: '数据接入草稿',
    targetTable: 'access_records',
  });
  await flush();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

afterEach(() => {
  for (const { app, host, scope } of mounted.splice(0)) {
    scope.stop();
    app.unmount();
    host.remove();
  }
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('dataforge wizard with the real form runtime', () => {
  it('retains entered values and mounted controls while changing pages', async () => {
    const { wizard, host } = await mountWizard();
    const nameControl = input(host, 'jobName');
    nameControl.value = '消息接入草稿';
    nameControl.dispatchEvent(new Event('input'));
    await flush();
    await wizard.goToStep(1);
    input(host, 'password').value = 'draft-secret';
    input(host, 'password').dispatchEvent(new Event('input'));
    await flush();
    await wizard.goToStep(2);
    input(host, 'targetTable').value = 'message_records';
    input(host, 'targetTable').dispatchEvent(new Event('input'));
    await flush();
    await wizard.goToStep(0);
    await flush();

    expect(input(host, 'jobName')).toBe(nameControl);
    expect(nameControl.value).toBe('消息接入草稿');
    expect(await wizard.formApi.getValues()).toMatchObject({
      jobName: '消息接入草稿',
      password: 'draft-secret',
      targetTable: 'message_records',
      lockedField: '9007199254740993',
    });
  });

  it('validates the page being left without requiring later pages yet', async () => {
    const { wizard } = await mountWizard();
    const validate = vi.spyOn(wizard.formApi, 'validateField');

    await wizard.goToStep(1);
    expect(wizard.currentStep.value).toBe(0);
    expect(validate).toHaveBeenCalledExactlyOnceWith('jobName');

    await wizard.formApi.setFieldValue('jobName', '有效名称');
    validate.mockClear();
    await wizard.goToStep(1);
    expect(wizard.currentStep.value).toBe(1);
    expect(validate).toHaveBeenCalledExactlyOnceWith('jobName');
    expect(await wizard.formApi.getValues()).toHaveProperty('targetTable', '');
  });

  it('preserves conditional visibility and required rules across pages', async () => {
    const { wizard, host } = await mountWizard();
    await fillValid(wizard);
    await wizard.goToStep(1);
    await flush();
    expect(
      input(host, 'host').closest('[style*="display: none"]'),
    ).not.toBeNull();

    await wizard.goToStep(2);
    expect(wizard.currentStep.value).toBe(2);
    await wizard.goToStep(1);
    await wizard.formApi.setFieldValue('sourceMode', 'REMOTE');
    await flush();
    expect(input(host, 'host').closest('[style*="display: none"]')).toBeNull();
    await wizard.goToStep(2);
    expect(wizard.currentStep.value).toBe(1);

    await wizard.formApi.setFieldValue('host', 'example.invalid');
    await wizard.goToStep(2);
    expect(wizard.currentStep.value).toBe(2);
  });

  it('validates intermediate pages before a forward jump and returns to the invalid page', async () => {
    const { wizard } = await mountWizard();
    await wizard.formApi.setFieldValue('jobName', '有效名称');
    const validate = vi.spyOn(wizard.formApi, 'validateField');

    await wizard.goToStep(3);

    expect(wizard.currentStep.value).toBe(2);
    expect(validate.mock.calls.map(([field]) => field)).toEqual([
      'jobName',
      'sourceMode',
      'host',
      'password',
      'lockedField',
      'targetTable',
    ]);
  });

  it('allows backward navigation without validating unfinished edits', async () => {
    const { wizard } = await mountWizard();
    await fillValid(wizard);
    await wizard.goToStep(2);
    await wizard.formApi.setFieldValue('targetTable', '');
    const validate = vi.spyOn(wizard.formApi, 'validateField');

    await wizard.goToStep(0);

    expect(wizard.currentStep.value).toBe(0);
    expect(validate).not.toHaveBeenCalled();
  });

  it('revalidates earlier conditional fields before the final save', async () => {
    const { wizard } = await mountWizard();
    await fillValid(wizard);
    await wizard.goToStep(3);
    await wizard.formApi.setFieldValue('sourceMode', 'REMOTE');
    await flush();
    const validate = vi.spyOn(wizard.formApi, 'validateField');

    expect(await wizard.validateAll()).toBe(false);
    expect(wizard.currentStep.value).toBe(1);
    expect(validate).toHaveBeenCalledWith('jobName');
    expect(validate).toHaveBeenCalledWith('host');
    expect(validate).not.toHaveBeenCalledWith('targetTable');

    await wizard.formApi.setFieldValue('host', 'example.invalid');
    await wizard.goToStep(3);
    validate.mockClear();
    expect(await wizard.validateAll()).toBe(true);
    expect(validate.mock.calls.map(([field]) => field)).toEqual([
      'jobName',
      'sourceMode',
      'host',
      'password',
      'lockedField',
      'targetTable',
    ]);
  });

  it('disables confirmation controls and password reveal, then restores editing state', async () => {
    const { wizard, host } = await mountWizard();
    await fillValid(wizard);
    await wizard.formApi.setFieldValue('password', 'draft-secret');
    await wizard.goToStep(3);
    await flush();

    expect(input(host, 'jobName').disabled).toBe(true);
    expect(input(host, 'password').disabled).toBe(true);
    expect(input(host, 'password').dataset.visibilityToggle).toBe('false');
    expect(input(host, 'lockedField').disabled).toBe(true);

    await wizard.goToStep(1);
    await flush();

    expect(input(host, 'jobName').disabled).toBe(false);
    expect(input(host, 'password').disabled).toBe(false);
    expect(input(host, 'password').dataset.visibilityToggle).toBe('true');
    expect(input(host, 'lockedField').disabled).toBe(true);
    expect(await wizard.formApi.getValues()).toHaveProperty(
      'password',
      'draft-secret',
    );
  });

  it('invalidates old async navigation on reset without clearing a newer request busy state', async () => {
    const old = deferred<string | undefined>();
    const latest = deferred<string | undefined>();
    const validateStep = vi
      .fn<ValidateStep>()
      .mockImplementationOnce(() => old.promise)
      .mockImplementationOnce(() => latest.promise);
    const { wizard } = await mountWizard(validateStep);
    await fillValid(wizard);
    const first = wizard.goToStep(1);
    await vi.waitFor(() => expect(validateStep).toHaveBeenCalledTimes(1));
    wizard.resetSteps();
    const second = wizard.goToStep(1);
    await vi.waitFor(() => expect(validateStep).toHaveBeenCalledTimes(2));

    old.resolve('旧请求的校验失败');
    await first;
    expect(wizard.currentStep.value).toBe(0);
    expect(wizard.busy.value).toBe(true);

    latest.resolve(undefined);
    await second;
    expect(wizard.currentStep.value).toBe(1);
    expect(wizard.busy.value).toBe(false);
  });

  it('invalidates pending navigation when its owning scope is disposed', async () => {
    const pending = deferred<string | undefined>();
    const validateStep = vi.fn<ValidateStep>(() => pending.promise);
    const { wizard, scope } = await mountWizard(validateStep);
    await fillValid(wizard);
    const navigation = wizard.goToStep(1);
    await vi.waitFor(() => expect(validateStep).toHaveBeenCalledOnce());

    scope.stop();
    pending.resolve(undefined);
    await navigation;

    expect(wizard.currentStep.value).toBe(0);
    expect(wizard.busy.value).toBe(false);
  });
});
