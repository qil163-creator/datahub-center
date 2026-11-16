<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterMatchApi } from '#/api/governance/mastermatch';

import { computed, reactive, ref, watch } from 'vue';

import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  InputNumber,
  message,
  Select,
} from 'antdv-next';

import { saveMatchRule } from '#/api/governance/mastermatch';

import { algorithmOptions, survivorshipOptions } from '../data';

const props = defineProps<{
  canEdit: boolean;
  model: GovernanceMasterDataApi.Model;
  rule: GovernanceMasterMatchApi.Rule;
}>();
const emit = defineEmits<{ saved: [rule: GovernanceMasterMatchApi.Rule] }>();

const columns = computed(() => props.model.columns ?? []);
/** 主键不参与匹配，也不配置取值规则 */
const candidates = computed(() =>
  columns.value.filter((column) => column.pk !== 1),
);
const columnOptions = computed(() =>
  candidates.value.map((column) => ({
    label: column.commentStr
      ? `${column.commentStr}（${column.name}）`
      : column.name,
    value: column.name!,
  })),
);
function typeOf(name?: string) {
  return columns.value.find((column) => column.name === name)?.fieldType;
}

const draft = reactive({
  fields: [] as GovernanceMasterMatchApi.RuleField[],
  blockingFields: [] as string[],
  threshold: '0.85',
  maxRows: 20_000,
  survivorship: {} as Record<string, GovernanceMasterMatchApi.Survivorship>,
});
const saving = ref(false);

function reset() {
  draft.fields = props.rule.fields.map((field) => ({ ...field }));
  draft.blockingFields = [...props.rule.blockingFields];
  draft.threshold = props.rule.threshold;
  draft.maxRows = props.rule.maxRows;
  draft.survivorship = { ...props.rule.survivorship };
}
watch(() => props.rule, reset, { immediate: true });

function addField() {
  const used = new Set(draft.fields.map((field) => field.name));
  const next = candidates.value.find((column) => !used.has(column.name!));
  if (!next) return;
  draft.fields.push({
    name: next.name!,
    algorithm: algorithmOptions(next.fieldType).at(-1)?.value ?? 'EXACT',
    weight: 1,
  });
}
function changeField(field: GovernanceMasterMatchApi.RuleField, name: string) {
  field.name = name;
  const allowed = algorithmOptions(typeOf(name)).map((option) => option.value);
  if (!allowed.includes(field.algorithm)) field.algorithm = 'EXACT';
}

/** 与服务端一致的前置校验，避免明显错误往返 */
const issue = computed(() => {
  if (draft.fields.length === 0) return '请至少添加一个参与匹配的字段';
  const names = draft.fields.map((field) => field.name);
  if (new Set(names).size !== names.length) return '参与匹配的字段不能重复';
  if (!/^(?:0(?:\.\d{1,4})?|1(?:\.0{1,4})?)$/u.test(draft.threshold.trim()))
    return '相似度阈值为 0.5 至 1、最多 4 位小数';
  if (Number(draft.threshold) < 0.5) return '相似度阈值不能低于 0.5';
  return undefined;
});

