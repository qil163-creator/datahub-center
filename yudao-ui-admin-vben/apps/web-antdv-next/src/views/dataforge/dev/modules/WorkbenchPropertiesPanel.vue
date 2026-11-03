<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import { Button, Input, InputNumber, Select } from 'antdv-next';

import {
  getDevProgramType,
  getDevRuntimeProfiles,
  getDevTaskEngine,
  isNativeProgramTask,
} from '#/api/dataforge/dev';

import {
  defaultExecutionConfig,
  PARAM_TYPE_OPTIONS,
  PRIORITY_OPTIONS,
} from '../list-model';

interface DatabaseOption {
  label: string;
  productId?: string;
  value: DataforgeDevApi.Id;
}
type ProgramNumber =
  | 'driverCores'
  | 'driverMemoryMb'
  | 'executorCores'
  | 'executorMemoryMb'
  | 'jobManagerMemoryMb'
  | 'numExecutors'
  | 'parallelism'
  | 'taskManagerCount'
  | 'taskManagerMemoryMb'
  | 'taskManagerSlots';
type PropertiesPatch = Pick<
  DataforgeDevApi.Task,
  | 'databaseId'
  | 'dbType'
  | 'executionConfig'
  | 'maxRows'
  | 'programConfig'
  | 'timeoutSeconds'
>;
const props = withDefaults(
  defineProps<{
    databases?: DatabaseOption[];
    readonly?: boolean;
    runtimeProfiles?: DataforgeDevApi.RuntimeProfile[];
    saving?: boolean;
    task: DataforgeDevApi.Task;
    workloadMode?: DataforgeDevApi.WorkloadMode;
  }>(),
  { databases: () => [], runtimeProfiles: undefined, workloadMode: undefined },
);
const emit = defineEmits<{
  cancel: [];
  collapse: [];
  save: [patch: PropertiesPatch];
}>();
const access = useAccessStore();
const draft = ref<DataforgeDevApi.Task>({});
const memories = ref<Record<string, string>>({});
const error = ref('');
const profiles = ref<DataforgeDevApi.RuntimeProfile[]>([]);
const profilesLoading = ref(false);
const profilesFailed = ref(false);
let generation = 0;
const engine = computed(() => getDevTaskEngine(props.task.taskType));
const native = computed(() => isNativeProgramTask(draft.value));
const programType = computed(() => getDevProgramType(props.task.taskType));
const execution = computed(() => draft.value.executionConfig!);
const program = computed(() => draft.value.programConfig);
const profile = computed(() =>
  profiles.value.find(
    (item) => item.code === program.value?.runtimeProfileCode,
  ),
);
const localProfile = computed(() => profile.value?.deployMode === 'LOCAL');
const profileOptions = computed(() => {
  const options: { disabled?: boolean; label: string; value: string }[] =
    profiles.value
      .filter(
        (item) =>
          item.engine === engine.value &&
          props.task.taskType &&
          item.allowedTaskTypes.includes(props.task.taskType),
      )
      .map((item) => ({ label: item.name, value: item.code }));
  if (
    program.value?.runtimeProfileCode &&
    !options.some((item) => item.value === program.value?.runtimeProfileCode)
  )
    options.push({
      value: program.value.runtimeProfileCode,
      label: `不可用环境：${program.value.runtimeProfileCode}`,
      disabled: true,
    });
  return options;
});
const fields = computed<
  {
    default: number;
    key: ProgramNumber;
    label: string;
    max: number;
    memory?: boolean;
  }[]
