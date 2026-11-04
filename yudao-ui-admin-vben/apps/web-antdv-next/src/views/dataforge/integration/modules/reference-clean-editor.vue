<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  TextArea,
} from 'antdv-next';

import { getCleanRulePage } from '#/api/governance/cleanrule';

import {
  createGovernanceCleanSelection,
  governanceCleanSelectionField,
  governanceCleanSelectionFields,
  governanceCleanUsesAllFields,
  governanceCleanUsesMultipleFields,
  isGovernanceCleanSelectionEditable,
  selectGovernanceCleanRules,
  setGovernanceCleanSelectionField,
  setGovernanceCleanSelectionFields,
} from '../../etl/pipeline/node-editors/data-clean-governance';

defineOptions({ name: 'IntegrationReferenceClean' });

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
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
  hasAccessByCodes(['governance:clean-rule:query']),
);
const canLoadRules = computed(() => !props.disabled && currentOwner.value && canQuery.value);
const MAX_RULE_PAGES = 20;
const RULE_PAGE_SIZE = 100;
let active = true;
let ruleSequence = 0;

const cleanRules = ref<GovernanceCleanRuleApi.CleanRule[]>([]);
const cleanRuleLoadFailed = ref(false);
const cleanRuleLoading = ref(false);
const cleanRuleTruncated = ref(false);
const ruleSearch = ref('');
const selectedGovernanceRule = ref<string>();
const pickerOpen = ref(false);
const category = ref('');
const categories = computed(() => [...new Set(cleanRules.value.map((rule) => rule.categoryName || '未分类'))]);
const pickerRules = computed(() => cleanRules.value.filter((rule) => !category.value || (rule.categoryName || '未分类')===category.value));
function openPicker() {
  if (!canLoadRules.value) return;
  props.config.executionMode='GOVERNANCE';
  pickerOpen.value=true;
  void loadGovernanceRules();
}
function removeRule(index: number) { if (!props.disabled) props.config.governanceRules=props.config.governanceRules.filter((_: unknown, position: number) => position!==index); }
function setWhere(value: unknown) { if (!props.disabled) props.config.whereCondition=value; }

const fieldSelectOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    label: `${field.name}（${field.dataType ?? '-'}）`,
    value: field.name!,
  })),
);

onMounted(() => {
  if (props.disabled) return;
  if (!Array.isArray(props.config.governanceRules))
    props.config.governanceRules = [];
});

watch(
  () => [props.config.executionMode, canLoadRules.value],
  ([mode]) => {
    invalidateRules();
    if (mode === 'GOVERNANCE' && canLoadRules.value) void loadGovernanceRules();
  },
  { immediate: true, flush: 'sync' },
);
watch(
  owner,
  () => {
    invalidateRules();
    ruleSearch.value = '';
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  active = false;
  invalidateRules();
});

function invalidateRules() {
  ++ruleSequence;
  cleanRules.value = [];
  cleanRuleLoading.value = false;
  cleanRuleLoadFailed.value = false;
  cleanRuleTruncated.value = false;
  selectedGovernanceRule.value = undefined;
}

async function loadGovernanceRules() {
  invalidateRules();
  if (
    !active ||
    !canLoadRules.value ||
    props.config.executionMode !== 'GOVERNANCE'
  )
    return;
  const sequence = ruleSequence;
  const context = owner();
  const keyword = ruleSearch.value.trim() || undefined;
  const current = () =>
    active &&
    sequence === ruleSequence &&
    context === owner() &&
    canLoadRules.value &&
    props.config.executionMode === 'GOVERNANCE';
  cleanRuleLoading.value = true;
  try {
    const rows: GovernanceCleanRuleApi.CleanRule[] = [];
    for (let pageNo = 1; pageNo <= MAX_RULE_PAGES; pageNo += 1) {
      const result = await getCleanRulePage({
        pageNo,
        pageSize: RULE_PAGE_SIZE,
        status: 0,
        name: keyword,
      });
      if (!current()) return;
      rows.push(...(result.list ?? []));
      if (
        rows.length >= (result.total ?? 0) ||
        (result.list ?? []).length < RULE_PAGE_SIZE
      )
        break;
      if (pageNo === MAX_RULE_PAGES) cleanRuleTruncated.value = true;
    }
    if (!current()) return;
    cleanRules.value = selectGovernanceCleanRules(rows);
  } catch {
    if (current()) cleanRuleLoadFailed.value = true;
  } finally {
    if (current()) cleanRuleLoading.value = false;
  }
}



function addGovernanceRule(ruleId?: string) {
  if (!ruleId || !active || !canLoadRules.value || cleanRuleLoading.value)
    return;
  const rule = cleanRules.value.find((item) => String(item.id) === ruleId);
  if (
    !rule ||
    (props.config.governanceRules as any[]).some(
      (item) => String(item.ruleId) === ruleId,
    )
  ) {
    selectedGovernanceRule.value = undefined;
    return;
  }
  props.config.governanceRules.push(
    createGovernanceCleanSelection(rule, props.inputFields?.[0]?.name ?? ''),
  );
  selectedGovernanceRule.value = undefined;
}

function governanceRuleName(ruleId: number | string) {
  const rule = cleanRules.value.find(
    (item) => String(item.id) === String(ruleId),
  );
  return rule
    ? `${rule.name ?? rule.code}（${rule.strategyKey}）`
    : `规则 ${ruleId}（未读取、已停用或无权访问）`;
}

function cleanRule(ruleId: number | string) {
  return cleanRules.value.find((rule) => String(rule.id) === String(ruleId));
}
defineExpose({openRulePicker:openPicker});
</script>

