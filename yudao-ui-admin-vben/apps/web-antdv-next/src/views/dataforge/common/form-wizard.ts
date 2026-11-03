import type { VbenFormProps, VbenFormSchema } from '#/adapter/form';

import { nextTick, onScopeDispose, ref, watch } from 'vue';

import { cn } from '@vben/utils';

import { message } from 'antdv-next';

import { useDataforgeForm } from './form-layout';

export interface DataforgeWizardStep {
  description: string;
  fields: string[];
  hint: string;
  title: string;
}

interface WizardFormOptions extends VbenFormProps {
  steps: DataforgeWizardStep[];
  validateStep?: (
    step: number,
    values: Record<string, any>,
  ) => Promise<string | undefined> | string | undefined;
}

/** 保持控件挂载，切换步骤时保留校验规则和已填写的值。 */
export function createWizardSchema(
  schema: VbenFormSchema[],
  steps: DataforgeWizardStep[],
  current: number,
): VbenFormSchema[] {
  const confirming = current === steps.length - 1;
  const fields = steps[current]?.fields ?? [];
  const order = steps.flatMap((step) => step.fields);
  const sorted = confirming
    ? schema
    : schema.toSorted(
        (left, right) =>
          order.indexOf(left.fieldName) - order.indexOf(right.fieldName),
      );
  return sorted.map((field) => {
    const originalClass =
      typeof field.formItemClass === 'function'
        ? field.formItemClass()
        : (field.formItemClass ?? 'col-span-1');
    const visible = confirming || fields.includes(field.fieldName);
    return {
      ...field,
      disabled: confirming || field.disabled,
      formItemClass: `${originalClass} ${visible ? '' : 'hidden'}`,
      // Switches use the component's intrinsic width, not the text-input default.
      ...(field.component === 'Switch'
        ? {
            componentProps: (context: { fieldName?: string }) => {
              const props =
                typeof field.componentProps === 'function'
                  ? field.componentProps(context)
                  : field.componentProps;
              return { ...props, class: cn(props?.class, 'w-auto') };
            },
          }
        : {}),
      ...(confirming && field.component === 'InputPassword'
        ? {
            componentProps: {
              ...(typeof field.componentProps === 'function'
                ? field.componentProps({ fieldName: field.fieldName })
                : field.componentProps),
              visibilityToggle: false,
            },
          }
        : {}),
    };
  });
}

/** 复用领域表单 Schema 与 codec，统一分步展示和导航。 */
export function useDataforgeWizardForm(options: WizardFormOptions) {
  const { schema = [], steps, validateStep, ...formOptions } = options;
  const currentStep = ref(0);
  const busy = ref(false);
  let generation = 0;
  const [Form, formApi] = useDataforgeForm({
    ...formOptions,
    layout: options.layout ?? 'vertical',
    scrollToFirstError: false,
    wrapperClass: 'grid-cols-1 gap-x-6 sm:grid-cols-2',
    schema: createWizardSchema(schema, steps, 0),
  });

  watch(currentStep, (current) => {
    formApi.setState({ schema: createWizardSchema(schema, steps, current) });
  });

  function resetSteps() {
    ++generation;
    currentStep.value = 0;
    busy.value = false;
  }

  async function checkStep(step: number, started: number) {
    const results = await Promise.all(
      (steps[step]?.fields ?? []).map((field) => formApi.validateField(field)),
    );
    if (started !== generation) return false;
    if (results.some((result) => !result.valid)) {
      currentStep.value = step;
      return false;
    }
    const error = await validateStep?.(step, await formApi.getValues());
    if (started !== generation) return false;
    if (error) {
      currentStep.value = step;
      message.warning(error);
      return false;
    }
    return true;
  }

  async function goToStep(next: number) {
    if (
      busy.value ||
      !Number.isInteger(next) ||
      next < 0 ||
      next >= steps.length ||
      next === currentStep.value
    ) {
      return;
    }
    if (next < currentStep.value) {
      currentStep.value = next;
      return;
    }
    const started = generation;
    busy.value = true;
    try {
      for (let step = currentStep.value; step < next; ++step) {
        if (!(await checkStep(step, started))) return;
      }
      if (started === generation) currentStep.value = next;
    } finally {
      if (started === generation) busy.value = false;
    }
  }

  async function validateAll() {
    if (busy.value) return false;
    const started = generation;
    busy.value = true;
    try {
      // Revalidate earlier pages: later choices can change their conditional rules.
      for (let step = 0; step < steps.length - 1; ++step) {
        if (!(await checkStep(step, started))) {
          await nextTick();
          return false;
        }
      }
      return started === generation;
    } finally {
      if (started === generation) busy.value = false;
    }
  }

  onScopeDispose(resetSteps);
  return {
    Form,
    formApi,
    currentStep,
    steps,
    busy,
    goToStep,
    resetSteps,
    validateAll,
  };
}
