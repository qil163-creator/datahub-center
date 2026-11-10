<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  FormItem,
  Input,
  InputNumber,
  Select,
} from 'antdv-next';

import {
  getDevProgramType,
  getDevRuntimeProfiles,
  getDevTaskEngine,
} from '#/api/dataforge/dev';

const props = defineProps<{
  layout?: 'grid' | 'panel';
  readonly?: boolean;
  taskType?: DataforgeDevApi.TaskType;
  workloadMode?: DataforgeDevApi.WorkloadMode;
}>();
const emit = defineEmits<{ workloadChange: [mode: 'BATCH' | 'STREAMING'] }>();
const config = defineModel<DataforgeDevApi.ProgramConfig>({ required: true });
const engine = computed(() => getDevTaskEngine(props.taskType));
const programType = computed(() => getDevProgramType(props.taskType));
const workloadOptions = computed(() => [
  { label: '批任务', value: 'BATCH' },
  {
    label:
      props.taskType === 'SPARK_SQL' ? '流任务（Spark SQL 不支持）' : '流任务',
    value: 'STREAMING',
    disabled: props.taskType === 'SPARK_SQL',
  },
]);
const accessStore = useAccessStore();
const profiles = ref<DataforgeDevApi.RuntimeProfile[]>([]);
const loading = ref(false);
const failed = ref(false);
let generation = 0;
async function loadProfiles() {
  const seq = ++generation;
  profiles.value = [];
  failed.value = false;
  loading.value = true;
  try {
    const rows = await getDevRuntimeProfiles();
    if (seq === generation) profiles.value = rows;
  } catch {
    if (seq === generation) failed.value = true;
  } finally {
    if (seq === generation) loading.value = false;
  }
}
watch(() => [accessStore.accessToken, accessStore.refreshToken], loadProfiles, {
  immediate: true,
});
onBeforeUnmount(() => {
  ++generation;
});
const eligibleProfiles = computed(() =>
  profiles.value.filter(
    (profile) =>
      profile.engine === engine.value &&
      props.taskType !== undefined &&
      profile.allowedTaskTypes.includes(props.taskType),
  ),
);
const profile = computed(() =>
  eligibleProfiles.value.find(
    (row) => row.code === config.value.runtimeProfileCode,
  ),
);
const profileOptions = computed(() => {
  const options: { disabled?: boolean; label: string; value: string }[] =
    eligibleProfiles.value.map((row) => ({
      label: `${row.name} · ${row.deployMode === 'YARN_ATTACHED' ? 'YARN 同步执行' : row.deployMode}`,
      value: row.code,
    }));
  if (config.value.runtimeProfileCode && !profile.value)
    options.push({
      value: config.value.runtimeProfileCode,
      label: `不可用部署档案：${config.value.runtimeProfileCode}`,
      disabled: true,
    });
  return options;
});
const artifacts = computed(() =>
  (profile.value?.artifacts ?? []).filter((row) =>
    row.programTypes.includes(programType.value),
  ),
);
const artifact = computed(() =>
  artifacts.value.find(
    (row) => String(row.fileId) === String(config.value.artifactFileId),
  ),
);
function resourceOptions(
  resources: DataforgeDevApi.RuntimeResource[],
  selected: DataforgeDevApi.Id[],
) {
  const options: { disabled?: boolean; label: string; value: string }[] =
    resources.map((row) => ({ label: row.name, value: String(row.fileId) }));
  for (const id of selected) {
    if (!options.some((row) => row.value === String(id)))
      options.push({
        value: String(id),
        label: `不可用受管资源：#${id}`,
        disabled: true,
      });
  }
  return options;
}
const artifactOptions = computed(() =>
  resourceOptions(
    artifacts.value,
    config.value.artifactFileId === null ||
      config.value.artifactFileId === undefined
      ? []
      : [config.value.artifactFileId],
  ),
);
const dependencyOptions = computed(() =>
  resourceOptions(
    profile.value?.dependencyResources ?? [],
    Array.isArray(config.value.dependencyFileIds)
      ? config.value.dependencyFileIds
      : [],
  ),
);
function selectProfile(code: unknown) {
  const selected = eligibleProfiles.value.find((row) => row.code === code);
  if (!selected || props.readonly) return;
  config.value = {
    ...config.value,
    runtimeProfileCode: selected.code,
    engineVersion: selected.engineVersion,
    queue: selected.queue,
    pythonEnvironment: undefined,
    artifactFileId: undefined,
    artifactSha256: undefined,
    dependencyFileIds: [],
    entryPoint: undefined,
  };
}
function selectArtifact(id: unknown) {
  if (props.readonly) return;
  const selected = artifacts.value.find((row) => String(row.fileId) === id);
  config.value = {
    ...config.value,
    artifactFileId: selected ? String(selected.fileId) : undefined,
    artifactSha256: selected?.sha256,
    entryPoint: selected?.entryPoint,
  };
}
function changeWorkloadMode(value: unknown) {
  if (props.workloadMode) {
    config.value.workloadMode = props.workloadMode;
    return;
  }
  if (value === 'BATCH' || value === 'STREAMING') emit('workloadChange', value);
}
const artifactFileId = computed(() =>
  config.value.artifactFileId === undefined ||
  config.value.artifactFileId === null
    ? ''
    : String(config.value.artifactFileId),
);
function changeDependencyIds(value: unknown) {
  if (props.readonly) return;
  if (value === null || value === undefined) {
    config.value.dependencyFileIds = undefined;
    return;
  }
  if (
    Array.isArray(value) &&
    value.every(
      (id): id is DataforgeDevApi.Id =>
        typeof id === 'string' ||
        (typeof id === 'number' && Number.isSafeInteger(id)),
    )
  ) {
    config.value.dependencyFileIds = value;
  }
}
function validateDependencyIds(_rule: unknown, value: unknown) {
  return value === undefined ||
    value === null ||
    (Array.isArray(value) && value.every((id) => /^[1-9]\d*$/.test(String(id))))
    ? Promise.resolve()
    : Promise.reject(new Error('依赖文件应使用平台文件的正整数编号'));
}
type NumericField =
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
const resourceFields = computed<
  { key: NumericField; label: string; max: number; min: number }[]
