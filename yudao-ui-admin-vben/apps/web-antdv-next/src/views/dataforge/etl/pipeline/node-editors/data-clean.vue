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
  AutoComplete,
  Button,
  Checkbox,
  Input,
  RadioGroup,
  Select,
} from 'antdv-next';

import { getCleanRulePage } from '#/api/governance/cleanrule';

import {
  createGovernanceCleanSelection,
  governanceCleanSelectionField,
  governanceCleanSelectionFields,
  governanceCleanUsesAllFields,
  governanceCleanUsesMultipleFields,
  governanceRuleParameters,
  isGovernanceCleanSelectionEditable,
  selectGovernanceCleanRules,
  setGovernanceCleanSelectionField,
  setGovernanceCleanSelectionFields,
} from './data-clean-governance';

import './editor-style.css';

defineOptions({ name: 'EtlNodeDataClean' });

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
  hasAccessByCodes(['governance:clean-rule:query']),
);
const canLoadRules = computed(() => currentOwner.value && canQuery.value);
const MAX_RULE_PAGES = 20;
const RULE_PAGE_SIZE = 100;
let active = true;
let ruleSequence = 0;

const DEDUP_OPTIONS = [
  { label: '全字段去重', value: 'ALL_FIELDS' },
  { label: '按关键字段', value: 'KEY_FIELDS' },
];

const NULL_OPTIONS = [
  { label: '丢弃含空值的行', value: 'REMOVE' },
  { label: '填充默认值', value: 'FILL_DEFAULT' },
];

const MODE_OPTIONS = [
  { label: '本地配置', value: 'LOCAL' },
  { label: '治理清洗规则', value: 'GOVERNANCE' },
];
const cleanRules = ref<GovernanceCleanRuleApi.CleanRule[]>([]);
const cleanRuleLoading = ref(false);
const cleanRuleTruncated = ref(false);
const ruleSearch = ref('');
const selectedGovernanceRule = ref<string>();

const FORMAT_TYPE_OPTIONS = [
  { label: '去除首尾空格', value: 'TRIM' },
  { label: '转大写', value: 'UPPER_CASE' },
  { label: '转小写', value: 'LOWER_CASE' },
  { label: '正则替换', value: 'REGEX_REPLACE' },
];

const fieldSelectOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    label: `${field.name}（${field.dataType ?? '-'}）`,
    value: field.name!,
  })),
);

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

const removeFields = computed<string[]>({
  get: () =>
    props.config.fieldsToRemove
      ? String(props.config.fieldsToRemove).split(',').filter(Boolean)
      : [],
  set: (values) => {
    props.config.fieldsToRemove = values.join(',');
  },
});

const keyFields = computed<string[]>({
  get: () =>
    props.config.keyFields
      ? String(props.config.keyFields).split(',').filter(Boolean)
      : [],
  set: (values) => {
    props.config.keyFields = values.join(',');
  },
});

onMounted(() => {
  const defaults: Record<string, any> = {
    deduplicationStrategy: 'ALL_FIELDS',
    nullHandlingStrategy: 'REMOVE',
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] === null || props.config[key] === undefined) {
      props.config[key] = value;
    }
  });
  if (!Array.isArray(props.config.defaultValues)) {
    props.config.defaultValues = [];
  }
  if (!Array.isArray(props.config.formatRules)) {
    props.config.formatRules = [];
  }
  if (
    props.config.executionMode === null ||
    props.config.executionMode === undefined
  ) {
    props.config.executionMode = 'LOCAL';
  }
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
    // 请求客户端已显示错误，保留空选项。
  } finally {
    if (current()) cleanRuleLoading.value = false;
  }
}

const cleanRuleOptions = computed(() =>
  cleanRules.value.map((rule) => ({
    label: `${rule.name ?? rule.code}（${rule.strategyKey}）`,
    value: String(rule.id),
  })),
);

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
</script>

