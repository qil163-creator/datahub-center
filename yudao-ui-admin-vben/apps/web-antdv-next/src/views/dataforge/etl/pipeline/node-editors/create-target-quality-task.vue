<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  CreateEtlQualityTaskReceipt,
  CreateEtlQualityTaskRequest,
} from '#/api/governance/quality/etl-task';

import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Checkbox, Input, Modal, Select } from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getEtlEnvironmentBindings } from '#/api/dataforge/etl/environment';
import {
  createEtlQualityTask,
  getEtlQualityTaskCreation,
} from '#/api/governance/quality/etl-task';
import {
  getQualityRulePage,
  getQualityStrategyList,
} from '#/api/governance/quality/rule';
import { getQualityTask } from '#/api/governance/quality/task';
import { parseResourceId } from '#/utils/resource-id';

import { etlEnvironmentKey } from '../environment-context';

const props = defineProps<{ config: Record<string, any> }>();
const emit = defineEmits<{ created: [receipt: CreateEtlQualityTaskReceipt] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canCreate = computed(() =>
  [
    'governance:quality-task:create',
    'governance:quality-task:query',
    'governance:quality-check-object:create',
    'governance:quality-rule:query',
  ].every((code) => hasAccessByCodes([code])),
);
const environment = inject(
  etlEnvironmentKey,
  computed<DataforgeEtlApi.Id | undefined>(() => undefined),
);
const open = ref(false);
const loading = ref(false);
const ruleLoading = ref(false);
const busy = ref(false);
const errorMessage = ref('');
const ruleError = ref('');
const note = ref('');
const target = ref<{
  databaseId: DataforgeEtlApi.Id;
  schemaName: string;
  tableName: string;
}>();
const form = reactive({
  taskName: '',
  activate: true,
  ruleIds: [] as string[],
});
const search = ref('');
const rulePage = ref(1);
const hasMore = ref(false);
const rules = ref<{ label: string; value: string }[]>([]);
const strategies = ref<Set<string>>(new Set());
const pending = ref<CreateEtlQualityTaskRequest>();
const receipt = ref<CreateEtlQualityTaskReceipt>();
const targetSignature = computed(() =>
  JSON.stringify([
    String(props.config.databaseId ?? ''),
    props.config.tableName,
    props.config.schemaName,
    props.config.logicalDatabaseKey,
    String(environment.value ?? ''),
  ]),
);
let pendingSignature = '';
class TargetConfigurationError extends Error {}
let generation = 0;
let ruleSequence = 0;
function close() {
  generation++;
  ruleSequence++;
  open.value = false;
  loading.value = false;
  ruleLoading.value = false;
  busy.value = false;
}
function reset() {
  close();
  pending.value = undefined;
  pendingSignature = '';
  receipt.value = undefined;
  target.value = undefined;
  rules.value = [];
  strategies.value = new Set();
  errorMessage.value = '';
  ruleError.value = '';
  note.value = '';
  Object.assign(form, { taskName: '', activate: true, ruleIds: [] });
  search.value = '';
  rulePage.value = 1;
  hasMore.value = false;
}
async function loadRules(append = false) {
  if (!open.value || !canCreate.value || pending.value) return;
  const seq = ++ruleSequence;
  const page = append ? rulePage.value + 1 : 1;
  ruleLoading.value = true;
  ruleError.value = '';
  if (!append)
    rules.value = rules.value.filter((rule) =>
      form.ruleIds.includes(rule.value),
    );
  try {
    const result = await getQualityRulePage({
      pageNo: page,
      pageSize: 20,
      status: 0,
      ruleName: search.value.trim() || undefined,
    });
    if (seq !== ruleSequence || !open.value) return;
    const options = result.list
      .filter(
        (rule) =>
          rule.status === 0 &&
          !!rule.strategyKey &&
          strategies.value.has(rule.strategyKey),
      )
      .map((rule) => ({
        value: parseResourceId(rule.id),
        label: `${rule.ruleName ?? rule.id}（${rule.strategyKey}）`,
      }))
      .filter((rule): rule is { label: string; value: string } => !!rule.value);
    rules.value = [
      ...new Map(
        [...rules.value, ...options].map((rule) => [rule.value, rule]),
      ).values(),
    ];
    rulePage.value = page;
    hasMore.value = page * 20 < result.total;
  } catch {
    if (seq === ruleSequence)
      ruleError.value = '规则读取失败，请重试；未读取的规则不能用于生成任务。';
  } finally {
    if (seq === ruleSequence) ruleLoading.value = false;
  }
}
async function show() {
  if (!canCreate.value) return;
  open.value = true;
  if (pending.value) {
    target.value = {
      databaseId: pending.value.databaseId,
      schemaName: pending.value.schemaName,
      tableName: pending.value.tableName,
    };
    await lookup();
    return;
  }
  const seq = ++generation;
  loading.value = true;
  errorMessage.value = '';
  note.value = '';
  receipt.value = undefined;
  target.value = undefined;
  form.taskName = 'ETL 目标批次质量验收';
  form.ruleIds = [];
  rules.value = [];
  try {
    const tableName = String(props.config.tableName ?? '').trim();
    if (!tableName)
      throw new TargetConfigurationError('先选择已有目标表，再创建质量任务。');
    const [databases, supported] = await Promise.all([
      getDatabaseSimpleList(),
      getQualityStrategyList(),
    ]);
    if (seq !== generation || !open.value) return;
    strategies.value = new Set(supported.map((item) => item.strategyKey));
    let databaseId = parseResourceId(props.config.databaseId);
    let schemaName = String(props.config.schemaName ?? '').trim();
    if (props.config.logicalDatabaseKey) {
      if (!environment.value)
        throw new TargetConfigurationError(
          '请先选择运行环境，并配置可读取的逻辑连接。',
        );
      const bindings = await getEtlEnvironmentBindings(environment.value);
      if (seq !== generation || !open.value) return;
      const binding = bindings.find(
        (item) => item.logicalKey === props.config.logicalDatabaseKey,
      );
      if (!binding)
        throw new TargetConfigurationError(
          '当前环境未找到该逻辑连接，无法生成质量任务。',
        );
      databaseId = parseResourceId(binding.databaseId);
      schemaName = binding.schemaName;
    }
    const database = databases.find((item) => String(item.id) === databaseId);
    if (!databaseId || !database)
      throw new TargetConfigurationError(
        '无法读取目标数据源，请先选择有权查询的数据源。',
      );
    schemaName ||= database.databaseSchema?.trim() ?? '';
    if (!schemaName)
      throw new TargetConfigurationError(
        '请先在数据源配置中填写默认 Schema，无法推测目标模式。',
      );
    if (database.databaseSchema && database.databaseSchema !== schemaName)
      throw new TargetConfigurationError(
        '当前仅支持连接默认 Schema 内的独立 SQL 质量任务，请调整目标连接。',
      );
    target.value = { databaseId, schemaName, tableName };
    await loadRules();
  } catch (error) {
    if (seq === generation && error instanceof TargetConfigurationError)
      errorMessage.value = error.message;
  } finally {
    if (seq === generation) loading.value = false;
  }
}
async function accept(result: CreateEtlQualityTaskReceipt) {
  if (!pending.value || result.requestId !== pending.value.requestId)
    throw new TargetConfigurationError('RECEIPT_IDENTITY');
  receipt.value = result;
  pending.value = undefined;
  if (pendingSignature !== targetSignature.value) {
    note.value = `原目标质量任务 #${result.taskId} 已创建，当前目标已变化，未回填。请核对原任务后重新打开弹窗处理当前目标。`;
    return;
  }
  note.value = '创建回执已确认并回填，未触发全表执行。正在确认任务当前状态…';
  emit('created', result);
  const seq = generation;
  try {
    const current = await getQualityTask(result.taskId);
    if (seq !== generation || !open.value) return;
    if (String(current.id) !== String(result.taskId))
      throw new Error('TASK_IDENTITY');
    note.value =
      current.taskStatus === 0
        ? '任务已回填；当前任务已上线，未触发全表执行，发布时仍会重新校验。'
        : '任务已回填；当前任务未上线，请在治理模块上线后再发布 ETL。';
  } catch {
    if (seq === generation)
      note.value =
        '创建回执已确认并回填；当前任务状态查询失败，不能据原回执认定当前已上线，请在治理模块核对。';
  }
}
async function submit() {
  if (
    !open.value ||
    !canCreate.value ||
    busy.value ||
    loading.value ||
    ruleLoading.value
  )
    return;
  if (!pending.value) {
    if (
      !target.value ||
      !form.taskName.trim() ||
      form.taskName.trim().length > 100 ||
      form.ruleIds.length === 0 ||
      form.ruleIds.length > 100 ||
      form.ruleIds.some((id) => !rules.value.some((rule) => rule.value === id))
    ) {
      errorMessage.value =
        '请确认目标、填写不超过 100 字的任务名称，并选择 1–100 条已启用的 SQL 策略规则。';
      return;
    }
    pending.value = {
      ...target.value,
      taskName: form.taskName.trim(),
      ruleIds: [...form.ruleIds],
      activate: form.activate,
      requestId: crypto.randomUUID(),
    };
    pendingSignature = targetSignature.value;
  }
  const seq = generation;
  busy.value = true;
  errorMessage.value = '';
  try {
    const result = await createEtlQualityTask(pending.value);
    if (seq === generation && open.value) await accept(result);
  } catch {
    if (seq === generation)
      errorMessage.value =
        '创建结果未确认，请核查同一请求编号；原样重试会复用编号，不会自动创建另一个任务。';
  } finally {
    if (seq === generation) busy.value = false;
  }
}
async function lookup() {
  if (!pending.value || !canCreate.value || busy.value) return;
  const seq = generation;
  busy.value = true;
  errorMessage.value = '';
  note.value = '';
  try {
    const result = await getEtlQualityTaskCreation(pending.value.requestId);
    if (seq !== generation || !open.value) return;
    if (result) await accept(result);
    else
      note.value =
        '尚未取得成功回执，继续保留当前请求编号；可原样重试，不能据此认定原请求未执行。';
  } catch {
    if (seq === generation)
      errorMessage.value =
        '回执查询失败，创建结果仍未确认，请保留当前请求编号。';
  } finally {
    if (seq === generation) busy.value = false;
  }
}
watch(
  targetSignature,
  () => {
    close();
    target.value = undefined;
    receipt.value = undefined;
    rules.value = [];
    note.value = '';
    errorMessage.value = '';
  },
  { flush: 'sync' },
);
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canCreate.value,
  ],
  reset,
  { flush: 'sync' },
);
onBeforeUnmount(close);
</script>

