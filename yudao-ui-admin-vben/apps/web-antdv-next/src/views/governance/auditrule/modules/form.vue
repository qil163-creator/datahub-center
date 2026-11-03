<script lang="ts" setup>
import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';

import { computed, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Select,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createAuditRule,
  getAuditRule,
  getAuditRuleDimensionList,
  getAuditStrategyList,
  updateAuditRule,
} from '#/api/governance/auditrule';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<GovernanceAuditRuleApi.Id>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const identity = computed(() =>
  JSON.stringify([user.userInfo?.userId, access.accessCodes]),
);
const loadRequest = useObjectRequest(() => `${identity.value}/${editId.value}`);
const saveRequest = useObjectRequest(() => `${identity.value}/${editId.value}`);
const visible = ref(false);
const loading = ref(false);
const failed = ref(false);
const saving = ref(false);

const formData = reactive<GovernanceAuditRuleApi.AuditRule>({});

const dimensions = ref<GovernanceAuditRuleApi.Dimension[]>([]);
const dimensionTree = computed(() =>
  dimensions.value
    .toSorted((left, right) => left.sort - right.sort)
    .map((dimension) => ({ ...dimension, disabled: dimension.status !== 0 })),
);
const strategies = ref<GovernanceAuditRuleApi.Strategy[]>([]);

const rules = {
  name: [{ required: true, message: '请输入规则名称' }],
  dimensionId: [{ required: true, message: '请选择质量维度' }],
};

async function open(
  id?: GovernanceAuditRuleApi.Id,
  dimensionId?: GovernanceAuditRuleApi.Id,
) {
  if (!hasAccessByCodes(['governance:audit-rule:query'])) return;
  saveRequest.begin();
  saving.value = false;
  modalApi.unlock();
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  formData.ruleLevel = 1;
  formData.status = 0;
  if (dimensionId !== undefined) {
    formData.dimensionId = dimensionId;
  }
  dimensions.value = [];
  strategies.value = [];
  visible.value = true;
  modalApi.open();
  await load();
}

async function load() {
  const ticket = loadRequest.begin();
  loading.value = true;
  failed.value = false;
  try {
    const [result, strategyList, detail] = await Promise.all([
      getAuditRuleDimensionList(),
      getAuditStrategyList(),
      editId.value === undefined ? undefined : getAuditRule(editId.value),
    ]);
    if (!visible.value || !ticket()) return;
    dimensions.value = result;
    strategies.value = strategyList;
    if (detail) Object.assign(formData, detail);
  } catch {
    if (ticket()) failed.value = true;
  } finally {
    if (ticket()) loading.value = false;
  }
}

/** 选择策略时提示配置示例 */
function currentStrategy() {
  return strategies.value.find(
    (item) => item.strategyKey === formData.strategyKey,
  );
}

async function handleOk() {
  if (!visible.value || loading.value || failed.value || saving.value) return;
  if (
    !hasAccessByCodes([
      editId.value === undefined
        ? 'governance:audit-rule:create'
        : 'governance:audit-rule:update',
    ])
  )
    return;
  const ticket = saveRequest.begin();
  saving.value = true;
  modalApi.lock();
  try {
    await formRef.value?.validate();
    if (!ticket()) return;
    if (
      !dimensions.value.some(
        (dimension) =>
          dimension.status === 0 &&
          String(dimension.id) === String(formData.dimensionId),
      )
    ) {
      message.warning('请选择有效的质量维度');
      return;
    }
    await (editId.value === undefined
      ? createAuditRule({ ...formData })
      : updateAuditRule({ ...formData, id: editId.value }));
    if (!visible.value || !ticket()) return;
    message.success(editId.value ? '修改成功' : '新增成功');
    await modalApi.close();
    emit('saved');
  } finally {
    if (ticket()) {
      saving.value = false;
      modalApi.unlock();
    }
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await handleOk();
  },
  onOpenChange(isOpen) {
    if (isOpen) return;
    visible.value = false;
    loadRequest.begin();
    saveRequest.begin();
    dimensions.value = [];
    strategies.value = [];
    loading.value = false;
    failed.value = false;
    saving.value = false;
    clearReactiveObject(formData as Record<string, unknown>);
    modalApi.unlock();
  },
});

watch(
  [identity, () => access.accessToken, () => access.refreshToken],
  () => {
    if (visible.value) modalApi.close();
  },
  { flush: 'sync' },
);
watch([loading, failed], () =>
  modalApi.setState({ confirmDisabled: loading.value || failed.value }),
);

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '编辑稽查规则' : '新增稽查规则'"
  >
    <Spin v-if="loading" class="mb-3" />
    <Alert
      v-if="failed"
      class="mb-3"
      type="error"
      message="稽查规则或质量维度加载失败"
    >
      <template #action>
        <Button size="small" @click="load">重试</Button>
      </template>
    </Alert>
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :disabled="loading || saving || failed"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="规则名称" name="name">
        <Input v-model:value="formData.name" placeholder="请输入规则名称" />
      </FormItem>
      <FormItem label="质量维度" name="dimensionId">
        <TreeSelect
          v-model:value="formData.dimensionId"
          :field-names="{ label: 'name', value: 'id' }"
          :tree-data="dimensionTree"
          allow-clear
          placeholder="请选择质量维度"
          tree-default-expand-all
        />
      </FormItem>
      <FormItem label="规则级别" name="ruleLevel">
        <Select
          v-model:value="formData.ruleLevel"
          :options="[
            { label: '字段级', value: 1 },
            { label: '表级', value: 2 },
          ]"
        />
      </FormItem>
      <FormItem label="规则编码" name="code">
        <Input
          v-model:value="formData.code"
          placeholder="请输入规则编码（选填）"
        />
      </FormItem>
      <FormItem label="引擎策略" name="strategyKey">
        <Select
          v-model:value="formData.strategyKey"
          :options="
            strategies.map((item) => ({
              label: `${item.strategyName}（${item.strategyKey}）`,
              value: item.strategyKey,
            }))
          "
          allow-clear
          placeholder="选择质量引擎稽查策略（选填）"
          show-search
        />
        <div
          v-if="currentStrategy()?.configExample"
          class="text-muted-foreground mt-1 text-xs"
        >
          配置示例：{{ currentStrategy()?.configExample }}
        </div>
      </FormItem>
      <FormItem label="规则描述" name="description">
        <TextArea
          v-model:value="formData.description"
          :rows="2"
          placeholder="请输入规则描述"
        />
      </FormItem>
      <FormItem label="使用场景" name="useCase">
        <TextArea
          v-model:value="formData.useCase"
          :rows="2"
          placeholder="请输入使用场景"
        />
      </FormItem>
      <FormItem label="示例" name="example">
        <TextArea
          v-model:value="formData.example"
          :rows="3"
          placeholder="请输入规则示例"
        />
      </FormItem>
      <FormItem label="状态" name="status">
        <Select
          v-model:value="formData.status"
          :options="[
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ]"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
