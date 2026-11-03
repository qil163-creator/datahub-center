<script lang="ts" setup>
import type { ConfigFormValues } from '../data';

import type { DataforgeWizardStep } from '#/views/dataforge/common/form-wizard';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Button, message } from 'antdv-next';

import {
  createDataPushConfig,
  getDataPushConfig,
  testDataPushConfigConnection,
  updateDataPushConfig,
} from '#/api/dataforge/datapush/config';
import { $t } from '#/locales';
import { useDataforgeWizardForm } from '#/views/dataforge/common/form-wizard';
import DataforgeFormWizardPage from '#/views/dataforge/common/form-wizard-page.vue';

import {
  getDefaultFormValues,
  toFormValues,
  toSaveReq,
  useFormSchema,
} from '../data';

defineOptions({ name: 'DataforgeDataPushConfigForm' });

const props = defineProps<{ initialData?: { id?: number | string } }>();
const emit = defineEmits(['close', 'success']);

const access = useAccessStore();
const user = useUserStore();
const editingId = ref<number | string>();
const testing = ref(false);
const loading = ref(false);
const saving = ref(false);
let formActive = true;
let formEpoch = 0;
let testRequest = 0;

const wizardSteps: DataforgeWizardStep[] = [
  {
    title: '配置基础信息',
    description: '配置名称与推送通道',
    hint: '给推送配置起个名字，选择通道类型和所属单位',
    fields: ['name', 'orgId', 'channelType', 'description'],
  },
  {
    title: '通道配置',
    description: '连接接收方并设置传输规则',
    hint: '填写当前通道的连接信息、认证和传输选项，可测试连接后继续',
    fields: [
      'httpUrl',
      'httpTimeoutMs',
      'httpGzip',
      'httpHeaders',
      'httpParams',
      'httpAdvanced',
      'httpRetryTimes',
      'httpRetryIntervalMs',
      'httpTruststorePath',
      'httpTruststorePassword',
      'httpKeystorePath',
      'httpKeystorePassword',
      'dbDatabaseId',
      'dbTargetTableName',
      'dbTargetDrop',
      'dbSyncExist',
      'dbChangeDataSync',
      'mqType',
      'kafkaBootstrapServers',
      'kafkaTopic',
      'kafkaSecurityProtocol',
      'kafkaAcks',
      'kafkaSaslMechanism',
      'kafkaSaslUsername',
      'kafkaSaslPassword',
      'kafkaTruststorePath',
      'kafkaTruststorePassword',
      'kafkaKeystorePath',
      'kafkaKeystorePassword',
      'kafkaDisableHostnameVerification',
      'rabbitHost',
      'rabbitPort',
      'rabbitUsername',
      'rabbitPassword',
      'rabbitVirtualHost',
      'rabbitExchange',
      'rabbitRoutingKey',
      'rabbitUseSsl',
      'rabbitTruststorePath',
      'rabbitTruststorePassword',
      'rabbitKeystorePath',
      'rabbitKeystorePassword',
    ],
  },
  {
    title: '确认提交',
    description: '核对推送配置',
    hint: '核对通道、接收方和传输规则，确认后保存推送配置',
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
  schema: useFormSchema(),
  steps: wizardSteps,
  layout: 'horizontal',
});

const working = computed(
  () => busy.value || loading.value || saving.value || testing.value,
);

function isCurrent(epoch: number) {
  return formActive && epoch === formEpoch;
}

async function handleStepChange(step: number) {
  if (!working.value && formActive) await goToStep(step);
}

async function handleTestConnection() {
  if (working.value || currentStep.value !== 1) return;
  const epoch = formEpoch;
  const request = ++testRequest;
  testing.value = true;
  try {
    if (!(await validateAll()) || !isCurrent(epoch)) return;
    const values = (await formApi.getValues()) as ConfigFormValues;
    if (!isCurrent(epoch) || request !== testRequest) return;
    const data = toSaveReq(values);
    await testDataPushConfigConnection(data);
    if (!isCurrent(epoch) || request !== testRequest) return;
    const current = (await formApi.getValues()) as ConfigFormValues;
    if (
      !isCurrent(epoch) ||
      request !== testRequest ||
      JSON.stringify(toSaveReq(current)) !== JSON.stringify(data)
    )
      return;
    message.success('通道连通');
  } finally {
    if (epoch === formEpoch && request === testRequest) testing.value = false;
  }
}

async function handleSubmit() {
  if (!formActive || working.value || currentStep.value !== steps.length - 1)
    return;
  const epoch = formEpoch;
  saving.value = true;
  try {
    if (!(await validateAll()) || !isCurrent(epoch)) return;
    const values = (await formApi.getValues()) as ConfigFormValues;
    if (!isCurrent(epoch)) return;
    const data = toSaveReq(values);
    await (editingId.value
      ? updateDataPushConfig(data)
      : createDataPushConfig(data));
    if (!isCurrent(epoch)) return;
    message.success($t('ui.actionMessage.operationSuccess'));
    emit('success');
  } finally {
    if (epoch === formEpoch) saving.value = false;
  }
}

// 页面向导按挂载周期初始化；旧详情和连接测试响应不得写回关闭后的表单。
onMounted(async () => {
  const epoch = ++formEpoch;
  ++testRequest;
  loading.value = true;
  resetSteps();
  try {
    await formApi.reset();
    if (!isCurrent(epoch)) return;
    const data = props.initialData;
    if (data?.id === undefined) {
      await formApi.setValues(getDefaultFormValues());
      return;
    }
    editingId.value = data.id;
    const detail = await getDataPushConfig(data.id);
    if (!isCurrent(epoch)) return;
    editingId.value = detail.id;
    await formApi.setValues(toFormValues(detail));
  } catch {
    // 初始化失败返回列表，避免空表单继续更新尚未载入的配置。
    if (isCurrent(epoch)) {
      invalidateForm();
      emit('close');
    }
  } finally {
    if (epoch === formEpoch) loading.value = false;
  }
});

function invalidateForm() {
  formActive = false;
  ++formEpoch;
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
        <template #icon>
          <IconifyIcon icon="lucide:plug-zap" />
        </template>
        测试连接
      </Button>
    </template>
  </DataforgeFormWizardPage>
</template>
