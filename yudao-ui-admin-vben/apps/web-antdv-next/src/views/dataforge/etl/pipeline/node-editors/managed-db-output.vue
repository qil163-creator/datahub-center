<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 编辑器只操作设计器提供的配置副本。 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Alert, Checkbox, Input, Select } from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';
import { getQualityTaskSimpleList } from '#/api/governance/quality/task';

import { useWorkspaceDatabases } from '../workspace-context';
import CreateTargetQualityTask from './create-target-quality-task.vue';
import EnvironmentBinding from './environment-binding.vue';

import './editor-style.css';

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  qualitySources?: { name: string; nodeNo: string; statusField: string }[];
}>();
const loadDatabases = useWorkspaceDatabases();
const databases = ref<{ label: string; value: string }[]>([]);
const tables = ref<{ label: string; value: string }[]>([]);
const tasks = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
const tableLoading = ref(false);
let sequence = 0;
let tableSequence = 0;
const sourceOptions = computed(() =>
  (props.qualitySources ?? []).map((source) => ({
    value: source.nodeNo,
    label: source.name,
  })),
);
const fieldOptions = computed(() =>
  (props.inputFields ?? [])
    .filter(
      (field) =>
        ![props.config.batchColumn, props.config.rowColumn].includes(
          field.name,
        ),
    )
    .map((field) => ({ value: field.name, label: field.name })),
);
const policies = [
  { value: 'STRICT', label: '严格入库：只写允许的合格记录' },
  { value: 'KEEP_TAGGED', label: '全量保留：包含不合格记录及质量标识' },
  { value: 'BATCH_BLOCK', label: '整批阻断：存在不允许的记录则不提交' },
];
function selectSource(id: unknown) {
  const source = props.qualitySources?.find((item) => item.nodeNo === id);
  if (!source) return;
  const previous = props.config.statusColumn;
  props.config.qualityNodeNo = source.nodeNo;
  props.config.statusColumn = source.statusField;
  props.config.fields = [
    ...new Set([
      ...(props.config.fields ?? []).filter(
        (field: string) => field !== previous,
      ),
      source.statusField,
    ]),
  ];
}
async function loadTables(id?: DataforgeEtlApi.Id) {
  const current = ++tableSequence;
  tables.value = [];
  tableLoading.value = false;
  if (!id) return;
  tableLoading.value = true;
  try {
    const list = await getDatabaseTableList(id);
    if (current === tableSequence)
      tables.value = list.map((table) => ({
        label: table.remarks
          ? `${table.tableName}（${table.remarks}）`
          : table.tableName,
        // Managed targets currently verify PostgreSQL 16; preserve exact physical identifier spelling.
        value: `"${table.tableName.replaceAll('"', '""')}"`,
      }));
  } catch {
    // 请求客户端已显示错误，保留空选项。
  } finally {
    if (current === tableSequence) tableLoading.value = false;
  }
}
async function loadOptions() {
  const current = ++sequence;
  loading.value = true;
  const result = await Promise.allSettled([
    loadDatabases(),
    getQualityTaskSimpleList(),
  ]);
  if (current !== sequence) return;
  const [db, quality] = result;
  if (db.status === 'fulfilled')
    databases.value = db.value.map((item) => ({
      value: String(item.id),
      label: item.name ?? String(item.id),
    }));
  if (quality.status === 'fulfilled')
    tasks.value = quality.value.map((item) => ({
      value: String(item.id),
      label: item.taskName ?? String(item.id),
    }));
  loading.value = false;
}
watch(
  () => props.config.databaseId,
  (id) => {
    void loadTables(id);
  },
);
onMounted(() => {
  props.config.disposition ??= 'STRICT';
  props.config.allowWarnings ??= true;
  props.config.batchColumn ??= '_etl_batch_key';
  props.config.rowColumn ??= '_etl_row_no';
  props.config.statusColumn ??= '_quality_status';
  props.config.fields ??= [];
  props.config.businessKeys ??= [];
  if (!props.config.qualityNodeNo && props.qualitySources?.length === 1)
    selectSource(props.qualitySources[0]!.nodeNo);
  void loadOptions();
  void loadTables(props.config.databaseId);
});
onBeforeUnmount(() => {
  ++sequence;
  ++tableSequence;
});
</script>
<template>
  <div class="etl-editor">
    <Alert
      type="info"
      show-icon
      message="当前受管输出已验证 PostgreSQL 16。先暂存完整批次，流程完成后提交，再执行批次质量验收。提交成功和质量通过分别显示；仅通过准入后开放受控批次读取。每批最多 5000 行、8 MiB。"
    />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">直接上游质量节点</label><Select
        :value="config.qualityNodeNo"
        :options="sourceOptions"
        placeholder="先连接全量标记模式的质量节点 main 输出"
        @change="selectSource"
      />
    </div>
    <EnvironmentBinding :config="config" />
    <div v-if="!config.logicalDatabaseKey" class="form-item">
      <label class="form-item__label form-item__label--required">目标数据源</label><Select
        :value="
          config.databaseId == null ? undefined : String(config.databaseId)
        "
        :options="databases"
        :loading="loading"
        show-search
        option-filter-prop="label"
        @change="
          (id) => {
            config.databaseId = id;
            config.tableName = undefined;
          }
        "
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">已有目标表</label><Input
        v-if="config.logicalDatabaseKey"
        v-model:value="config.tableName"
        placeholder="逻辑连接 Schema 内的已有目标表"
      /><Select
        v-else
        v-model:value="config.tableName"
        :options="tables"
        :loading="tableLoading"
        show-search
        option-filter-prop="label"
        placeholder="目标表需已具备批次、行号和质量状态字段"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">批次质量验收任务</label><Select
        :value="
          config.qualityTaskId == null
            ? undefined
            : String(config.qualityTaskId)
        "
        :options="tasks"
        :loading="loading"
        show-search
        option-filter-prop="label"
        @change="(id) => (config.qualityTaskId = id)"
      />
      <CreateTargetQualityTask
        :config="config"
        @created="
          (result) => {
            config.qualityTaskId = result.taskId;
            tasks = [
              ...tasks.filter((item) => item.value !== String(result.taskId)),
              { value: String(result.taskId), label: result.taskName },
            ];
          }
        "
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">目标准入策略</label><Select
        v-model:value="config.disposition"
        :options="policies"
      /><Checkbox v-model:checked="config.allowWarnings">
        允许警告记录通过准入
      </Checkbox>
    </div>
    <Alert
      v-if="config.disposition === 'KEEP_TAGGED'"
      type="warning"
      message="全量保留包含不合格记录。批次质量通过后，后续查询和服务仍需显式按质量状态过滤；直接访问目标库不受本页面准入控制。"
    />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">输出字段（含质量状态，最多 254 个）</label><Select
        v-model:value="config.fields"
        mode="tags"
        :options="fieldOptions"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">业务身份字段（1–16 个）</label><Select
        v-model:value="config.businessKeys"
        mode="tags"
        :options="
          fieldOptions.filter((field) => field.value !== config.statusColumn)
        "
      />
      <p class="form-item__tip">
        用于确认检测与输出为同一批完整数据。不能使用批次、行号或质量状态字段；同一质量节点的多个目标必须选择相同身份字段。
      </p>
    </div>
    <div class="form-item">
      <label class="form-item__label">技术字段</label><Input v-model:value="config.batchColumn" addon-before="批次" /><Input
        v-model:value="config.rowColumn"
        addon-before="行号"
      /><Input :value="config.statusColumn" addon-before="质量状态" readonly />
      <p class="form-item__tip">
        批次与行号由系统生成，不加入输出字段列表。质量字段与直接上游保持一致。
      </p>
    </div>
  </div>
</template>