>(() =>
  engine.value === 'FLINK'
    ? [
        {
          key: 'jobManagerMemoryMb',
          label: 'JobManager内存数',
          default: 1024,
          max: 1_048_576,
          memory: true,
        },
        {
          key: 'taskManagerMemoryMb',
          label: 'TaskManager内存数',
          default: 1024,
          max: 1_048_576,
          memory: true,
        },
        { key: 'taskManagerSlots', label: 'Slot数量', default: 1, max: 1024 },
        {
          key: 'taskManagerCount',
          label: 'TaskManager数量',
          default: 1,
          max: 10_000,
        },
        { key: 'parallelism', label: '并行度', default: 1, max: 65_536 },
      ]
    : [
        { key: 'driverCores', label: 'Driver核心数', default: 1, max: 1024 },
        {
          key: 'driverMemoryMb',
          label: 'Driver内存数',
          default: 512,
          max: 1_048_576,
          memory: true,
        },
        { key: 'numExecutors', label: 'Executor数', default: 1, max: 10_000 },
        {
          key: 'executorMemoryMb',
          label: 'Executor内存数',
          default: 1024,
          max: 1_048_576,
          memory: true,
        },
        {
          key: 'executorCores',
          label: 'Executor核心数',
          default: 1,
          max: 1024,
        },
      ],
);
const productLabels: Record<string, string> = {
  MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL',
  DORIS: 'Doris',
  ORACLE: 'Oracle',
  SQLSERVER: 'SQL Server',
  HIVE: 'HiveSQL',
  DM: 'DM8',
  DM8: 'DM8',
  DAMENG: 'DM8',
};
const connectionLabel = computed(() => {
  if (!native.value)
    return productLabels[draft.value.dbType ?? ''] ?? draft.value.dbType ?? 'SQL';
  if (engine.value === 'FLINK')
    return `Flink${program.value?.workloadMode === 'STREAMING' ? '流' : '批'}`;
  return 'SparkSql';
});
const artifacts = computed(() =>
  (profile.value?.artifacts ?? []).filter((item) =>
    item.programTypes.includes(programType.value),
  ),
);
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
function formatMemory(value: number) {
  return value % 1024 === 0 ? `${value / 1024}G` : `${value}M`;
}
function reset() {
  draft.value = clone(props.task);
  draft.value.executionConfig = {
    ...defaultExecutionConfig(),
    ...clone(props.task.executionConfig ?? {}),
  };
  memories.value = Object.fromEntries(
    fields.value
      .filter((field) => field.memory)
      .map((field) => [
        field.key,
        formatMemory(program.value?.[field.key] ?? field.default),
      ]),
  );
  error.value = '';
}
watch(
  () => [
    props.task.id,
    props.task.taskType,
    props.task.revision,
    props.task.name,
  ],
  reset,
  { immediate: true },
);
async function loadProfiles() {
  const seq = ++generation;
  profilesFailed.value = false;
  if (props.runtimeProfiles !== undefined) {
    profiles.value = props.runtimeProfiles;
    profilesLoading.value = false;
    return;
  }
  profiles.value = [];
  if (!native.value) return;
  profilesLoading.value = true;
  try {
    const rows = await getDevRuntimeProfiles();
    if (seq === generation) profiles.value = rows;
  } catch {
    if (seq === generation) profilesFailed.value = true;
  } finally {
    if (seq === generation) profilesLoading.value = false;
  }
}
watch(
  () => [
    props.runtimeProfiles,
    props.task.id,
    props.task.taskType,
    access.accessToken,
    access.refreshToken,
  ],
  loadProfiles,
  { immediate: true },
);
onBeforeUnmount(() => {
  ++generation;
});
function setExecution<K extends keyof DataforgeDevApi.ExecutionConfig>(
  key: K,
  value: DataforgeDevApi.ExecutionConfig[K],
) {
  if (!props.readonly) execution.value[key] = value;
}
function setNumber(key: ProgramNumber, value: null | number | string) {
  if (!props.readonly && program.value && typeof value === 'number')
    program.value[key] = value;
}
function chooseDatabase(value: unknown) {
  const selected = props.databases.find(
    (item) => String(item.value) === String(value),
  );
  if (!props.readonly && selected) {
    draft.value.databaseId = selected.value;
    draft.value.dbType = selected.productId ?? draft.value.dbType;
  }
}
function chooseProfile(code: unknown) {
  const selected = profiles.value.find(
    (item) =>
      item.code === code &&
      props.task.taskType &&
      item.allowedTaskTypes.includes(props.task.taskType),
  );
  if (props.readonly || !selected || !program.value) return;
  if (program.value.runtimeProfileCode !== selected.code) {
    program.value.artifactFileId = undefined;
    program.value.artifactSha256 = undefined;
    program.value.entryPoint = undefined;
    program.value.dependencyFileIds = [];
  }
  Object.assign(program.value, {
    runtimeProfileCode: selected.code,
    engineVersion: selected.engineVersion,
    queue: selected.queue,
  });
}
function chooseArtifact(id: unknown) {
  const selected = artifacts.value.find(
    (item) => String(item.fileId) === String(id),
  );
  if (!props.readonly && program.value)
    Object.assign(program.value, {
      artifactFileId: selected?.fileId,
      artifactSha256: selected?.sha256,
      entryPoint: selected?.entryPoint,
    });
}
function chooseDependencies(value: unknown) {
  if (
    !props.readonly &&
    program.value &&
    Array.isArray(value) &&
    value.every((id) => typeof id === 'string' && /^[1-9]\d*$/.test(id))
  )
    program.value.dependencyFileIds = value;
}
function changeArguments(value: unknown) {
  if (!props.readonly && program.value && typeof value === 'string') {
    program.value.arguments = value.split('\n').filter((item) => item !== '');
  }
}
function chooseWorkload(value: unknown) {
  if (
    props.readonly ||
    !program.value ||
    (value !== 'BATCH' && value !== 'STREAMING') ||
    (props.task.taskType === 'SPARK_SQL' && value === 'STREAMING')
  )
    return;
  program.value.workloadMode = value;
  draft.value.timeoutSeconds =
    value === 'STREAMING' ? 0 : draft.value.timeoutSeconds || 300;
}
function addParam() {
  if (
    !props.readonly &&
    !native.value &&
    (execution.value.localParams?.length ?? 0) < 100
  )
    execution.value.localParams = [
      ...(execution.value.localParams ?? []),
      { prop: '', type: 'VARCHAR', value: '' },
    ];
}
function removeParam(index: number) {
  if (!props.readonly && !native.value)
    execution.value.localParams = execution.value.localParams?.filter(
      (_, position) => position !== index,
    );
}
function cancel() {
  reset();
  emit('cancel');
}
function save() {
  if (props.readonly || props.saving) return;
  error.value = '';
  if (native.value && execution.value.localParams?.length) {
    error.value = '原生任务不接受 JDBC 绑定参数，请先清空不兼容参数。';
    return;
  }
  if (native.value && program.value) {
    for (const field of fields.value.filter((item) => item.memory)) {
      const text = (memories.value[field.key] ?? '').trim();
      const match = /^(\d+)([GM])?$/i.exec(text);
      const mb = match
        ? Number(match[1]) * (match[2]?.toUpperCase() === 'G' ? 1024 : 1)
        : 0;
      if (!Number.isSafeInteger(mb) || mb < 128 || mb > field.max) {
        error.value = `${field.label}请输入 128M 至 1024G，例如 512M 或 1G。`;
        return;
      }
      program.value[field.key] = mb;
    }
    if (
      localProfile.value &&
      fields.value.some((field) =>
        field.memory
          ? ![512, 768, 1024].includes(program.value![field.key] ?? 1024)
          : (program.value![field.key] ?? 1) !== 1,
      )
    ) {
      error.value =
        '当前 LOCAL 环境仅支持单核、单实例、单 Slot，内存为 512M、768M 或 1G。';
      return;
    }
  }
  const params = execution.value.localParams ?? [];
  if (
    params.some((item) => !/^[a-z_]\w{0,63}$/i.test(item.prop)) ||
    new Set(params.map((item) => item.prop)).size !== params.length
  ) {
    error.value =
      '参数名应为不重复的字母、数字或下划线，并以字母或下划线开头。';
    return;
  }
  emit(
    'save',
    clone({
      executionConfig: execution.value,
      programConfig: draft.value.programConfig,
      databaseId: draft.value.databaseId,
      dbType: draft.value.dbType,
      timeoutSeconds: draft.value.timeoutSeconds,
      maxRows: draft.value.maxRows,
    }),
  );
}
</script>