>(() =>
  engine.value === 'FLINK'
    ? [
        { key: 'parallelism', label: '并行度', min: 1, max: 65_536 },
        {
          key: 'jobManagerMemoryMb',
          label: 'JobManager 内存（MB）',
          min: 128,
          max: 1_048_576,
        },
        {
          key: 'taskManagerSlots',
          label: 'TaskManager Slot 数',
          min: 1,
          max: 1024,
        },
        {
          key: 'taskManagerCount',
          label: '最大 TaskManager 数',
          min: 1,
          max: 10_000,
        },
        {
          key: 'taskManagerMemoryMb',
          label: 'TaskManager 内存（MB）',
          min: 128,
          max: 1_048_576,
        },
      ]
    : [
        {
          key: 'driverMemoryMb',
          label: 'Driver 内存（MB）',
          min: 128,
          max: 1_048_576,
        },
        {
          key: 'executorMemoryMb',
          label: 'Executor 内存（MB）',
          min: 128,
          max: 1_048_576,
        },
        { key: 'driverCores', label: 'Driver 核数', min: 1, max: 1024 },
        { key: 'executorCores', label: 'Executor 核数', min: 1, max: 1024 },
        { key: 'numExecutors', label: 'Executor 数量', min: 1, max: 10_000 },
      ],
);
</script>

