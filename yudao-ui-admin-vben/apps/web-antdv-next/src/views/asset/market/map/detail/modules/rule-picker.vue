<script lang="ts" setup>
import type { QualityRuleApi } from '#/api/governance/quality/rule';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Empty, Input, Spin, Tag, Tree } from 'antdv-next';

import { getQualityRuleSimpleList } from '#/api/governance/quality/rule';
import { useObjectRequest } from '#/hooks/use-object-request';

defineOptions({ name: 'AssetQualityRulePicker' });

const emit = defineEmits<{
  (e: 'select', rule: QualityRuleApi.Rule): void;
}>();

const QUALITY_DIM_LABEL: Record<string, string> = {
  COMPLETENESS: '完整性',
  UNIQUENESS: '唯一性',
  NORMALIZATION: '规范性',
  ACCURACY: '准确性',
  TIMELINESS: '及时性',
  CONSISTENCY: '一致性',
};

const loading = ref(false);
const rules = ref<QualityRuleApi.Rule[]>([]);
const selectedDimension = ref<string>();
const keyword = ref('');
const opened = ref(false);
const { hasAccessByCodes } = useAccess();
const requests = useObjectRequest(
  () => opened.value,
  () => {
    rules.value = [];
    loading.value = false;
  },
);

const dimensionTree = computed(() => {
  const dims = [
    ...new Set(rules.value.map((rule) => rule.qualityDim).filter(Boolean)),
  ] as string[];
  return [
    {
      key: 'ALL',
      title: '质量维度',
      count: rules.value.length,
      children: dims.map((dim) => ({
        key: dim,
        title: QUALITY_DIM_LABEL[dim] ?? dim,
        count: rules.value.filter((rule) => rule.qualityDim === dim).length,
      })),
    },
  ];
});

const visibleRules = computed(() => {
  const text = keyword.value.trim();
  return rules.value.filter(
    (rule) =>
      (!selectedDimension.value ||
        selectedDimension.value === 'ALL' ||
        rule.qualityDim === selectedDimension.value) &&
      (!text ||
        rule.ruleName?.includes(text) ||
        rule.description?.includes(text) ||
        rule.ruleCode?.includes(text)),
  );
});

async function load() {
  if (!opened.value || !hasAccessByCodes(['governance:quality-rule:query']))
    return;
  const current = requests.begin();
  loading.value = true;
  try {
    const value = (await getQualityRuleSimpleList()) ?? [];
    if (current()) rules.value = value;
  } finally {
    if (current()) loading.value = false;
  }
}

function handleSelect(rule: QualityRuleApi.Rule) {
  if (!opened.value || !hasAccessByCodes(['governance:quality-rule:query']))
    return;
  emit('select', rule);
  modalApi.close();
}

const [Modal, modalApi] = useVbenModal({
  class: 'w-[960px]',
  showCancelButton: true,
  showConfirmButton: false,
  async onOpenChange(isOpen: boolean) {
    opened.value = isOpen;
    requests.begin();
    rules.value = [];
    loading.value = false;
    if (isOpen) {
      selectedDimension.value = undefined;
      keyword.value = '';
      await load();
    }
  },
});
</script>

<template>
  <Modal
    :centered="false"
    title="新增质量规则"
    class="!top-[60px] !w-[960px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
  >
    <Spin :spinning="loading">
      <div class="rule-picker">
        <aside class="rule-picker__aside">
          <Tree
            :default-expand-all="true"
            :selected-keys="selectedDimension ? [selectedDimension] : ['ALL']"
            :tree-data="dimensionTree"
            block-node
            @select="
              (keys: any[]) => (selectedDimension = keys[0] || undefined)
            "
          >
            <template #titleRender="node">
              <span class="inline-flex items-center gap-1">
                <IconifyIcon
                  :icon="
                    node.key === 'ALL'
                      ? 'lucide:list-checks'
                      : 'lucide:badge-check'
                  "
                />
                {{ node.title }}
                <span class="text-muted-foreground text-xs">({{ node.count }})</span>
              </span>
            </template>
          </Tree>
        </aside>
        <main class="rule-picker__main">
          <Input
            v-model:value="keyword"
            allow-clear
            class="mb-3"
            placeholder="搜索规则名称、编码或描述"
            size="small"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <div v-if="visibleRules.length > 0" class="rule-picker__grid">
            <div
              v-for="rule in visibleRules"
              :key="rule.id"
              class="rule-card"
              @click="handleSelect(rule)"
            >
              <IconifyIcon class="rule-card__icon" icon="lucide:file-check-2" />
              <div class="rule-card__name">{{ rule.ruleName }}</div>
              <div class="rule-card__desc">
                {{ rule.description || '暂无描述' }}
              </div>
              <div class="rule-card__meta">
                <Tag :bordered="false" color="blue">
                  {{
                    QUALITY_DIM_LABEL[rule.qualityDim ?? ''] ||
                    rule.qualityDim ||
                    '未分类'
                  }}
                </Tag>
                <Tag
                  :bordered="false"
                  :color="rule.checkLevel === 'TABLE' ? 'gold' : 'green'"
                >
                  {{ rule.checkLevel === 'TABLE' ? '表级' : '字段级' }}
                </Tag>
              </div>
            </div>
          </div>
          <Empty
            v-else
            class="py-16"
            description="当前维度下暂无启用的质量规则"
          />
        </main>
      </div>
    </Spin>
  </Modal>
</template>

<style scoped>
.rule-picker {
  display: flex;
  gap: 12px;
  min-height: 460px;
}

.rule-picker__aside {
  flex: 0 0 200px;
  padding: 8px;
  border-right: 1px solid hsl(var(--border));
}

.rule-picker__main {
  flex: 1;
  min-width: 0;
  max-height: 560px;
  padding-right: 4px;
  overflow: auto;
}

.rule-picker__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.rule-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  padding: 16px 12px;
  text-align: center;
  cursor: pointer;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.rule-card:hover {
  border-color: hsl(var(--primary));
  box-shadow: 0 4px 12px rgb(37 99 235 / 12%);
}

.rule-card__icon {
  font-size: 34px;
  color: hsl(var(--primary));
}

.rule-card__name {
  font-weight: 600;
}

.rule-card__desc {
  display: -webkit-box;
  min-height: 34px;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  line-height: 17px;
  color: hsl(var(--muted-foreground));
  -webkit-box-orient: vertical;
}

.rule-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  justify-content: center;
}

.rule-card__meta :deep(.ant-tag) {
  margin: 0;
}
</style>