<template>
  <section class="workbench-properties-panel">
    <header class="panel-heading">
      <strong>属性配置</strong><button
        type="button"
        aria-label="收起属性配置"
        @click="emit('collapse')"
      >
        −
      </button>
    </header>
    <div class="panel-body">
      <h3>基础配置</h3>
      <div class="property-row">
        <label class="required">任务优先级:</label><Select
          :value="execution.priority"
          :options="PRIORITY_OPTIONS"
          :disabled="readonly"
          aria-label="任务优先级"
          @update:value="
            (value) =>
              setExecution(
                'priority',
                value as DataforgeDevApi.ExecutionConfig['priority'],
              )
          "
        />
      </div>
      <div class="property-row">
        <label class="required">Worker分组:</label><Input
          :value="execution.workerGroup"
          disabled
          aria-label="Worker分组"
        />
      </div>
      <div class="property-row with-unit">
        <label>失败重试次数:</label><InputNumber
          :value="execution.retryTimes"
          :min="0"
          :max="10"
          :disabled="readonly"
          aria-label="失败重试次数"
          @update:value="
            (value) =>
              setExecution('retryTimes', typeof value === 'number' ? value : 0)
          "
        /><span>次</span>
      </div>
      <div class="property-row with-unit">
        <label>失败重试间隔:</label><InputNumber
          :value="execution.retryIntervalMinutes"
          :min="1"
          :max="1440"
          :disabled="readonly"
          aria-label="失败重试间隔"
          @update:value="
            (value) =>
              setExecution(
                'retryIntervalMinutes',
                typeof value === 'number' ? value : 1,
              )
          "
        /><span>分</span>
      </div>
      <div class="property-row with-unit">
        <label>延迟执行时间:</label><InputNumber
          :value="execution.delayMinutes"
          :min="0"
          :max="1440"
          :disabled="readonly"
          aria-label="延迟执行时间"
          @update:value="
            (value) =>
              setExecution(
                'delayMinutes',
                typeof value === 'number' ? value : 0,
              )
          "
        /><span>分</span>
      </div>
      <template v-if="native && program">
        <div v-for="field in fields" :key="field.key" class="property-row">
          <label>{{ field.label }}:</label><Input
            v-if="field.memory"
            v-model:value="memories[field.key]"
            :disabled="readonly"
            :aria-label="field.label"
          /><InputNumber
            v-else
            :value="program[field.key] ?? field.default"
            :min="1"
            :max="localProfile ? 1 : field.max"
            :disabled="readonly"
            :aria-label="field.label"
            @update:value="(value) => setNumber(field.key, value)"
          />
        </div>
        <p v-if="localProfile" class="field-note">
          LOCAL 资源预算：单核、单实例、单 Slot；内存 512M、768M 或 1G。
        </p>
        <div class="property-row">
          <label>Yarn队列:</label><Input
            v-model:value="program.queue"
            :disabled="readonly || Boolean(program.runtimeProfileCode)"
            aria-label="Yarn队列"
            placeholder="请输入Yarn队列(选填)"
          />
        </div>
      </template>
      <h3>其他配置</h3>
      <div class="property-row">
        <label>数据连接类型:</label><span>{{ connectionLabel }}</span>
      </div>
      <div v-if="!native && task.taskType !== 'SHELL'" class="property-row">
        <label class="required">数据源连接:</label><Select
          :value="
            draft.databaseId == null ? undefined : String(draft.databaseId)
          "
          :options="
            databases.map((item) => ({
              label: item.label,
              value: String(item.value),
            }))
          "
          :disabled="readonly"
          show-search
          option-filter-prop="label"
          aria-label="数据源连接"
          placeholder="请选择数据源连接"
          @update:value="chooseDatabase"
        />
      </div>
      <div class="property-row">
        <label>分段执行符号:</label><Select
          :value="native ? undefined : execution.statementSeparator"
          :disabled="readonly || native"
          :options="[
            { label: ';', value: ';' },
            { label: '/', value: '/' },
          ]"
          :placeholder="
            native ? '原生引擎不使用 JDBC 分段符' : '请选择分段执行符号'
          "
          aria-label="分段执行符号"
          @update:value="
            (value) => setExecution('statementSeparator', String(value))
          "
        />
      </div>
      <h3>参数配置</h3>
      <div class="property-row parameter-add">
        <label>自定义参数:</label><Button :disabled="readonly || native" @click="addParam">
          ＋ 添加配置项
        </Button>
      </div>
      <p v-if="native" class="field-note">
        原生任务不支持 JDBC 绑定参数，参数值不会混入原生提交。
      </p>
      <Button
        v-if="native && execution.localParams?.length"
        :disabled="readonly"
        size="small"
        @click="execution.localParams = []"
      >
        清空不兼容参数
      </Button>
      <div
        v-for="(param, index) in execution.localParams"
        :key="index"
        class="parameter-item"
      >
        <Input
          v-model:value="param.prop"
          :disabled="readonly || native"
          aria-label="参数名称"
          placeholder="参数名称"
          :maxlength="64"
        /><Select
          v-model:value="param.type"
          :options="PARAM_TYPE_OPTIONS"
          :disabled="readonly || native"
          aria-label="参数类型"
        /><Input
          v-model:value="param.value"
          :disabled="readonly || native"
          aria-label="参数值"
          placeholder="参数值"
          :maxlength="2048"
        /><Button
          :disabled="readonly || native"
          danger
          type="text"
          :aria-label="`删除参数 ${index + 1}`"
          @click="removeParam(index)"
        >
          删除
        </Button>
      </div>
      <details class="advanced-properties">
        <summary>高级配置</summary>
        <template v-if="native && program">
          <div class="property-row">
            <label>受管运行环境:</label><Select
              :value="program.runtimeProfileCode"
              :options="profileOptions"
              :loading="profilesLoading"
              :disabled="readonly"
              aria-label="受管运行环境"
              placeholder="请选择受管运行环境"
              @update:value="chooseProfile"
            />
          </div>
          <p v-if="profilesFailed" role="alert" class="field-note">
            运行环境加载失败。<button type="button" @click="loadProfiles">
              重试
            </button>
          </p>
          <div class="property-row">
            <label>引擎版本:</label><Input
              :value="program.engineVersion"
              disabled
              aria-label="引擎版本"
            />
          </div>
          <div class="property-row">
            <label>运行模式:</label><Select
              :value="program.workloadMode"
              :disabled="readonly || task.taskType === 'SPARK_SQL'"
              :options="[
                { label: '批任务', value: 'BATCH' },
                {
                  label: '流任务',
                  value: 'STREAMING',
                  disabled: task.taskType === 'SPARK_SQL',
                },
              ]"
              aria-label="运行模式"
              @update:value="chooseWorkload"
            />
          </div>
          <div v-if="programType !== 'SQL'" class="property-row">
            <label>受管程序制品:</label><Select
              :value="
                program.artifactFileId == null
                  ? undefined
                  : String(program.artifactFileId)
              "
              :options="
                artifacts.map((item) => ({
                  label: item.name,
                  value: String(item.fileId),
                }))
              "
              :disabled="readonly"
              aria-label="受管程序制品"
              @update:value="chooseArtifact"
            />
          </div>
          <div v-if="programType === 'JAR'" class="property-row">
            <label>程序入口类:</label><Input
              :value="program.entryPoint"
              disabled
              aria-label="程序入口类"
            />
          </div>
          <div class="property-row">
            <label>受管依赖资源:</label><Select
              :value="program.dependencyFileIds?.map(String)"
              :options="
                (profile?.dependencyResources ?? []).map((item) => ({
                  label: item.name,
                  value: String(item.fileId),
                }))
              "
              :disabled="readonly"
              mode="multiple"
              aria-label="受管依赖资源"
              @update:value="chooseDependencies"
            />
          </div>
          <div v-if="programType !== 'SQL'" class="property-row">
            <label>程序参数:</label><Input.TextArea
              :value="program.arguments?.join('\n')"
              :disabled="readonly"
              :rows="3"
              aria-label="程序参数"
              placeholder="每行一个固定参数"
              @update:value="changeArguments"
            />
          </div>
          <div v-if="engine === 'FLINK'" class="property-row">
            <label>检查点间隔(ms):</label><InputNumber
              v-model:value="program.checkpointIntervalMs"
              :min="100"
              :max="86400000"
              :disabled="readonly"
              aria-label="检查点间隔"
            />
          </div>
        </template>
        <div v-else class="property-row">
          <label>SQL类型:</label><Select
            v-model:value="execution.sqlType"
            :options="[
              { label: '自动识别', value: 'AUTO' },
              { label: '查询', value: 'QUERY' },
              { label: '非查询', value: 'NON_QUERY' },
              { label: '存储过程', value: 'PROCEDURE' },
            ]"
            :disabled="readonly"
            aria-label="SQL类型"
          />
        </div>
        <div class="property-row with-unit">
          <label>执行超时:</label><InputNumber
            v-model:value="draft.timeoutSeconds"
            :min="program?.workloadMode === 'STREAMING' ? 0 : 1"
            :max="86400"
            :disabled="readonly"
            aria-label="执行超时"
          /><span>秒</span>
        </div>
        <div v-if="!native" class="property-row">
          <label>最大返回行数:</label><InputNumber
            v-model:value="draft.maxRows"
            :min="1"
            :max="10000"
            :disabled="readonly"
            aria-label="最大返回行数"
          />
        </div>
      </details>
      <p v-if="error" class="panel-error" role="alert">{{ error }}</p>
    </div>
    <footer class="panel-footer">
      <Button :disabled="saving" @click="cancel">取消</Button><Button
        :disabled="readonly"
        :loading="saving"
        type="primary"
        ghost
        @click="save"
      >
        保存
      </Button>
    </footer>
  </section>