async function save() {
  if (issue.value || !props.canEdit) return;
  saving.value = true;
  try {
    const survivorship = Object.fromEntries(
      Object.entries(draft.survivorship).filter(
        ([, value]) => value && value !== 'SURVIVOR',
      ),
    ) as Record<string, GovernanceMasterMatchApi.Survivorship>;
    const saved = await saveMatchRule({
      modelId: props.model.id!,
      fields: draft.fields,
      blockingFields: draft.blockingFields,
      threshold: draft.threshold.trim(),
      maxRows: draft.maxRows,
      survivorship,
    });
    message.success('匹配规则已保存');
    emit('saved', saved);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="match-rule">
    <Alert
      v-if="!rule.configured"
      type="info"
      show-icon
      message="该模型还没有匹配规则。添加参与比较的字段并保存后即可执行相似匹配。"
    />
    <div v-else class="match-rule__meta">
      最近保存：{{ rule.updateTime ? formatDateTime(rule.updateTime) : '-' }}
    </div>

    <h4>参与匹配的字段</h4>
    <p class="match-rule__hint">
      两条记录的相似度 =
      各字段相似度按权重加权平均；只统计两侧都有值的字段，有值字段的权重不足总权重一半时不判定为重复。
    </p>
    <table class="match-rule__table">
      <thead>
        <tr>
          <th>字段</th>
          <th>比较算法</th>
          <th>权重</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(field, index) in draft.fields" :key="index">
          <td>
            <Select
              :value="field.name"
              :options="columnOptions"
              :disabled="!canEdit"
              show-search
              option-filter-prop="label"
              class="w-60"
              @update:value="(value) => changeField(field, String(value))"
            />
          </td>
          <td>
            <Select
              v-model:value="field.algorithm"
              :disabled="!canEdit"
              class="w-56"
              :options="
                algorithmOptions(typeOf(field.name)).map((option) => ({
                  label: `${option.label} · ${option.description}`,
                  value: option.value,
                }))
              "
            />
          </td>
          <td>
            <InputNumber
              v-model:value="field.weight"
              :min="1"
              :max="10"
              :precision="0"
              :disabled="!canEdit"
            />
          </td>
          <td>
            <Button
              v-if="canEdit"
              type="link"
              danger
              @click="draft.fields.splice(index, 1)"
            >
              移除
            </Button>
          </td>
        </tr>
      </tbody>
    </table>
    <Button
      v-if="canEdit"
      class="mt-2"
      :disabled="draft.fields.length >= candidates.length"
      @click="addField"
    >
      添加字段
    </Button>

    <h4>比较范围与阈值</h4>
    <div class="match-rule__grid">
      <label>分块字段</label>
      <div>
        <Select
          v-model:value="draft.blockingFields"
          mode="multiple"
          :options="columnOptions"
          :disabled="!canEdit"
          :max-count="5"
          allow-clear
          placeholder="可选：如所在城市、证件类型"
          class="w-full max-w-xl"
        />
        <p class="match-rule__hint">
          只有分块字段取值相同的记录才互相比较，可大幅减少比较量；分块字段为空的记录不参与比较。不设置时全部记录两两比较，最多比较
          500 万对。
        </p>
      </div>
      <label>相似度阈值</label>
      <div>
        <Input
          v-model:value="draft.threshold"
          :disabled="!canEdit"
          class="w-40"
          placeholder="0.85"
        />
        <p class="match-rule__hint">
          0.5～1，相似度达到阈值的两条记录列为疑似重复；越高越严格。
        </p>
      </div>
      <label>最多读取记录数</label>
      <div>
        <InputNumber
          v-model:value="draft.maxRows"
          :min="2"
          :max="100000"
          :precision="0"
          :disabled="!canEdit"
          class="w-40"
        />
        <p class="match-rule__hint">表内记录超过该数量时不执行匹配。</p>
      </div>
    </div>

    <h4>黄金记录取值规则</h4>
    <p class="match-rule__hint">
      合并时按这里的规则为每个字段预选取值，提交前仍可逐项调整；主键始终取保留记录。保留记录默认是字段最完整的那条。
    </p>
    <table class="match-rule__table">
      <thead>
        <tr>
          <th>字段</th>
          <th>取值规则</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="column in candidates" :key="column.name">
          <td>
            {{ column.commentStr || column.name }}
            <span class="match-rule__name">{{ column.name }}</span>
          </td>
          <td>
            <Select
              :value="draft.survivorship[column.name!] ?? 'SURVIVOR'"
              :options="survivorshipOptions(column.fieldType)"
              :disabled="!canEdit"
              class="w-72"
              @update:value="
                (value) =>
                  (draft.survivorship[column.name!] =
                    value as GovernanceMasterMatchApi.Survivorship)
              "
            />
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="canEdit" class="match-rule__footer">
      <span v-if="issue" class="match-rule__issue">{{ issue }}</span>
      <Button @click="reset">重置</Button>
      <Button
        type="primary"
        :loading="saving"
        :disabled="!!issue"
        @click="save"
      >
        保存规则
      </Button>
    </div>
  </div>
</template>

<style scoped>
.match-rule h4 {
  margin: 20px 0 6px;
  font-weight: 600;
}

.match-rule__meta,
.match-rule__hint {
  margin: 4px 0;
  font-size: 12px;
  color: var(--ant-color-text-tertiary, #8c8c8c);
}

.match-rule__table {
  border-collapse: collapse;
}

.match-rule__table th,
.match-rule__table td {
  padding: 6px 12px 6px 0;
  text-align: left;
  vertical-align: middle;
}

.match-rule__table th {
  font-weight: 500;
  color: var(--ant-color-text-secondary, #595959);
}

.match-rule__name {
  margin-left: 6px;
  font-size: 12px;
  color: var(--ant-color-text-tertiary, #8c8c8c);
}

.match-rule__grid {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 12px 16px;
  align-items: start;
}

.match-rule__grid > label {
  padding-top: 5px;
}

.match-rule__footer {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 16px;
  margin-top: 20px;
  border-top: 1px solid var(--ant-color-split, #f0f0f0);
}

.match-rule__issue {
  margin-right: auto;
  color: var(--ant-color-error, #ff4d4f);
}
</style>
