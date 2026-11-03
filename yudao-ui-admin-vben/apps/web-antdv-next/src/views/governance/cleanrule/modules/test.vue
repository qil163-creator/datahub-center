<script lang="ts" setup>
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import { reactive, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Modal,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  getCleanStrategyList,
  testCleanRule,
} from '#/api/governance/cleanrule';

const visible = ref(false);
const testing = ref(false);
const rule = ref<GovernanceCleanRuleApi.CleanRule>();
const result = ref<GovernanceCleanRuleApi.TestResp>();

const formData = reactive<GovernanceCleanRuleApi.TestReq>({
  configJson: '',
  rowsJson: '',
});

const DEFAULT_ROWS = `[
  { "name": " 张三 ", "phone": "+86-138 1234 5678", "amount": "12.3" },
  { "name": "李四", "phone": "13900001111", "amount": "100" }
]`;

async function open(row: GovernanceCleanRuleApi.CleanRule) {
  rule.value = row;
  result.value = undefined;
  formData.ruleId = row.id;
  formData.strategyKey = row.strategyKey;
  formData.configJson = '';
  formData.rowsJson = DEFAULT_ROWS;
  visible.value = true;
  if (row.strategyKey) {
    const strategies = await getCleanStrategyList();
    const strategy = strategies.find(
      (item) => item.strategyKey === row.strategyKey,
    );
    if (strategy?.configExample) {
      formData.configJson = strategy.configExample;
    }
  }
}

async function handleTest() {
  testing.value = true;
  try {
    result.value = await testCleanRule({ ...formData });
  } finally {
    testing.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :footer="null"
    :title="`清洗规则测试 - ${rule?.name ?? ''}`"
    :width="800"
    destroy-on-hidden
  >
    <Alert
      class="mb-3"
      message="输入样例数据与规则配置，在线执行质量引擎清洗函数并查看清洗结果。"
      show-icon
      type="info"
    />
    <Form
      :label-col="{ span: 4 }"
      :model="formData"
      :wrapper-col="{ span: 19 }"
    >
      <FormItem label="规则配置">
        <TextArea
          v-model:value="formData.configJson"
          :rows="3"
          placeholder="策略配置 JSON，如 {&quot;columns&quot;: [&quot;name&quot;], &quot;handleType&quot;: 1}"
        />
      </FormItem>
      <FormItem label="样例数据">
        <TextArea
          v-model:value="formData.rowsJson"
          :rows="6"
          placeholder="JSON 数组，每个元素为一行数据"
        />
      </FormItem>
      <FormItem :wrapper-col="{ offset: 4, span: 19 }">
        <Button :loading="testing" type="primary" @click="handleTest">
          <template #icon><IconifyIcon icon="lucide:play" /></template>
          执行测试
        </Button>
      </FormItem>
    </Form>

    <template v-if="result">
      <div class="mb-2 flex items-center gap-2">
        <span class="text-base font-semibold">清洗结果</span>
        <Tag color="default">清洗前 {{ result.beforeCount }} 行</Tag>
        <Tag color="success">清洗后 {{ result.afterCount }} 行</Tag>
      </div>
      <pre class="clean-test__result">{{
        JSON.stringify(result.rows, null, 2)
      }}</pre>
    </template>
  </Modal>
</template>

<style scoped>
.clean-test__result {
  max-height: 320px;
  padding: 10px;
  margin: 0;
  overflow: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted));
  border-radius: 6px;
}
</style>