</template>

<style scoped>
.workbench-properties-panel {
  --panel-primary: #2865ff;

  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  color: #606266;
  background: #f7f7f7;
  border-left: 1px solid #e0e5ee;
}

.panel-heading {
  display: flex;
  flex: 0 0 42px;
  align-items: center;
  justify-content: space-between;
  padding: 0 11px;
  border-bottom: 1px solid #e0e5ee;
}

.panel-heading strong {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--panel-primary);
}

.panel-heading strong::before {
  width: 6px;
  height: 16px;
  content: '';
  background: var(--panel-primary);
  border-radius: 4px;
}

.panel-heading button {
  padding: 2px 7px;
  color: #8c9aad;
  background: transparent;
  border: 0;
}

.panel-body {
  flex: 1;
  min-height: 0;
  padding: 0 10px 12px;
  overflow: auto;
}

h3 {
  margin: 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--panel-primary);
}

.property-row {
  display: grid;
  grid-template-columns: minmax(112px, 126px) minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  min-height: 26px;
  margin: 14px 10px 14px 0;
  font-size: 14px;
}

.property-row > label {
  text-align: right;
  white-space: nowrap;
}

.property-row .required::before {
  margin-right: 4px;
  color: #ff4d4f;
  content: '*';
}

.property-row.with-unit {
  grid-template-columns: minmax(112px, 126px) minmax(0, 1fr) 14px;
  gap: 7px;
  margin-right: 10px;
}