<template>
  <div class="etl-editor">
    <div class="editor-section">执行方式</div>
    <div class="form-item">
      <RadioGroup
        v-model:value="config.executionMode"
        :options="MODE_OPTIONS"
        option-type="button"
      />
    </div>

    <template v-if="config.executionMode === 'GOVERNANCE'">
      <div class="editor-section">治理清洗规则</div>
      <div class="form-item">
        <p>
          仅可选已启用且执行引擎支持的规则。规则按下方顺序执行；去重和删行规则基于完整输入行集执行。
        </p>
        <Alert
          v-if="!currentOwner"
          type="warning"
          message="身份或访问上下文已变化，请重新打开节点配置。"
        />
        <Alert
          v-else-if="!canQuery"
          type="warning"
          message="没有治理清洗规则查询权限，无法选择或确认治理参数。"
        />
        <div class="flex gap-2">
          <Input
            v-model:value="ruleSearch"
            :disabled="!canLoadRules"
            placeholder="按清洗规则名称搜索"
            @press-enter="loadGovernanceRules"
          />
          <Button
            :disabled="!canLoadRules"
            size="small"
            @click="loadGovernanceRules"
          >
            搜索
          </Button>
          <Button
            :disabled="!canLoadRules"
            size="small"
            @click="loadGovernanceRules"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            刷新
          </Button>
        </div>
        <p v-if="cleanRuleTruncated" class="text-amber-700 text-xs">
          仅加载当前条件下前 2000 条启用规则，请按名称缩小范围后搜索。
        </p>
        <Select
          v-model:value="selectedGovernanceRule"
          :options="cleanRuleOptions"
          :loading="cleanRuleLoading"
          :disabled="!canLoadRules || cleanRuleLoading"
          class="w-full"
          placeholder="选择要加入的启用规则"
          show-search
          option-filter-prop="label"
          @change="addGovernanceRule"
        />
        <div
          v-for="(item, index) in config.governanceRules ?? []"
          :key="index"
          class="editor-row"
        >
          <div
            v-if="isGovernanceCleanSelectionEditable(item)"
            class="flex min-w-0 flex-1 flex-col gap-2"
          >
            <strong>{{ governanceRuleName(item.ruleId) }}</strong>
            <p
              v-if="governanceCleanUsesAllFields(cleanRule(item.ruleId))"
              class="text-muted-foreground text-xs"
            >
              此规则检查整行字段，无需指定目标字段。
            </p>
            <Select
              v-if="governanceCleanUsesMultipleFields(cleanRule(item.ruleId))"
              :value="governanceCleanSelectionFields(item)"
              :options="fieldSelectOptions"
              :disabled="
                !canLoadRules || cleanRuleLoading || !cleanRule(item.ruleId)
              "
              mode="multiple"
              class="w-full"
              placeholder="组合目标字段"
              @change="
                (fields: string[]) =>
                  setGovernanceCleanSelectionFields(item, fields)
              "
            />
            <Select
              v-else-if="!governanceCleanUsesAllFields(cleanRule(item.ruleId))"
              :value="governanceCleanSelectionField(item)"
              :options="fieldSelectOptions"
              :disabled="
                !canLoadRules || cleanRuleLoading || !cleanRule(item.ruleId)
              "
              class="w-full"
              placeholder="目标字段"
              @change="
                (field: string) => setGovernanceCleanSelectionField(item, field)
              "
            />
            <p class="text-muted-foreground text-xs">
              治理参数（只读）：{{
                cleanRule(item.ruleId)
                  ? governanceRuleParameters(cleanRule(item.ruleId)?.config)
                  : '未读取，无法确认当前参数'
              }}
            </p>
            <p class="text-muted-foreground text-xs">
              函数参数由治理规则持久化管理。旧节点参数仅作一致性核验；规则参数变化后需重新发布流程。
            </p>
          </div>
          <p v-else class="flex-1 text-amber-700">
            该清洗规则配置不完整，未自动补写默认值。请删除后重新选择启用规则。
          </p>
          <IconifyIcon
            class="editor-row__remove size-4"
            icon="lucide:trash-2"
            @click="config.governanceRules.splice(Number(index), 1)"
          />
        </div>
      </div>
    </template>

    <template v-else>
      <div class="editor-section">去除字段</div>
      <div class="form-item">
        <Checkbox
          :checked="config.enableFieldRemoval === true"
          @update:checked="(v: boolean) => (config.enableFieldRemoval = v)"
        >
          启用
        </Checkbox>
        <Select
          v-if="config.enableFieldRemoval"
          v-model:value="removeFields"
          :options="fieldSelectOptions"
          mode="tags"
          placeholder="选择要移除的字段"
        />
      </div>

      <div class="editor-section">去重</div>
      <div class="form-item">
        <Checkbox
          :checked="config.enableDeduplication === true"
          @update:checked="(v: boolean) => (config.enableDeduplication = v)"
        >
          启用（保留首条）
        </Checkbox>
        <template v-if="config.enableDeduplication">
          <RadioGroup
            v-model:value="config.deduplicationStrategy"
            :options="DEDUP_OPTIONS"
            option-type="button"
          />
          <Select
            v-if="config.deduplicationStrategy === 'KEY_FIELDS'"
            v-model:value="keyFields"
            :options="fieldSelectOptions"
            mode="tags"
            placeholder="按这些字段判断重复"
          />
        </template>
      </div>

      <div class="editor-section">空值处理</div>
      <div class="form-item">
        <Checkbox
          :checked="config.enableNullHandling === true"
          @update:checked="(v: boolean) => (config.enableNullHandling = v)"
        >
          启用
        </Checkbox>
        <template v-if="config.enableNullHandling">
          <RadioGroup
            v-model:value="config.nullHandlingStrategy"
            :options="NULL_OPTIONS"
            option-type="button"
          />
          <template v-if="config.nullHandlingStrategy === 'FILL_DEFAULT'">
            <div
              v-for="(item, index) in config.defaultValues ?? []"
              :key="index"
              class="editor-row"
            >
              <AutoComplete
                v-model:value="item.fieldName"
                :options="fieldOptions()"
                class="w-44"
                placeholder="字段名"
              />
              <Input
                v-model:value="item.defaultValue"
                class="flex-1"
                placeholder="默认值"
              />
              <IconifyIcon
                class="editor-row__remove size-4"
                icon="lucide:trash-2"
                @click="config.defaultValues.splice(Number(index), 1)"
              />
            </div>
            <Button
              class="self-start"
              size="small"
              type="dashed"
              @click="
                config.defaultValues.push({ fieldName: '', defaultValue: '' })
              "
            >
              <template #icon><IconifyIcon icon="lucide:plus" /></template>
              添加默认值
            </Button>
          </template>
        </template>
      </div>

      <div class="editor-section">格式化</div>
      <div class="form-item">
        <Checkbox
          :checked="config.enableFormatting === true"
          @update:checked="(v: boolean) => (config.enableFormatting = v)"
        >
          启用
        </Checkbox>
        <template v-if="config.enableFormatting">
          <div
            v-for="(rule, index) in config.formatRules ?? []"
            :key="index"
            class="editor-row"
          >
            <AutoComplete
              v-model:value="rule.fieldName"
              :options="fieldOptions()"
              class="w-40"
              placeholder="字段名"
            />
            <Select
              v-model:value="rule.type"
              :options="FORMAT_TYPE_OPTIONS"
              class="w-36"
            />
            <template v-if="rule.type === 'REGEX_REPLACE'">
              <Input
                v-model:value="rule.pattern"
                class="flex-1"
                placeholder="正则"
              />
              <Input
                v-model:value="rule.replacement"
                class="flex-1"
                placeholder="替换为"
              />
            </template>
            <IconifyIcon
              class="editor-row__remove size-4"
              icon="lucide:trash-2"
              @click="config.formatRules.splice(Number(index), 1)"
            />
          </div>
          <Button
            class="self-start"
            size="small"
            type="dashed"
            @click="config.formatRules.push({ fieldName: '', type: 'TRIM' })"
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加规则
          </Button>
        </template>
      </div>
    </template>
  </div>
</template>
