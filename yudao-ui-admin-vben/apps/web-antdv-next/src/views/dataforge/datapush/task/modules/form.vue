<script lang="ts" setup>
import type { UnifiedTaskFormValues } from '../form-model';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Button, message } from 'antdv-next';

import {
  getDataPushConfig,
  testDataPushConfigConnection,
} from '#/api/dataforge/datapush/config';
import {
  createDataPushTask,
  getDataPushTask,
  previewDataPushTask,
  updateDataPushTask,
} from '#/api/dataforge/datapush/task';
import { $t } from '#/locales';
import { useDataforgeWizardForm } from '#/views/dataforge/common/form-wizard';
import DataforgeFormWizardPage from '#/views/dataforge/common/form-wizard-page.vue';

import { PUSH_MODE, PUSH_TYPE } from '../data';
import {
  getDefaultUnifiedFormValues,
  RECEIVER_FIELDS,
  toUnifiedFormValues,
  toUnifiedSaveReq,
  UNIFIED_TASK_STEPS,
  useUnifiedFormSchema,
} from '../form-model';

defineOptions({ name: 'DataforgeDataPushTaskForm' });

const props = defineProps<{ initialData?: { categoryId?: number | string; id?: number | string } }>();
const emit = defineEmits(['close', 'success']);
const access = useAccessStore();
const user = useUserStore();
const editingId = ref<number | string>();
const previewing = ref(false);
const testing = ref(false);
const loading = ref(true);
const saving = ref(false);
let formActive = true;
let formEpoch = 0;
let previewRequest = 0;
let testRequest = 0;
let hydrating = true;

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
  schema: useUnifiedFormSchema(),
  steps: UNIFIED_TASK_STEPS,
  layout: 'horizontal',
  handleValuesChange: async (values, changedFields) => {
    // 详情回填和初始化不得清空已保存的增量字段。
    if (hydrating || !formActive) return;
    if (changedFields.includes('sourceDatabaseId')) {
      await formApi.setFieldValue('tableName', undefined);
      await formApi.setFieldValue('incrementColumn', undefined);
    }
    if (
      changedFields.includes('tableName') ||
      changedFields.includes('pushType')
    ) {
      await formApi.setFieldValue('incrementColumn', undefined);
    }
    if (
      changedFields.includes('pushMode') &&
      values.pushMode !== PUSH_MODE.INCREMENT
    ) {
      await formApi.setFieldValue('incrementColumn', undefined);
      await formApi.setFieldValue('initStartVal', undefined);
    }
  },
});

const working = computed(
  () =>
    busy.value ||
    loading.value ||
    saving.value ||
    previewing.value ||
    testing.value,
);

function isCurrent(epoch: number) {
  return formActive && epoch === formEpoch;
}

async function handleStepChange(step: number) {
  if (!working.value && formActive) await goToStep(step);
}

async function validateReceiver(epoch: number) {
  const results = await Promise.all(
    ['name', ...RECEIVER_FIELDS].map((field) => formApi.validateField(field)),
  );
  return isCurrent(epoch) && results.every((result) => result.valid);
}

async function handleTestConnection() {
  if (!formActive || working.value || currentStep.value !== 1) return;
  const epoch = formEpoch;
  const request = ++testRequest;
  testing.value = true;
  try {
    if (!(await validateReceiver(epoch))) return;
    const values = (await formApi.getValues()) as UnifiedTaskFormValues;
    if (!isCurrent(epoch) || request !== testRequest) return;
    const data = toUnifiedSaveReq(values).pushConfig!;
    await testDataPushConfigConnection(data);
    if (!isCurrent(epoch) || request !== testRequest) return;
    const current = (await formApi.getValues()) as UnifiedTaskFormValues;
    if (
      !isCurrent(epoch) ||
      request !== testRequest ||
      JSON.stringify(toUnifiedSaveReq(current).pushConfig) !==
        JSON.stringify(data)
    )
      return;
    message.success('接收端连接成功');
  } finally {
    if (epoch === formEpoch && request === testRequest) testing.value = false;
  }
}