.property-row > :deep(.ant-input-number),
.property-row > :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}

.property-row :deep(.ant-input),
.property-row :deep(.ant-select-selector),
.property-row :deep(.ant-input-number),
.property-row :deep(.ant-input-number-input) {
  height: 26px;
  min-height: 26px;
  padding-top: 0;
  padding-bottom: 0;
  font-size: 14px;
  border-radius: 2px;
}

.property-row > :deep(.ant-select) {
  height: 26px;
}

.property-row :deep(.ant-select-selection-item),
.property-row :deep(.ant-select-selection-placeholder) {
  line-height: 24px;
}

.property-row :deep(.ant-input-number-input) {
  text-align: center;
}

.parameter-add :deep(.ant-btn) {
  height: 27px;
  padding: 0 7px;
  color: var(--panel-primary);
  background: #fff;
  border-color: var(--panel-primary);
  border-radius: 2px;
}

.parameter-add :deep(.ant-btn:disabled) {
  color: #a4b3d1;
  border-color: #cbd4e5;
}

.parameter-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 92px;
  gap: 6px;
  padding: 8px;
  margin: 8px 0;
  border: 1px solid #e0e5ee;
}

.parameter-item > :deep(.ant-select) {
  width: 100%;
}

.field-note {
  margin: 8px 0;
  font-size: 11px;
  line-height: 1.5;
  color: #909399;
}

.field-note button {
  padding: 0 4px;
  color: var(--panel-primary);
  border: 0;
}

.advanced-properties {
  padding-top: 10px;
  margin-top: 18px;
  border-top: 1px solid #e0e5ee;
}

.advanced-properties summary {
  font-size: 12px;
  color: #7a879c;
  cursor: pointer;
}

.panel-error {
  font-size: 12px;
  color: #ff4d4f;
}

.panel-footer {
  display: flex;
  flex: 0 0 44px;
  gap: 20px;
  align-items: center;
  justify-content: flex-end;
  padding: 0 11px;
  border-top: 1px solid #e0e5ee;
}

.panel-footer :deep(.ant-btn) {
  min-width: 52px;
  height: 26px;
  border-radius: 2px;
}

:global(.dark) .workbench-properties-panel {
  color: hsl(var(--foreground));
  background: hsl(var(--background));
}

@media (max-width: 420px) {
  .property-row,
  .property-row.with-unit {
    grid-template-columns: minmax(100px, 120px) minmax(0, 1fr);
    margin-right: 0;
  }

  .property-row.with-unit > span:last-child {
    display: none;
  }
}
</style>
