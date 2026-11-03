<script lang="ts" setup>
import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';

import { computed, reactive, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  Modal,
  Select,
  TextArea,
} from 'antdv-next';

import {
  getAuditStrategyList,
  previewAuditRuleSql,
} from '#/api/governance/auditrule';

import { automaticAuditStrategies } from '../../quality/task/modules/audit-rule-parameters';

const visible = ref(false);
const generating = ref(false);
const rule = ref<GovernanceAuditRuleApi.AuditRule>();
const result = ref<GovernanceAuditRuleApi.PreviewResp>();
const requiresLiveSource = computed(() =>
  automaticAuditStrategies.has(rule.value?.strategyKey ?? ''),
);

const formData = reactive<GovernanceAuditRuleApi.PreviewReq>({
  productType: 'MYSQL',
  tableName: '',
});

async function open(row: GovernanceAuditRuleApi.AuditRule) {
  rule.value = row;
  result.value = undefined;
  formData.ruleId = row.id;
  formData.strategyKey = row.strategyKey;
  formData.productType = 'MYSQL';
  formData.tableName = 't_demo';
  formData.column = '';
  formData.columns = '';
  formData.whereClause = '';
  formData.configJson = '';
  visible.value = true;
  if (requiresLiveSource.value) return;
  // 预填该策略的配置示例
  if (row.strategyKey) {
    const strategies = await getAuditStrategyList();
    const strategy = strategies.find(
      (item) => item.strategyKey === row.strategyKey,
    );
    if (strategy?.configExample) {
      formData.configJson = strategy.configExample;
    }
  }
}

async function handleGenerate() {
  if (requiresLiveSource.value) return;
  generating.value = true;
  try {
    result.value = await previewAuditRuleSql({ ...formData });
  } finally {
    generating.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :footer="null"
    :title="`稽查 SQL 预览 - ${rule?.name ?? ''}`"
    :width="960"
    destroy-on-hidden
  >
    <Alert
      class="mb-3"
      :message="
        requiresLiveSource
          ? '此规则必须读取授权数据源的真实字段、主键或结构。请在质量任务中配置该规则后使用抽查。'
          : '按策略生成质量检查 SQL：错误统计（totalCount / errorCount）与错误明细，可直接在目标库执行验证。'
      "
      show-icon
      :type="requiresLiveSource ? 'warning' : 'info'"
    />
    <Form
      v-if="!requiresLiveSource"
      :label-col="{ span: 4 }"
      :model="formData"
      :wrapper-col="{ span: 19 }"
    >
      <FormItem label="数据库类型">
        <Select
          v-model:value="formData.productType"
          :options="[
            { label: 'MySQL', value: 'MYSQL' },
            { label: 'PostgreSQL', value: 'POSTGRESQL' },
          ]"
        />
      </FormItem>
      <FormItem label="表名">
        <Input v-model:value="formData.tableName" placeholder="如 t_user" />
      </FormItem>
      <FormItem label="稽查字段">
        <Input
          v-model:value="formData.column"
          placeholder="单字段规则填写，如 phone"
        />
      </FormItem>
      <FormItem label="多字段">
        <Input
          v-model:value="formData.columns"
          placeholder="组合唯一/字段组等多字段规则，逗号分隔，如 store_id,sku_id"
        />
      </FormItem>
      <FormItem label="过滤条件">
        <Input
          v-model:value="formData.whereClause"
          placeholder="附加 WHERE 条件（选填），如 deleted = 0"
        />
      </FormItem>
      <FormItem label="规则配置">
        <TextArea
          v-model:value="formData.configJson"
          :rows="3"
          placeholder="策略配置 JSON"
        />
      </FormItem>
      <FormItem :wrapper-col="{ offset: 4, span: 19 }">
        <Button :loading="generating" type="primary" @click="handleGenerate">
          <template #icon><IconifyIcon icon="lucide:code" /></template>
          生成 SQL
        </Button>
      </FormItem>
    </Form>

    <template v-if="result">
      <div class="mb-1 text-base font-semibold">错误统计 SQL</div>
      <pre class="audit-preview__sql">{{ result.countSql }}</pre>
      <div class="mb-1 mt-3 text-base font-semibold">错误明细 SQL</div>
      <pre class="audit-preview__sql">{{ result.errorDataSql }}</pre>
    </template>
  </Modal>
</template>

<style scoped>
.audit-preview__sql {
  padding: 10px;
  margin: 0;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted));
  border-radius: 6px;
}
</style>
