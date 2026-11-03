<script lang="ts" setup>
import type { PlanFormValues } from '../../data';

import type { DataforgeWizardStep } from '#/views/dataforge/common/form-wizard';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { message } from 'antdv-next';

import { createPlan, getPlan, updatePlan } from '#/api/dataforge/fill';
import { $t } from '#/locales';
import { useDataforgeWizardForm } from '#/views/dataforge/common/form-wizard';
import DataforgeFormWizardPage from '#/views/dataforge/common/form-wizard-page.vue';

import {
  getDefaultPlanFormValues,
  toPlanFormValues,
  toPlanSaveReq,
  usePlanFormSchema,
  validatePlanOrgs,
} from '../../data';

defineOptions({ name: 'DataforgeFillPlanForm' });

const props = defineProps<{
  initialData?: { categoryId?: number | string; id?: number | string };
}>();
const emit = defineEmits<{ close: []; success: [] }>();
const access = useAccessStore();
const user = useUserStore();

const editingId = ref<number | string>();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
const formIdentity = identity.value;

const planSteps: DataforgeWizardStep[] = [
  {
    title: '配置基础信息',
    description: '计划名称与填报模板',
    hint: '填写计划用途，选择填报表及已发布的模板版本',
    fields: [
      'name',
      'categoryId',
      'processDefinitionKey',
      'description',
      'tableId',
      'tableVersionId',
    ],
  },
  {
    title: '周期与截止',
    description: '何时生成、何时截止',
    hint: '设置采集周期、生成时间和截止规则',
    fields: [
      'periodType',
      'overduePolicy',
      'onceOpenTime',
      'onceDeadlineTime',
      'weekGenerateDay',
      'monthGenerateDay',
      'quarterGenerateDay',
      'generateTime',
      'periodOffset',
      'deadlineOffsetDays',
      'deadlineTime',
      'prefillLastPeriod',
    ],
  },
  {
    title: '报送与提醒',
    description: '哪些组织、由谁填报',
    hint: '指定目标组织与填报人，并配置截止前提醒和催办频率',
    fields: ['orgs', 'remindHoursBeforeDeadline', 'remindUrgeCooldownMinutes'],
  },
  {
    title: '确认提交',
    description: '核对采集计划',
    hint: '核对模板、周期和报送范围，确认后保存采集计划',
    fields: [],
  },
];

const {
  Form,
  formApi,
  currentStep,
  steps,
  goToStep,
  resetSteps,
  validateAll,
  busy,
} = useDataforgeWizardForm({
  layout: 'horizontal',
  schema: usePlanFormSchema(),
  steps: planSteps,
  validateStep: (step, values) =>
    step === 2 ? validatePlanOrgs(values as PlanFormValues) : undefined,
});

let openGeneration = 0;
let formActive = true;
const loading = ref(true);
const submitting = ref(false);
const wizardBusy = computed(
  () => busy.value || loading.value || submitting.value,
);
const isCurrent = (generation: number) =>
  formActive &&
  generation === openGeneration &&
  formIdentity === identity.value;

async function handleStepChange(step: number) {
  if (!wizardBusy.value && isCurrent(openGeneration)) await goToStep(step);
}

function closeForm() {
  formActive = false;
  ++openGeneration;
  emit('close');
}

function handleClose() {
  if (!wizardBusy.value) closeForm();
}

async function handleSubmit() {
  if (
    wizardBusy.value ||
    !isCurrent(openGeneration) ||
    currentStep.value !== steps.length - 1
  ) {
    return;
  }
  const generation = openGeneration;
  const isEdit = !!editingId.value;
  submitting.value = true;
  try {
    if (!(await validateAll()) || !isCurrent(generation)) {
      return;
    }
    const values = (await formApi.getValues()) as PlanFormValues;
    if (!isCurrent(generation)) return;
    const orgError = validatePlanOrgs(values);
    if (orgError) {
      message.warning(orgError);
      return;
    }
    const data = toPlanSaveReq(values);
    await (isEdit ? updatePlan(data) : createPlan(data));
    if (isCurrent(generation)) {
      emit('success');
      message.success(
        isEdit
          ? $t('ui.actionMessage.operationSuccess')
          : '创建成功（草稿状态，发布后生效）',
      );
    }
  } finally {
    if (isCurrent(generation)) {
      submitting.value = false;
    }
  }
}

async function initializeForm() {
  const generation = ++openGeneration;
  loading.value = true;
  submitting.value = false;
  resetSteps();
  editingId.value = undefined;
  try {
    await formApi.reset();
    if (!isCurrent(generation)) {
      return;
    }
    const data = props.initialData;
    if (!data?.id) {
      await formApi.setValues({
        ...getDefaultPlanFormValues(),
        categoryId: data?.categoryId,
      });
      return;
    }
    editingId.value = data.id;
    const plan = await getPlan(data.id);
    if (!isCurrent(generation)) {
      return;
    }
    editingId.value = plan.id;
    await formApi.setValues(toPlanFormValues(plan));
  } catch {
    if (isCurrent(generation)) closeForm();
  } finally {
    if (isCurrent(generation)) {
      loading.value = false;
    }
  }
}

onMounted(initializeForm);

watch(identity, () => {
  formActive = false;
  ++openGeneration;
  resetSteps();
  emit('close');
});

onBeforeUnmount(() => {
  formActive = false;
  openGeneration++;
});
</script>

<template>
  <DataforgeFormWizardPage
    :current="currentStep"
    :steps="steps"
    :busy="wizardBusy"
    @change="handleStepChange"
    @confirm="handleSubmit"
    @close="handleClose"
  >
    <Form />
  </DataforgeFormWizardPage>
</template>