<template>
  <div class="qi-reference-clean">
    <div class="qi-reference-form-row"><label>where条件</label><div><TextArea :value="config.whereCondition" :disabled="disabled" :rows="3" placeholder="请输入where条件" @update:value="setWhere" /><p class="qi-reference-help">支持受限字段比较与布尔组合；不支持子查询、函数或多语句。</p></div></div>
    <div class="qi-reference-section-header qi-reference-table-heading"><h3>规则设置</h3><Button class="qi-reference-button" :disabled="!canLoadRules" @click="openPicker"><IconifyIcon icon="lucide:plus" />新增规则</Button></div>
    <Alert v-if="!currentOwner" type="warning" message="身份或访问上下文已变化，请重新打开节点配置。" />
    <Alert v-else-if="!canQuery" type="warning" message="没有治理清洗规则查询权限，无法选择或确认治理参数。" />
    <Alert v-if="cleanRuleLoadFailed" type="error" message="规则列表加载失败，请重试。" />
    <div class="qi-reference-fields-scroll">
<table class="qi-reference-fields-table">
<thead><tr><th>序号</th><th>清洗名称</th><th>清洗字段</th><th>清洗规则</th><th>规则描述</th><th>维度</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="(item,index) in config.governanceRules ?? []" :key="index">
<td>{{ Number(index) + 1 }}</td><td>{{ governanceRuleName(item.ruleId) }}</td><td>
        <span v-if="governanceCleanUsesAllFields(cleanRule(item.ruleId))">全部字段</span>
        <Select v-else-if="governanceCleanUsesMultipleFields(cleanRule(item.ruleId))" :value="governanceCleanSelectionFields(item)" :options="fieldSelectOptions" mode="multiple" :disabled="!canLoadRules || !cleanRule(item.ruleId) || !isGovernanceCleanSelectionEditable(item)" @update:value="(value) => setGovernanceCleanSelectionFields(item,value as string[])" />
        <Select v-else :value="governanceCleanSelectionField(item)" :options="fieldSelectOptions" :disabled="!canLoadRules || !cleanRule(item.ruleId) || !isGovernanceCleanSelectionEditable(item)" @update:value="(value) => setGovernanceCleanSelectionField(item,String(value ?? ''))" />
      </td><td>{{ cleanRule(item.ruleId)?.strategyKey ?? '-' }}</td><td>{{ cleanRule(item.ruleId)?.description ?? '-' }}</td><td>{{ cleanRule(item.ruleId)?.categoryName ?? '-' }}</td><td>{{ cleanRule(item.ruleId)?.status === 0 ? '启用' : '未读取' }}</td><td><Button type="link" danger :disabled="disabled" @click="removeRule(Number(index))">删除</Button></td>
</tr>
      <tr v-if="!config.governanceRules?.length"><td colspan="8" class="qi-reference-empty">暂无记录</td></tr>
    </tbody>
</table>
</div>
    <Modal v-model:open="pickerOpen" title="清洗规则" :width="1200" :footer="null">
      <div class="qi-clean-rule-picker"><aside><button type="button" :class="{'is-active':!category}" @click="category = ''">全部规则（{{ cleanRules.length }}）</button><button v-for="name in categories" :key="name" type="button" :class="{'is-active':category === name}" @click="category = name">{{ name }}</button></aside><main><div class="qi-reference-file-actions"><Input v-model:value="ruleSearch" :disabled="!canLoadRules" placeholder="请输入清洗规则名称" @press-enter="loadGovernanceRules" /><Button :disabled="!canLoadRules" :loading="cleanRuleLoading" @click="loadGovernanceRules">查询</Button></div><p v-if="cleanRuleTruncated" class="qi-reference-notice">仅加载当前条件下前2000条启用规则，请缩小搜索范围。</p><div class="qi-clean-rule-cards"><button v-for="rule in pickerRules" :key="String(rule.id)" type="button" :disabled="disabled || config.governanceRules?.some((item: any) => String(item.ruleId) === String(rule.id))" @click="addGovernanceRule(String(rule.id))"><strong>{{ rule.name || rule.code }}</strong><p>{{ rule.description || rule.useCase || rule.strategyKey }}</p><small>{{ rule.categoryName }}</small></button></div><p v-if="!cleanRuleLoading && !pickerRules.length" class="qi-reference-empty">暂无记录</p></main></div>
    </Modal>
  </div>
</template>
<style scoped>
.qi-clean-rule-picker {
  display:grid;
  grid-template-columns:155px minmax(0,1fr);
  gap:20px;
  min-height:400px;
  }

.qi-clean-rule-picker aside {
  display:flex;
  flex-direction:column;
  gap:4px;
  padding-right:12px;
  border-right:1px solid #e4e7ed;
  }

.qi-clean-rule-picker aside button {
  padding:12px 8px;
  text-align:left;
  cursor:pointer;
  background:transparent;
  border:0;
  }

.qi-clean-rule-picker aside button.is-active {
  color:#2f62ff;
  background:#f0f4ff;
  }

.qi-clean-rule-cards {
  display:grid;
  grid-template-columns:repeat(3,minmax(0,1fr));
  gap:12px;
  margin-top:16px;
  }

.qi-clean-rule-cards button {
  min-height:130px;
  padding:16px;
  color:#606266;
  text-align:left;
  cursor:pointer;
  background:#fff;
  border:1px solid #dcdfe6;
  border-radius:4px;
  }

.qi-clean-rule-cards button:hover {
  border-color:#2f62ff;
  }

.qi-clean-rule-cards button:disabled {
  cursor:default;
  opacity:.5;
  }

.qi-clean-rule-cards strong {
  font-size:16px;
  color:#303133;
  }

.qi-clean-rule-cards p {
  margin:8px 0;
  font-size:12px;
  }

.qi-clean-rule-cards small {
  color:#909399;
  }
</style>
