<script lang="ts" setup>
import type { QualityRuleApi } from '#/api/governance/quality/rule';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Form,
  FormItem,
  Input,
  message,
  Select,
  Switch,
  TextArea,
} from 'antdv-next';

import {
  createQualityRule,
  getQualityRule,
  getQualityStrategyList,
  updateQualityRule,
} from '#/api/governance/quality/rule';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number>();

const formData = reactive<QualityRuleApi.Rule>({});
const strategies = ref<QualityRuleApi.Strategy[]>([]);

const rules = {
  ruleName: [{ required: true, message: '请输入规则名称' }],
  qualityDim: [{ required: true, message: '请选择质量维度' }],
  ruleType: [{ required: true, message: '请选择规则类型' }],
};

const DIM_OPTIONS = [
  { label: '完整性', value: 'COMPLETENESS' },
  { label: '唯一性', value: 'UNIQUENESS' },
  { label: '规范性', value: 'NORMALIZATION' },
  { label: '准确性', value: 'ACCURACY' },
  { label: '及时性', value: 'TIMELINESS' },
  { label: '一致性', value: 'CONSISTENCY' },
];

const RULE_TYPE_OPTIONS = [
  ['NOT_NULL', '非空校验'],
  ['UNIQUE', '唯一校验'],
  ['RANGE', '范围校验'],
  ['REGEX', '正则格式校验'],
  ['ENUM', '枚举值校验'],
  ['LENGTH', '长度校验'],
  ['PRECISION', '数值精度校验'],
  ['TIME_ORDER', '时间顺序校验'],
  ['GROUP_COMPLETENESS', '字段组完整性'],
  ['COMPOSITE_UNIQUE', '组合唯一校验'],
  ['CROSS_TABLE', '跨表一致性'],
  ['FOREIGN_KEY', '外键校验'],
  ['TIMELINESS', '及时性校验'],
  ['PLACEHOLDER', '占位值识别'],
  ['CUSTOM', '自定义SQL'],
].map(([value, label]) => ({ label: `${label}（${value}）`, value }));

async function open(id?: number, prefill?: QualityRuleApi.Rule) {
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  formData.status = 0;
  formData.severity = 'MID';
  formData.checkLevel = 'FIELD';
  formData.collectIssueDetail = 1;
  if (!id && prefill) {
    // 探查推荐带入：只预填，保存仍需用户确认。
    Object.assign(formData, prefill);
  }
  modalApi.open();
  strategies.value = await getQualityStrategyList();
  if (id) {
    Object.assign(formData, await getQualityRule(id));
  }
}

function currentStrategy() {
  return strategies.value.find(
    (item) => item.strategyKey === formData.strategyKey,
  );
}

async function handleOk() {
  await formRef.value?.validate();
  modalApi.lock();
  try {
    await (editId.value
      ? updateQualityRule({ ...formData, id: editId.value })
      : createQualityRule(formData));
    message.success(editId.value ? '修改成功' : '新增成功');
    await modalApi.close();
    emit('saved');
  } finally {
    modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await handleOk();
  },
});

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '编辑质量规则' : '新增质量规则'"
  >
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="规则名称" name="ruleName">
        <Input v-model:value="formData.ruleName" placeholder="请输入规则名称" />
      </FormItem>
      <FormItem label="规则编码" name="ruleCode">
        <Input
          v-model:value="formData.ruleCode"
          placeholder="请输入规则编码（选填）"
        />
      </FormItem>
      <FormItem label="质量维度" name="qualityDim">
        <Select
          v-model:value="formData.qualityDim"
          :options="DIM_OPTIONS"
          placeholder="请选择质量维度"
        />
      </FormItem>
      <FormItem label="规则类型" name="ruleType">
        <Select
          v-model:value="formData.ruleType"
          :options="RULE_TYPE_OPTIONS"
          placeholder="请选择规则类型"
          show-search
        />
      </FormItem>
      <FormItem label="检查级别" name="checkLevel">
        <Select
          v-model:value="formData.checkLevel"
          :options="[
            { label: '字段级', value: 'FIELD' },
            { label: '表级', value: 'TABLE' },
            { label: '跨表级', value: 'CROSS_TABLE' },
          ]"
        />
      </FormItem>
      <FormItem label="严重程度" name="severity">
        <Select
          v-model:value="formData.severity"
          :options="[
            { label: '严重', value: 'CRITICAL' },
            { label: '高', value: 'HIGH' },
            { label: '中', value: 'MID' },
            { label: '低', value: 'LOW' },
          ]"
        />
      </FormItem>
      <FormItem label="适用分层" name="applyLayer">
        <Select
          v-model:value="formData.applyLayer"
          :options="[
            { label: '贴源层', value: '贴源' },
            { label: '明细层', value: '明细' },
            { label: '汇总层', value: '汇总' },
            { label: '应用层', value: '应用' },
          ]"
          allow-clear
          placeholder="留空表示不限层"
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
          placeholder="选择质量引擎策略（选填）"
          show-search
        />
        <div
          v-if="currentStrategy()?.configExample"
          class="text-muted-foreground mt-1 text-xs"
        >
          配置示例：{{ currentStrategy()?.configExample }}
        </div>
      </FormItem>
      <FormItem label="规则参数" name="paramSchema">
        <TextArea
          v-model:value="formData.paramSchema"
          :rows="3"
          placeholder="JSON 参数，如 {&quot;column&quot;: &quot;id_card&quot;, &quot;regex&quot;: &quot;^\\d{17}[\\dXx]$&quot;}"
        />
        <div class="text-muted-foreground mt-1 text-xs">
          保留键：column/columns/where；容差阈值：toleranceType（PASS_RATE
          按通过率 / FAIL_COUNT 按失败数）+ passRateLimit（通过率下限%）或
          failCountLimit（失败数上限）， 不配置则失败数&gt;0 即未通过
        </div>
      </FormItem>
      <FormItem label="规则内容" name="ruleContent">
        <TextArea
          v-model:value="formData.ruleContent"
          :rows="3"
          placeholder="自定义 SQL（SELECT 问题行）或正则表达式（选填）"
        />
      </FormItem>
      <FormItem label="采集问题明细" name="collectIssueDetail">
        <Switch
          :checked="formData.collectIssueDetail === 1"
          checked-children="是"
          un-checked-children="否"
          @change="
            (checked: any) => (formData.collectIssueDetail = checked ? 1 : 0)
          "
        />
        <div class="text-muted-foreground mt-1 text-xs">
          检出后留存可回源的定位记录（定位SQL），不复制源数据
        </div>
      </FormItem>
      <FormItem label="描述" name="description">
        <TextArea
          v-model:value="formData.description"
          :rows="2"
          placeholder="请输入描述"
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