async function handlePreview() {
  if (!formActive || working.value) return;
  const epoch = formEpoch;
  const request = ++previewRequest;
  previewing.value = true;
  try {
    if (!(await validateReceiver(epoch))) return;
    const values = (await formApi.getValues()) as UnifiedTaskFormValues;
    if (!isCurrent(epoch) || request !== previewRequest) return;
    if (!values.sourceDatabaseId) {
      message.warning('请先选择源数据源');
      return;
    }
    if (values.pushType === PUSH_TYPE.TABLE && !values.tableName) {
      message.warning('请选择源表');
      return;
    }
    if (values.pushType === PUSH_TYPE.SQL && !values.sqlText?.trim()) {
      message.warning('请填写自定义 SQL');
      return;
    }
    if (values.pushMode === PUSH_MODE.INCREMENT && !values.incrementColumn) {
      message.warning('增量推送请选择增量字段');
      return;
    }
    const data = toUnifiedSaveReq(values);
    const result = await previewDataPushTask({ ...data, limit: 10 });
    if (!isCurrent(epoch) || request !== previewRequest) return;
    const current = (await formApi.getValues()) as UnifiedTaskFormValues;
    if (
      !isCurrent(epoch) ||
      request !== previewRequest ||
      JSON.stringify(toUnifiedSaveReq(current)) !== JSON.stringify(data)
    )
      return;
    message.success(
      result.emptyInterval
        ? '增量区间为空，当前不会推出数据'
        : `已预览 ${result.rowCount ?? 0} 行，未写入接收方`,
    );
  } finally {
    if (epoch === formEpoch && request === previewRequest)
      previewing.value = false;
  }
}

async function handleSubmit() {
  if (!formActive || working.value || currentStep.value !== steps.length - 1)
    return;
  const epoch = formEpoch;
  saving.value = true;
  try {
    if (!(await validateAll()) || !isCurrent(epoch)) return;
    const values = (await formApi.getValues()) as UnifiedTaskFormValues;
    if (!isCurrent(epoch)) return;
    const data = toUnifiedSaveReq(values);
    await (editingId.value
      ? updateDataPushTask(data)
      : createDataPushTask(data));
    if (!isCurrent(epoch)) return;
    message.success($t('ui.actionMessage.operationSuccess'));
    emit('success');
  } finally {
    if (epoch === formEpoch) saving.value = false;
  }
}

// 同一页面载入任务及其接收端；关闭或切换登录会话后丢弃旧响应。
onMounted(async () => {
  const epoch = ++formEpoch;
  loading.value = true;
  resetSteps();
  try {
    await formApi.reset();
    if (!isCurrent(epoch)) return;
    const id = props.initialData?.id;
    if (id === undefined) {
      await formApi.setValues({
        ...getDefaultUnifiedFormValues(),
        categoryId: props.initialData?.categoryId,
      });
      return;
    }
    const task = await getDataPushTask(id);
    if (!isCurrent(epoch)) return;
    if (task.pushConfigId === undefined) {
      message.warning('任务的接收端配置缺失，请检查任务详情');
      invalidateForm();
      emit('close');
      return;
    }
    const config = await getDataPushConfig(task.pushConfigId);
    if (!isCurrent(epoch)) return;
    editingId.value = task.id;
    await formApi.setValues(toUnifiedFormValues(task, config));
  } catch {
    // 详情失败时返回列表，避免空表单覆盖尚未载入的任务。
    if (isCurrent(epoch)) {
      invalidateForm();
      emit('close');
    }
  } finally {
    await nextTick();
    if (epoch === formEpoch) {
      hydrating = false;
      loading.value = false;
    }
  }
});

function invalidateForm() {
  formActive = false;
  ++formEpoch;
  ++previewRequest;
  ++testRequest;
  resetSteps();
}

function handleClose() {
  if (working.value) return;
  invalidateForm();
  emit('close');
}

watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    invalidateForm();
    emit('close');
  },
);

onBeforeUnmount(invalidateForm);
</script>

<template>
  <DataforgeFormWizardPage
    :current="currentStep"
    :steps="steps"
    :busy="working"
    @change="handleStepChange"
    @confirm="handleSubmit"
    @close="handleClose"
  >
    <Form />
    <template #actions>
      <Button
        v-if="currentStep === 1"
        :disabled="working"
        :loading="testing"
        @click="handleTestConnection"
      >
        <template #icon><IconifyIcon icon="lucide:plug-zap" /></template>
        测试连接
      </Button>
      <Button
        v-if="currentStep === 2 || currentStep === 3"
        :disabled="working"
        :loading="previewing"
        @click="handlePreview"
      >
        <template #icon><IconifyIcon icon="lucide:eye" /></template>
        预览前 10 行
      </Button>
    </template>
  </DataforgeFormWizardPage>
</template>
