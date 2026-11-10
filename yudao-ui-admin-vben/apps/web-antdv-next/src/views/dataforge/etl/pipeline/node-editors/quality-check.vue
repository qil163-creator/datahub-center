<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
/* eslint-disable vue/no-mutating-props -- 节点编辑器编辑独立配置副本，由设计器统一提交。 */
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, AutoComplete, Input, Select } from 'antdv-next';

import {
  getRuleSetItems,
  getRuleSetSimpleList,
} from '#/api/governance/quality/ruleset';

import './editor-style.css';

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const initialOwner = owner();
const currentOwner = computed(() => owner() === initialOwner);
const canQuery = computed(() =>
  hasAccessByCodes(['governance:quality-rule-set:query']),
);
const canLoadRules = computed(() => currentOwner.value && canQuery.value);
const ruleSets = ref<QualityRuleSetApi.RuleSet[]>([]);
const rules = ref<QualityRuleSetApi.Item[]>([]);
const loading = ref(false);
const rulesLoading = ref(false);
const rulesError = ref('');
let setSeq = 0;
let rulesSeq = 0;
const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({ value: field.name })),
);
const ruleSetOptions = computed(() =>
  ruleSets.value.map((set) => ({
    value: String(set.id),
    label: set.setName ?? String(set.id),
  })),
);
const bindings = computed<{ field: string; ruleId: DataforgeEtlApi.Id }[]>(
  () => (canLoadRules.value ? (props.config.fieldBindings ?? []) : []),
);

async function loadSets() {
  const seq = ++setSeq;
  ruleSets.value = [];
  if (!canLoadRules.value) return;
  loading.value = true;
  try {
    const result = await getRuleSetSimpleList();
    if (seq === setSeq) ruleSets.value = result;
  } catch {
    // 请求客户端已显示错误，保留空选项。
  } finally {
    if (seq === setSeq) loading.value = false;
  }
}

async function loadRules(id?: DataforgeEtlApi.Id) {
  const seq = ++rulesSeq;
  rules.value = [];
  rulesError.value = '';
  rulesLoading.value = false;
  if (!id || !canLoadRules.value) return;
  rulesLoading.value = true;
  try {
    const result = await getRuleSetItems(id);
    if (seq !== rulesSeq) return;
    rules.value = result;
    const previous = bindings.value;
    props.config.fieldBindings = result.map((rule) => ({
      ruleId: String(rule.ruleId),
      field:
        previous.find(
          (binding) => String(binding.ruleId) === String(rule.ruleId),
        )?.field ?? '',
    }));
  } catch {
    if (seq === rulesSeq)
      rulesError.value = '规则明细加载失败，无法确认字段绑定，请重试后提交。';
  } finally {
    if (seq === rulesSeq) rulesLoading.value = false;
  }
}

function selectSet(value: unknown) {
  if (
    !canLoadRules.value ||
    loading.value ||
    !ruleSets.value.some((set) => String(set.id) === value)
  )
    return;
  props.config.ruleSetId = String(value);
}
function ruleLabel(id: DataforgeEtlApi.Id) {
  return (
    rules.value.find((rule) => String(rule.ruleId) === String(id))?.ruleName ??
    `规则 ${id}`
  );
}
watch(
  () => props.config.ruleSetId,
  (id) => {
    void loadRules(id);
  },
  { immediate: true },
);
onMounted(() => {
  props.config.disposition ??= 'ROUTE';
  props.config.statusField ??= '_quality_status';
  props.config.fieldBindings ??= [];
  void loadSets();
});
watch(
  () => [owner(), canQuery.value],
  () => {
    ++setSeq;
    ++rulesSeq;
    ruleSets.value = [];
    rules.value = [];
    loading.value = false;
    rulesLoading.value = false;
    rulesError.value = '';
    if (canLoadRules.value) {
      void loadSets();
      void loadRules(props.config.ruleSetId);
    }
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  ++setSeq;
  ++rulesSeq;
});
</script>

<template>
  <div class="etl-editor">
    <Alert
      v-if="!currentOwner"
      type="warning"
      message="身份或访问上下文已变化，请重新打开节点配置。"
      class="mb-3"
    />
    <Alert
      v-else-if="!canQuery"
      type="warning"
      message="没有治理规则集查询权限，无法确认规则和字段绑定。"
      class="mb-3"
    />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">治理规则集</label>
      <Select
        :value="config.ruleSetId == null ? undefined : String(config.ruleSetId)"
        :options="ruleSetOptions"
        :loading="loading"
        :disabled="!canLoadRules || loading"
        show-search
        option-filter-prop="label"
        placeholder="选择启用的规则集"
        @change="selectSet"
      />
      <span class="form-item__tip">本节点用于行级规则；唯一性、跨行及跨表规则须由批次检测执行，服务端会阻断不支持的规则。</span>
    </div>
    <div v-if="rulesLoading" class="form-item__tip">正在加载规则字段绑定…</div>
    <div
      v-for="binding in bindings"
      :key="String(binding.ruleId)"
      class="form-item"
    >
      <label class="form-item__label form-item__label--required">{{ ruleLabel(binding.ruleId) }} · 输入字段</label>
      <AutoComplete
        v-model:value="binding.field"
        :disabled="rulesLoading || !!rulesError"
        :options="fieldOptions"
        placeholder="选择或输入待检查字段"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">数据处置策略</label>
      <Select
        v-model:value="config.disposition"
        :options="[
          { value: 'ROUTE', label: '按合格 / 警告 / 不合格分流' },
          { value: 'KEEP_TAGGED', label: '全量保留并标记质量状态' },
        ]"
      />
      <span class="form-item__tip">分流时合格、警告、不合格三个端口均须连接输出，不合格端口应连接隔离输出；全量保留时从 main
        输出全部记录。全量保留包含不合格记录，后续查询和服务需显式按质量状态过滤。各分流端口请先连接不同下游节点，需合流时再使用合并组件。切换策略需重新连接输出。两种策略均保留问题证据。</span>
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">质量状态字段</label>
      <Input v-model:value="config.statusField" placeholder="_quality_status" />
    </div>
  </div>
</template>
