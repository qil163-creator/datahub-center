<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { QualityRuleApi } from '#/api/governance/quality/rule';
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';
import type { QualityTaskTemplateApi } from '#/api/governance/quality/template';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getQualityRuleSimpleList } from '#/api/governance/quality/rule';
import { getRuleSetSimpleList } from '#/api/governance/quality/ruleset';
import {
  createTaskTemplate,
  getTaskTemplate,
  updateTaskTemplate,
} from '#/api/governance/quality/template';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number>();

const formData = reactive<QualityTaskTemplateApi.Template>({});
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const ruleSets = ref<QualityRuleSetApi.RuleSet[]>([]);
const rulesList = ref<QualityRuleApi.Rule[]>([]);

const rules = {
  templateName: [{ required: true, message: '请输入模板名称' }],
  datasourceConnId: [{ required: true, message: '请选择数据源' }],
};

async function open(id?: number) {
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  formData.ruleSourceType = 'RULE_SET';
  formData.tableScopeType = 'DATASOURCE';
  formData.scheduleType = 'MANUAL';
  formData.syncMode = 'FULL';
  formData.taskLimit = 5000;
  formData.status = 0;
  modalApi.open();
  const [dbs, sets, ruleList] = await Promise.all([
    getDatabaseSimpleList(),
    getRuleSetSimpleList(),
    getQualityRuleSimpleList(),
  ]);
  databases.value = dbs;
  ruleSets.value = sets;
  rulesList.value = ruleList;
  if (id) {
    Object.assign(formData, await getTaskTemplate(id));
  }
}

async function handleOk() {
  await formRef.value?.validate();
  modalApi.lock();
  try {
    await (editId.value
      ? updateTaskTemplate({ ...formData, id: editId.value })
      : createTaskTemplate(formData));
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
    :title="editId ? '编辑任务模板' : '新增任务模板'"
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
      <FormItem label="模板名称" name="templateName">
        <Input
          v-model:value="formData.templateName"
          placeholder="请输入模板名称"
        />
      </FormItem>
      <FormItem label="数据源" name="datasourceConnId">
        <Select
          v-model:value="formData.datasourceConnId"
          :options="
            databases.map((db) => ({
              label: `${db.name}（${db.databaseTypeName ?? ''}）`,
              value: db.id,
            }))
          "
          placeholder="请选择数据源"
          show-search
        />
      </FormItem>
      <FormItem label="规则来源" name="ruleSourceType">
        <Select
          v-model:value="formData.ruleSourceType"
          :options="[
            { label: '规则集', value: 'RULE_SET' },
            { label: '指定规则', value: 'SPECIFY_RULE' },
          ]"
        />
        <div class="text-muted-foreground mt-1 text-xs">
          两者都填以规则集为准
        </div>
      </FormItem>
      <FormItem
        v-if="formData.ruleSourceType === 'RULE_SET'"
        label="规则集"
        name="ruleSetId"
      >
        <Select
          v-model:value="formData.ruleSetId"
          :options="
            ruleSets.map((set) => ({ label: set.setName, value: set.id }))
          "
          placeholder="请选择规则集"
        />
      </FormItem>
      <FormItem v-else label="指定规则" name="ruleIds">
        <Select
          :options="
            rulesList.map((rule) => ({ label: rule.ruleName, value: rule.id }))
          "
          :value="
            formData.ruleIds
              ? formData.ruleIds.split(',').map((v) => Number(v))
              : []
          "
          mode="multiple"
          placeholder="请选择规则（可多选）"
          show-search
          @change="
            (value: any) => (formData.ruleIds = (value as number[]).join(','))
          "
        />
      </FormItem>
      <FormItem label="表范围" name="tableScopeType">
        <Select
          v-model:value="formData.tableScopeType"
          :options="[
            { label: '整个数据源', value: 'DATASOURCE' },
            { label: '表名匹配', value: 'TABLE_MATCH' },
            { label: '指定表', value: 'SPECIFY_TABLE' },
            { label: '按分层', value: 'LAYER' },
          ]"
        />
      </FormItem>
      <FormItem
        v-if="formData.tableScopeType === 'TABLE_MATCH'"
        label="表名匹配"
        name="tableMatch"
      >
        <Input
          v-model:value="formData.tableMatch"
          placeholder="如 ods_ 开头的表填 ods_"
        />
      </FormItem>
      <FormItem
        v-if="formData.tableScopeType === 'SPECIFY_TABLE'"
        label="指定表ID"
        name="tableIds"
      >
        <Input
          v-model:value="formData.tableIds"
          placeholder="元数据表ID，逗号分隔"
        />
      </FormItem>
      <FormItem
        v-if="formData.tableScopeType === 'LAYER'"
        label="分层"
        name="layer"
      >
        <Select
          v-model:value="formData.layer"
          :options="[
            { label: '贴源层', value: 'ODS' },
            { label: '明细层', value: 'DWD' },
            { label: '汇总层', value: 'DWS' },
            { label: '应用层', value: 'ADS' },
          ]"
        />
      </FormItem>
      <FormItem label="字段选择策略" name="fieldSelectStrategy">
        <Select
          v-model:value="formData.fieldSelectStrategy"
          :options="[
            { label: '已挂标准的字段（推荐）', value: 'STANDARD_BOUND' },
            { label: '全部字段', value: 'ALL' },
          ]"
          allow-clear
          placeholder="请选择字段选择策略"
        />
      </FormItem>
      <FormItem label="调度类型" name="scheduleType">
        <Select
          v-model:value="formData.scheduleType"
          :options="[
            { label: '手动', value: 'MANUAL' },
            { label: '定时', value: 'TIMER' },
          ]"
        />
      </FormItem>
      <FormItem
        v-if="formData.scheduleType === 'TIMER'"
        label="Cron 表达式"
        name="cronExpression"
      >
        <Input
          v-model:value="formData.cronExpression"
          placeholder="如 0 0 2 * * ?"
        />
      </FormItem>
      <FormItem label="任务数上限" name="taskLimit">
        <InputNumber
          v-model:value="formData.taskLimit"
          :max="10000"
          :min="1"
          class="w-40"
        />
      </FormItem>
      <FormItem label="机构标识字段" name="orgField">
        <Input
          v-model:value="formData.orgField"
          placeholder="如 org_code（选填）"
        />
      </FormItem>
      <FormItem label="描述" name="description">
        <TextArea
          v-model:value="formData.description"
          :rows="2"
          placeholder="请输入描述"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
