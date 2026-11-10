<script lang="ts" setup>
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Form,
  FormItem,
  Input,
  message,
  Select,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getCategoryCleanRuleList } from '#/api/governance/categorycleanrule';
import {
  createCleanRule,
  getCleanRule,
  getCleanStrategyList,
  updateCleanRule,
} from '#/api/governance/cleanrule';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../common/form-layout';
import {
  cleanRuleConfigFromExample,
  parseCleanRuleConfig,
} from './clean-rule-config';
import { cleanParameterIssues } from './clean-rule-parameters';
import ParameterEditor from './parameter-editor.vue';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number | string>();

const formData = reactive<GovernanceCleanRuleApi.CleanRule>({});
const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
const configChanged = ref(false);
const access = useAccessStore();
const user = useUserStore();
const identity = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
let sequence = 0;
let shown = false;

const categoryTree = ref<any[]>([]);
const strategies = ref<GovernanceCleanRuleApi.Strategy[]>([]);

const rules = {
  name: [{ required: true, message: '请输入规则名称' }],
  categoryId: [{ required: true, message: '请选择清洗规则类目' }],
};

function reset() {
  ++sequence;
  clearReactiveObject(formData as Record<string, unknown>);
  categoryTree.value = [];
  strategies.value = [];
  loading.value = false;
  saving.value = false;
  loadError.value = '';
  configChanged.value = false;
  editId.value = undefined;
}

async function open(id?: number | string, categoryId?: number | string) {
  reset();
  const request = sequence;
  const scope = identity();
  const current = () => shown && request === sequence && scope === identity();
  editId.value = id;
  formData.ruleLevel = 1;
  formData.status = 0;
  formData.config = {};
  if (categoryId !== undefined) {
    formData.categoryId = String(categoryId);
  }
  shown = true;
  loading.value = true;
  modalApi.open();
  try {
    const [categories, strategyList, detail] = await Promise.all([
      getCategoryCleanRuleList({}),
      getCleanStrategyList(),
      id === undefined ? undefined : getCleanRule(id),
    ]);
    if (!current()) return;
    categoryTree.value = handleTree(
      categories.map((item) => ({
        ...item,
        id: String(item.id),
        parentId:
          item.parentId === undefined ? undefined : String(item.parentId),
      })),
    );
    strategies.value = strategyList;
    if (detail) {
      Object.assign(formData, detail);
      if (detail.categoryId !== undefined)
        formData.categoryId = String(detail.categoryId);
    }
  } catch {
    if (current())
      loadError.value = '清洗规则或参数选项加载失败，请关闭后重试。';
  } finally {
    if (current()) loading.value = false;
  }
}

const currentStrategy = computed(() =>
  strategies.value.find((item) => item.strategyKey === formData.strategyKey),
);
const strategyOptions = computed(() => {
  const options = strategies.value.map((item) => ({
    label: `${item.strategyName}（${item.strategyKey}）`,
    value: item.strategyKey,
  }));
  if (formData.strategyKey && !currentStrategy.value)
    options.push({
      label: `历史或未知策略（${formData.strategyKey}）`,
      value: formData.strategyKey,
    });
  return options;
});

function handleStrategyChange(strategyKey?: string) {
  const selected = strategies.value.find(
    (item) => item.strategyKey === strategyKey,
  );
  formData.strategyKey = strategyKey;
  formData.config = parseCleanRuleConfig(
    cleanRuleConfigFromExample(selected?.configExample),
  );
  configChanged.value = true;
}

async function handleOk() {
  if (!shown || loading.value || saving.value || loadError.value) return;
  const request = sequence;
  const scope = identity();
  const current = () => shown && request === sequence && scope === identity();
  saving.value = true;
  modalApi.lock();
  try {
    await formRef.value?.validate();
    if (!current()) return;
    const payload = { ...formData };
    // An unchanged edit omits config, retaining the server's legacy-client preservation contract.
    if (editId.value !== undefined && !configChanged.value)
      delete payload.config;
    else {
      const errors = cleanParameterIssues(
        formData.strategyKey,
        formData.config ?? {},
      );
      if (errors.length > 0) {
        message.error(errors[0]);
        return;
      }
    }
    await (editId.value === undefined
      ? createCleanRule(payload)
      : updateCleanRule({ ...payload, id: editId.value }));
    if (!current()) return;
    message.success(editId.value === undefined ? '新增成功' : '修改成功');
    await modalApi.close();
    emit('saved');
  } finally {
    if (request === sequence) saving.value = false;
    if (request === sequence || !shown) modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen: boolean) {
    shown = isOpen;
    if (!isOpen) reset();
  },
  async onConfirm() {
    await handleOk();
  },
});
watch(
  identity,
  () => {
    shown = false;
    reset();
    void modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  shown = false;
  reset();
});

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '编辑清洗规则' : '新增清洗规则'"
  >
    <Alert
      v-if="loadError && (!/(?:加载|读取|获取).*失败/.test(loadError) || /保存|冲突|未确认/.test(loadError))"
      class="mb-3"
      :message="loadError"
      show-icon
      type="error"
    />
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :disabled="loading || saving || !!loadError"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="规则名称" name="name">
        <Input v-model:value="formData.name" placeholder="请输入规则名称" />
      </FormItem>
      <FormItem label="清洗规则类目" name="categoryId">
        <TreeSelect
          v-model:value="formData.categoryId"
          :field-names="{
            children: 'children',
            label: 'categoryName',
            value: 'id',
          }"
          :tree-data="categoryTree as any"
          allow-clear
          placeholder="请选择清洗规则类目"
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
          :options="strategyOptions"
          allow-clear
          placeholder="选择质量引擎清洗策略（选填）"
          show-search
          @change="handleStrategyChange"
        />
        <ParameterEditor
          v-if="formData.strategyKey"
          :disabled="loading || saving || !!loadError"
          :model-value="formData.config"
          :strategy-key="formData.strategyKey"
          :supported="!!currentStrategy"
          @update:model-value="
            (config) => {
              formData.config = config;
              configChanged = true;
            }
          "
        />
        <div
          v-if="formData.strategyKey"
          class="text-muted-foreground mt-1 text-xs"
        >
          参数保存在治理规则中；ETL
          节点只选择目标字段。后端会校验函数参数类型、范围及未知字段。
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