<template>
  <div
    class="program-config-fields"
    :class="{ 'program-config-grid': layout === 'grid' }"
  >
    <h4 class="program-section-title">
      <IconifyIcon
        class="size-4 shrink-0"
        icon="ri:bookmark-2-fill"
        aria-hidden="true"
      />
      运行环境
    </h4>

    <Alert
      v-if="!failed && !loading && eligibleProfiles.length === 0"
      class="mb-3"
      type="warning"
      message="平台未配置此任务类型的可用部署档案；任务配置未完成，完善后保存才可运行。"
    />
    <FormItem label="运行模式" :name="['programConfig', 'workloadMode']">
      <Select
        v-model:value="config.workloadMode"
        :disabled="readonly || Boolean(workloadMode)"
        :options="workloadOptions"
        @change="changeWorkloadMode"
      />
    </FormItem>
    <Alert
      v-if="programType === 'SQL'"
      class="mb-3"
      type="info"
      message="原生 SQL 面向 YARN 计算作业；元数据 DDL 请使用现有 SQL 通道。未获得引擎终止证据时，实例结果保持待核查。"
    />
    <p
      v-if="profile?.deployMode === 'YARN_ATTACHED'"
      class="text-muted-foreground mb-3 text-xs"
    >
      {{
        taskType === 'SPARK_SQL'
          ? 'Spark SQL 使用 client 模式，Driver 在受管执行节点运行；流任务请选择 Spark JAR 或 Python。'
          : engine === 'SPARK'
            ? 'Spark 程序使用 cluster 模式，Driver 在 YARN 集群运行。'
            : 'Flink 使用受管 YARN 部署，同步跟踪引擎执行结果。'
      }}
    </p>
    <Alert
      v-if="config.workloadMode === 'STREAMING'"
      class="mb-3"
      type="info"
      message="流任务可长期运行；超时设为 0 表示不自动终止，请在运行实例中查看状态并停止。"
    />
    <FormItem label="部署档案" :name="['programConfig', 'runtimeProfileCode']">
      <Select
        :value="config.runtimeProfileCode"
        :options="profileOptions"
        :loading="loading"
        :disabled="readonly || loading"
        placeholder="请选择已配置的受管部署档案"
        @change="selectProfile"
      />
    </FormItem>
    <FormItem label="引擎版本" :name="['programConfig', 'engineVersion']">
      <Input
        :value="profile?.engineVersion || config.engineVersion"
        disabled
        placeholder="随部署档案带出"
      />
    </FormItem>
    <template v-if="programType !== 'SQL'">
      <FormItem
        label="执行制品"
        :name="['programConfig', 'artifactFileId']"
        :rules="[
          { pattern: /^[1-9]\d*$/, message: '请输入平台文件的正整数编号' },
        ]"
      >
        <Select
          :value="artifactFileId || undefined"
          :options="artifactOptions"
          :disabled="readonly || !profile"
          placeholder="请选择部署档案中的受管制品"
          allow-clear
          @change="selectArtifact"
        />
      </FormItem>
      <FormItem
        label="制品 SHA256"
        :name="['programConfig', 'artifactSha256']"
        :rules="[
          {
            pattern: /^[a-fA-F0-9]{64}$/,
            message: 'SHA256 应为64位十六进制字符串',
          },
        ]"
      >
        <Input
          :value="artifact?.sha256 || config.artifactSha256"
          disabled
          placeholder="随受管制品带出"
        />
      </FormItem>
      <FormItem
        v-if="programType === 'JAR'"
        label="入口类"
        :name="['programConfig', 'entryPoint']"
      >
        <Input
          v-model:value="config.entryPoint"
          :disabled="readonly || !!artifact?.entryPoint"
          :maxlength="500"
          placeholder="例如 com.example.Main"
        />
      </FormItem>
    </template>
    <FormItem
      v-if="programType !== 'SQL'"
      label="程序参数"
      :name="['programConfig', 'arguments']"
    >
      <Select
        v-model:value="config.arguments"
        :disabled="readonly"
        mode="tags"
        placeholder="逐项输入，按回车添加；每项原样传递"
        :max-count="100"
      />
    </FormItem>
    <FormItem
      label="依赖资源"
      :name="['programConfig', 'dependencyFileIds']"
      :rules="[{ validator: validateDependencyIds }]"
    >
      <Select
        :value="
          Array.isArray(config.dependencyFileIds)
            ? config.dependencyFileIds
            : undefined
        "
        mode="multiple"
        :options="dependencyOptions"
        :disabled="readonly || !profile"
        placeholder="请选择部署档案中的受管依赖"
        :max-count="100"
        @update:value="changeDependencyIds"
      />
    </FormItem>
    <p
      v-if="programType === 'PYTHON'"
      class="text-muted-foreground mb-3 text-xs"
    >
      Python 依赖环境由受管部署提供。任务执行所选 .py 制品，不执行编辑器文本。
    </p>
    <Alert
      v-if="config.pythonEnvironment"
      class="mb-3"
      type="warning"
      message="旧 Python 环境覆盖不再支持，请重新选择受管部署档案以清除。"
    />
    <h4 class="program-section-title">
      <IconifyIcon
        class="size-4 shrink-0"
        icon="ri:bookmark-2-fill"
        aria-hidden="true"
      />
      资源配置
    </h4>
    <FormItem
      v-for="field in resourceFields"
      :key="field.key"
      :label="field.label"
      :name="['programConfig', field.key]"
    >
      <InputNumber
        v-model:value="config[field.key]"
        :disabled="readonly"
        :min="field.min"
        :max="field.max"
        :precision="0"
        class="w-full"
        placeholder="选填，使用部署默认值"
      />
    </FormItem>
    <FormItem
      v-if="engine === 'SPARK' || engine === 'FLINK'"
      label="资源队列"
      :name="['programConfig', 'queue']"
    >
      <Input
        :value="profile?.queue || config.queue"
        disabled
        :maxlength="100"
        placeholder="随部署档案带出"
      />
    </FormItem>
    <p v-if="engine === 'FLINK'" class="text-muted-foreground mb-3 text-xs">
      最大 TaskManager 数与每个 Slot
      数共同限定资源容量；实际实例数由引擎分配，并行度不能超过容量。
    </p>
    <FormItem
      v-if="engine === 'FLINK' && config.workloadMode === 'STREAMING'"
      label="Checkpoint 间隔（ms）"
      :name="['programConfig', 'checkpointIntervalMs']"
    >
      <InputNumber
        v-model:value="config.checkpointIntervalMs"
        :disabled="readonly"
        :min="100"
        :max="86400000"
        :precision="0"
        class="w-full"
        placeholder="选填"
      />
    </FormItem>
  </div>
</template>

<style scoped>
.program-config-fields {
  min-width: 0;
  font-size: 14px;
}

.program-section-title {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 8px 12px;
  margin: 12px 0 14px;
  font-size: 13px;
  font-weight: 500;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 6%);
  border-radius: 2px;
}

.program-config-fields :deep(.ant-form-item) {
  margin-bottom: 14px;
}

.program-config-fields :deep(.ant-form-item-label > label) {
  font-size: 14px;
}

.program-config-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 24px;
}

.program-config-grid > .program-section-title,
.program-config-grid > :deep(.ant-alert),
.program-config-grid > p {
  grid-column: 1 / -1;
}

@media (max-width: 760px) {
  .program-config-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
