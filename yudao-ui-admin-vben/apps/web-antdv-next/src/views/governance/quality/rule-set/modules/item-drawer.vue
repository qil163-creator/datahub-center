<script lang="ts" setup>
import type { QualityRuleApi } from '#/api/governance/quality/rule';
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Drawer,
  Input,
  InputNumber,
  message,
  Select,
  Table,
} from 'antdv-next';

import { getQualityRuleSimpleList } from '#/api/governance/quality/rule';
import {
  getRuleSetItems,
  getRuleSetSimpleList,
  saveRuleSetItems,
} from '#/api/governance/quality/ruleset';

const emit = defineEmits<{ saved: [] }>();

const visible = ref(false);
const saving = ref(false);
const ruleSet = ref<QualityRuleSetApi.RuleSet>();
const allRules = ref<QualityRuleApi.Rule[]>([]);
const selectedItems = ref<QualityRuleSetApi.Item[]>([]);
const keyword = ref('');
const otherRuleSets = ref<QualityRuleSetApi.RuleSet[]>([]);
const mergeRuleSetId = ref<number>();

const availableRules = computed(() => {
  const selectedIds = new Set(selectedItems.value.map((item) => item.ruleId));
  const kw = keyword.value.trim();
  return allRules.value.filter(
    (rule) =>
      !selectedIds.has(rule.id!) && (!kw || rule.ruleName?.includes(kw)),
  );
});

async function open(row: QualityRuleSetApi.RuleSet) {
  ruleSet.value = row;
  mergeRuleSetId.value = undefined;
  visible.value = true;
  const [rules, items, ruleSets] = await Promise.all([
    getQualityRuleSimpleList(),
    getRuleSetItems(row.id!),
    getRuleSetSimpleList(),
  ]);
  allRules.value = rules;
  selectedItems.value = items;
  otherRuleSets.value = ruleSets.filter((set) => set.id !== row.id);
}

/** 合并另一规则集：把其明细（含权重）追加到已选列表，重复规则跳过 */
async function handleMergeRuleSet() {
  if (!mergeRuleSetId.value) {
    message.warning('请选择要合并的规则集');
    return;
  }
  const items = await getRuleSetItems(mergeRuleSetId.value);
  const selectedIds = new Set(selectedItems.value.map((item) => item.ruleId));
  let added = 0;
  for (const item of items) {
    if (!selectedIds.has(item.ruleId)) {
      selectedItems.value.push({ ...item, id: undefined });
      added++;
    }
  }
  message.success(`合并完成，新增 ${added} 条规则`);
  mergeRuleSetId.value = undefined;
}

function addRule(rule: QualityRuleApi.Rule) {
  selectedItems.value.push({
    ruleId: rule.id!,
    ruleName: rule.ruleName,
    ruleType: rule.ruleType,
    qualityDim: rule.qualityDim,
    weight: 1,
  });
}

function removeRule(index: number) {
  selectedItems.value.splice(index, 1);
}

async function handleSave() {
  if (selectedItems.value.length === 0) {
    message.warning('规则集至少需要一个规则');
    return;
  }
  saving.value = true;
  try {
    await saveRuleSetItems({
      ruleSetId: ruleSet.value!.id!,
      items: selectedItems.value.map((item) => ({
        ruleId: item.ruleId,
        weight: item.weight ?? 1,
      })),
    });
    message.success('保存成功');
    visible.value = false;
    emit('saved');
  } finally {
    saving.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Drawer
    v-model:open="visible"
    :title="`规则管理 - ${ruleSet?.setName ?? ''}`"
    :width="960"
    destroy-on-hidden
  >
    <div class="flex h-full gap-3">
      <!-- 可选规则 -->
      <div class="flex-1 overflow-auto rounded border p-2">
        <div class="mb-2 flex items-center justify-between">
          <span class="font-semibold">可选规则</span>
          <Input
            v-model:value="keyword"
            allow-clear
            class="w-40"
            placeholder="搜索规则名称"
            size="small"
          />
        </div>
        <Table
          :columns="[
            { title: '规则名称', dataIndex: 'ruleName', key: 'ruleName' },
            {
              title: '类型',
              dataIndex: 'ruleType',
              key: 'ruleType',
              width: 120,
            },
            { title: '', key: 'add', width: 100, fixed: 'right' as const },
          ]"
          :data-source="availableRules"
          :scroll="{ x: 'max-content' }"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'add'">
              <Button size="small" type="link" @click="addRule(record as any)">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>
                添加
              </Button>
            </template>
          </template>
        </Table>
      </div>
      <!-- 已选规则 + 权重 -->
      <div class="flex-1 overflow-auto rounded border p-2">
        <div class="mb-2 flex items-center justify-between">
          <span class="font-semibold"
            >已选规则（{{ selectedItems.length }}）</span
          >
          <div class="flex items-center gap-1">
            <Select
              v-model:value="mergeRuleSetId"
              :options="
                otherRuleSets.map((set) => ({
                  label: set.setName,
                  value: set.id,
                }))
              "
              allow-clear
              class="w-40"
              placeholder="合并其他规则集"
              size="small"
            />
            <Button size="small" @click="handleMergeRuleSet"
              ><template #icon><IconifyIcon icon="lucide:git-merge" /></template
              >合并</Button
            >
          </div>
        </div>
        <Table
          :columns="[
            { title: '规则名称', dataIndex: 'ruleName', key: 'ruleName' },
            {
              title: '类型',
              dataIndex: 'ruleType',
              key: 'ruleType',
              width: 110,
            },
            { title: '权重', key: 'weight', width: 110 },
            { title: '', key: 'remove', width: 100, fixed: 'right' as const },
          ]"
          :data-source="selectedItems"
          :scroll="{ x: 'max-content' }"
          :pagination="false"
          row-key="ruleId"
          size="small"
        >
          <template #bodyCell="{ column, index }">
            <template v-if="column.key === 'weight'">
              <InputNumber
                v-model:value="selectedItems[index]!.weight"
                :min="0"
                :precision="2"
                :step="0.1"
                size="small"
              />
            </template>
            <template v-else-if="column.key === 'remove'">
              <Button
                danger
                size="small"
                type="link"
                @click="removeRule(index)"
              >
                <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
                移除
              </Button>
            </template>
          </template>
        </Table>
      </div>
    </div>
    <template #footer>
      <Button @click="visible = false">取消</Button>
      <Button :loading="saving" type="primary" @click="handleSave">保存</Button>
    </template>
  </Drawer>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