<template>
  <Button v-if="canCreate" size="small" @click="show">
<template #icon><IconifyIcon icon="lucide:file-plus-2" /></template>
    生成独立 SQL 质量任务
  </Button>
  <Modal
    :open="open"
    title="为目标表创建独立质量任务"
    width="880px"
    :footer="null"
    destroy-on-hidden
    @cancel="close"
  >
    <Alert
      type="info"
      class="mb-3"
      message="当前仅支持 PostgreSQL 连接默认 Schema、物理表名为普通小写标识符的目标；含大写、点号或其他特殊字符的物理表暂不支持自动生成。创建检查对象和独立 SQL 质量任务，不根据内联结果虚构检测通过。上线仅允许任务供 ETL 使用，不立即执行全表检测；实际运行仍绑定精确目标批次。"
    />
    <Alert
      v-if="errorMessage"
      type="error"
      :message="errorMessage"
      class="mb-3"
    />
    <Alert v-if="note" type="info" :message="note" class="mb-3" />
    <p v-if="loading">正在解析目标连接和可用策略…</p>
    <template v-if="target">
      <p class="mb-3 break-all">
        目标：{{ target.databaseId }} / {{ target.schemaName }} /
        {{ target.tableName }}
      </p>
      <label class="mb-2 block">任务名称<Input
          v-model:value="form.taskName"
          aria-label="独立质量任务名称"
          :maxlength="100"
          :disabled="busy || !!pending || !!receipt"
      /></label>
      <label class="mb-2 block">规则（1–100 条）<Select
          v-model:value="form.ruleIds"
          aria-label="独立 SQL 检测规则"
          mode="multiple"
          class="w-full"
          :options="rules"
          :loading="ruleLoading"
          :disabled="busy || !!pending || !!receipt"
          show-search
          option-filter-prop="label"
      /></label>
      <div v-if="!pending && !receipt" class="mb-3 flex gap-2">
        <Input
          v-model:value="search"
          aria-label="SQL 规则名称筛选"
          placeholder="按名称搜索已启用规则"
        />
        <Button :disabled="ruleLoading" @click="loadRules(false)">
          搜索规则
        </Button>
        <Button v-if="hasMore" :disabled="ruleLoading" @click="loadRules(true)">
          更多规则
        </Button>
      </div>
      <Checkbox
        v-model:checked="form.activate"
        :disabled="busy || !!pending || !!receipt"
      >
        创建后上线，供 ETL 批次验收使用（不会立即执行）
      </Checkbox>
      <p class="my-2 text-sm text-gray-500">
        仅列出已启用且具有可用策略的规则；提交时服务端还会检查规则配置和目标 SQL
        是否兼容。
      </p>
      <p v-if="pending" class="break-all">
        未确认请求编号：{{
          pending.requestId
        }}。关闭本弹窗后重新打开可继续核查同一请求；离开节点编辑前请保存该编号。
      </p>
      <p v-if="receipt">
        任务 #{{ receipt.taskId }} · 检查对象 #{{ receipt.checkObjectId }} ·
        {{ receipt.replayed ? '读取已有创建回执' : '创建完成' }}
      </p>
      <div class="mt-3 flex gap-2">
        <Button
          v-if="!receipt"
          type="primary"
          :loading="busy"
          :disabled="loading || ruleLoading || !!ruleError"
          @click="submit"
        >
          <template #icon><IconifyIcon :icon="pending ? 'lucide:refresh-cw' : 'lucide:file-plus-2'" /></template>{{ pending ? '原样重试创建' : '创建并回填任务' }}
        </Button>
        <Button v-if="pending" :disabled="busy" @click="lookup">
<template #icon><IconifyIcon icon="lucide:shield-check" /></template>
          核查创建结果
        </Button>
        <Button @click="close">关闭</Button>
      </div>
    </template>
    <Button v-else-if="!loading" @click="show"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重新读取目标</Button>
  </Modal>
</template>
