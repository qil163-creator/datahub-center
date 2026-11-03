<script lang="ts" setup>
import type { QualityRuleApi } from '#/api/governance/quality/rule';

import { reactive, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
} from 'antdv-next';

import { previewQualityRuleSql } from '#/api/governance/quality/rule';

const visible = ref(false);
const loading = ref(false);
const rule = ref<QualityRuleApi.Rule>();
const result = ref<QualityRuleApi.PreviewResp>();

const formData = reactive<{ productType: string; tableName: string }>({
  productType: 'MYSQL',
  tableName: '',
});

function open(row: QualityRuleApi.Rule) {
  rule.value = row;
  result.value = undefined;
  formData.tableName = '';
  visible.value = true;
}

async function handlePreview() {
  if (!formData.tableName.trim()) {
    message.warning('请输入表名');
    return;
  }
  loading.value = true;
  try {
    result.value = await previewQualityRuleSql({
      strategyKey: rule.value?.strategyKey,
      productType: formData.productType,
      tableName: formData.tableName.trim(),
      configJson: rule.value?.paramSchema,
    });
  } finally {
    loading.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :footer="null"
    :title="`SQL 预览 - ${rule?.ruleName ?? ''}`"
    :width="960"
    destroy-on-hidden
  >
    <Form layout="inline">
      <FormItem label="数据库类型">
        <Select
          v-model:value="formData.productType"
          :options="
            ['MYSQL', 'POSTGRESQL', 'ORACLE', 'SQLSERVER', 'DORIS'].map(
              (value) => ({ label: value, value }),
            )
          "
          class="w-36"
        />
      </FormItem>
      <FormItem label="表名">
        <Input
          v-model:value="formData.tableName"
          class="w-48"
          placeholder="请输入检测表名"
        />
      </FormItem>
      <FormItem>
        <Button :loading="loading" type="primary" @click="handlePreview">
          <template #icon><IconifyIcon icon="lucide:code" /></template>
          生成预览
        </Button>
      </FormItem>
    </Form>
    <div class="text-muted-foreground mt-1 text-xs">
      字段与配置取自规则的「规则参数」JSON（column/columns/where 等）
    </div>
    <template v-if="result">
      <div class="mt-3">
        <div class="mb-1 font-semibold">统计 SQL（总数 + 错误数）</div>
        <pre
          class="max-h-32 overflow-auto whitespace-pre-wrap rounded bg-[hsl(var(--accent))] p-2 text-xs"
          >{{ result.countSql }}</pre>
      </div>
      <div class="mt-3">
        <div class="mb-1 font-semibold">错误明细 SQL</div>
        <pre
          class="max-h-32 overflow-auto whitespace-pre-wrap rounded bg-[hsl(var(--accent))] p-2 text-xs"
          >{{ result.errorDataSql }}</pre>
      </div>
    </template>
  </Modal>
</template>
