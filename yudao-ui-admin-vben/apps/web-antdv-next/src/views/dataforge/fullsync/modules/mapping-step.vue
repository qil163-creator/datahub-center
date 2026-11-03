<script setup lang="ts">
import type {
  DatabaseTable,
  FullSyncAppendColumn,
  FullSyncConfig,
  FullSyncTableProperties,
} from '#/api/dataforge/fullsync';

import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  reactive,
  ref,
  watch,
} from 'vue';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Table,
} from 'antdv-next';

import {
  getDatabaseTables,
} from '#/api/dataforge/fullsync';

import ReferenceIcon from './reference-icon.vue';

const props = withDefaults(
  defineProps<{
    config: FullSyncConfig;
    readonly?: boolean;
    tables: DatabaseTable[];
  }>(),
  { readonly: false },
);
const emit = defineEmits<{ 'update:config': [config: FullSyncConfig] }>();
const SqlEditor = defineAsyncComponent(
  () => import('#/components/monaco/sql-editor.vue'),
);
interface MappingRow extends FullSyncTableProperties {
  source: string;
  sourceComment: string;
  targetTable: string;
  targetComment: string;
}
const query = ref('');
const selected = ref<string[]>([]);
const appendIds = ref<string[]>([]);
watch(() => props.config.appendColumns?.length ?? 0, (length) => {
  while (appendIds.value.length < length) appendIds.value.push(crypto.randomUUID());
  if (appendIds.value.length > length) appendIds.value.length = length;
}, { immediate: true });
const tableDialog = ref(false);
const prefixDialog = ref(false);
const ruleDialog = ref(false);
const prefix = ref('');
const ruleType = ref<'bucketRule' | 'partitionRule'>('partitionRule');
const ruleSource = ref('');
const ruleValue = ref('');
const matching = ref(false);
const draft = reactive<MappingRow>({
  source: '',
  sourceComment: '',
  targetTable: '',
  targetComment: '',
  replicaCount: 1,
});
let matchId = 0;
onBeforeUnmount(() => {
  matchId++;
});
watch(() => props.config, () => {
  matchId++;
  matching.value = false;
  selected.value = selected.value.filter((source) => props.config.sourceSelectedTables.includes(source));
}, { deep: true });
const rows = computed<MappingRow[]>(() =>
  props.config.sourceSelectedTables.map((source) => {
    const item = props.config.tableProperties?.[source];
    const mapping = props.config.configMap?.[source];
    return {
      ...item,
      source,
      sourceComment:
        item?.sourceComment ??
        props.tables.find((table) => table.name === source)?.comment ??
        '',
      targetTable: item?.targetTable ?? mapping?.targetTableName ?? '',
      targetComment: item?.targetComment ?? mapping?.targetTableComment ?? '',
      replicaCount: item?.replicaCount ?? 1,
    };
  }),
);
const visibleRows = computed(() =>
  rows.value.filter((row) =>
    `${row.source} ${row.sourceComment}`
      .toLowerCase()
      .includes(query.value.trim().toLowerCase()),
  ),
);
const appendRows = computed(() =>
  (props.config.appendColumns ?? []).map((column, index) => ({
    ...column,
    key: appendIds.value[index],
    index,
  })),
);
const dataTypes = [
  'VARCHAR',
  'CHAR',
  'TEXT',
  'TINYINT',
  'SMALLINT',
  'INT',
  'BIGINT',
  'FLOAT',
  'DOUBLE',
  'DECIMAL',
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'TIMESTAMP',
  'JSON',
].map((value) => ({ label: value, value }));
const columns = computed(() => [
  { key: 'index', title: '编号', width: 60, align: 'center' as const },
  { key: 'source', dataIndex: 'source', title: '来源物理表名称', width: 180 },
  {
    key: 'sourceComment',
    dataIndex: 'sourceComment',
    title: '来源中文表名称',
    width: 180,
  },
  { key: 'targetTable', title: '目标物理表名称', width: 180 },
  { key: 'targetComment', title: '目标中文表名称', width: 180 },
  {
    key: 'partitionRule',
    title: '分区规则',
    width: 110,
    align: 'center' as const,
  },
  {
    key: 'bucketRule',
    title: '分桶规则',
    width: 110,
    align: 'center' as const,
  },
  {
    key: 'replicaCount',
    title: '副本数量',
    width: 120,
    align: 'center' as const,
  },
  {
    key: 'actions',
    title: '操作',
    width: props.readonly ? 100 : 220,
    fixed: 'right' as const,
    align: 'center' as const,
  },
]);
const appendColumns = [
  { key: 'index', title: '编号', width: 50, align: 'center' as const },
  { key: 'name', title: '字段名称', width: 230 },
  { key: 'description', title: '字段描述', width: 230 },
  { key: 'type', title: '数据类型', width: 130 },
  { key: 'length', title: '属性长度', width: 130 },
  { key: 'scale', title: '小数位', width: 120 },
  { key: 'defaultValue', title: '默认值', width: 120 },
  {
    key: 'actions',
    title: '操作',
    width: 85,
    fixed: 'right' as const,
    align: 'center' as const,
  },
];
const selection = computed(() =>
  props.readonly
    ? undefined
    : {
        selectedRowKeys: selected.value,
        onChange: (keys: (number | string)[]) => {
          selected.value = keys.map(String);
        },
        columnWidth: 40,
      },
);
function update(config: FullSyncConfig) {
  emit('update:config', config);
}
function patchRow(source: string, patch: FullSyncTableProperties) {
  const old = rows.value.find((row) => row.source === source);
  const next = { ...props.config.tableProperties?.[source], ...patch };
  update({
    ...props.config,
    mapperType: 'SINGLE',
    tableProperties: { ...props.config.tableProperties, [source]: next },
    configMap: {
      ...props.config.configMap,
      [source]: {
        ...props.config.configMap?.[source],
        targetTableName: patch.targetTable ?? old?.targetTable ?? source,
        targetTableComment: patch.targetComment ?? old?.targetComment ?? '',
      },
    },
  });
}
function applyRows(
  sources: string[],
  patch: (row: MappingRow) => FullSyncTableProperties,
) {
  const properties = { ...props.config.tableProperties };
  const configMap = { ...props.config.configMap };
  for (const row of rows.value.filter((item) =>
    sources.includes(item.source),
  )) {
    const next = { ...row, ...patch(row) };
    properties[row.source] = next;
    configMap[row.source] = {
      ...configMap[row.source],
      targetTableName: next.targetTable,
      targetTableComment: next.targetComment,
    };
  }
  update({
    ...props.config,
    mapperType: 'SINGLE',
    tableProperties: properties,
    configMap,
  });
}
function targets() { return selected.value; }
function editRow(row: MappingRow) {
  Object.assign(draft, { partitionRule: '', bucketRule: '', ...row });
  tableDialog.value = true;
}
function saveRow() {
  if (!draft.targetTable.trim()) {
    message.warning('请输入目标物理表名称');
    return;
  }
  patchRow(draft.source, {
    targetTable: draft.targetTable.trim(),
    sourceComment: draft.sourceComment,
    targetComment: draft.targetComment,
    partitionRule: draft.partitionRule,
    bucketRule: draft.bucketRule,
    replicaCount: draft.replicaCount,
  });
  tableDialog.value = false;
}
function removeRow(source: string) {
  Modal.confirm({
    title: '删除来源表',
    content: `确定从当前配置中删除 ${source}？`,
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      const properties = Object.fromEntries(Object.entries(props.config.tableProperties ?? {}).filter(([key]) => key !== source));
      const configMap = Object.fromEntries(Object.entries(props.config.configMap ?? {}).filter(([key]) => key !== source));
      selected.value = selected.value.filter((name) => name !== source);
      update({
        ...props.config,
        sourceSelectedTables: props.config.sourceSelectedTables.filter(
          (name) => name !== source,
        ),
        tableProperties: properties,
        configMap,
      });
    },
  });
}
function openPrefix() {
  prefix.value = '';
  prefixDialog.value = true;
}
function savePrefix() {
  applyRows(targets(), (row) => ({
    targetTable: `${prefix.value}${row.source}`,
  }));
  prefixDialog.value = false;
}
function resetRows() {
  applyRows(targets(), () => ({ targetTable: '', targetComment: '', partitionRule: '', bucketRule: '', replicaCount: 1 }));
}
async function autoMatch() {
  if (!props.config.targetDatabaseId) {
    message.warning('请先选择目标数据库');
    return;
  }
  const current = ++matchId;
  const selectedSources = [...selected.value];
  matching.value = true;
  try {
    const tables = await getDatabaseTables(props.config.targetDatabaseId);
    if (current !== matchId || selectedSources.join('\0') !== selected.value.join('\0')) return;
    const matched = rows.value.filter(
      (row) =>
        targets().includes(row.source) &&
        tables.some(
          (table) => table.name.toLowerCase() === row.source.toLowerCase(),
        ),
    );
    applyRows(
      matched.map((row) => row.source),
      (row) => {
        const table = tables.find(
          (item) => item.name.toLowerCase() === row.source.toLowerCase(),
        )!;
        return {
          targetTable: table.name,
          targetComment: table.comment ?? row.sourceComment,
        };
      },
    );
    message.info(`已匹配 ${matched.length} 张目标表`);
  } finally {
    if (current === matchId) matching.value = false;
  }
}
const ruleTemplates = {
  partitionRule:
    "PARTITION BY RANGE ( `主键` )(\n    PARTITION p202501 VALUES LESS THAN ( '2025-01-01' ),\n    PARTITION p202502 VALUES LESS THAN ( '2025-02-01' ),\n    PARTITION PMAX VALUES LESS THAN ( MAXVALUE )\n)",
  bucketRule: 'DISTRIBUTED BY HASH(`col1`, `col2`) BUCKETS 3',
};
function openRule(type: 'bucketRule' | 'partitionRule', source = '') {
  ruleType.value = type;
  ruleSource.value = source;
  ruleValue.value =
    (source ? draft[type] : props.config[type]) || ruleTemplates[type];
  ruleDialog.value = true;
}
function saveRule() {
  if (ruleSource.value) draft[ruleType.value] = ruleValue.value;
  else {
    const properties = { ...props.config.tableProperties };
    for (const source of targets())
      properties[source] = {
        ...properties[source],
        [ruleType.value]: ruleValue.value,
      };
    update({
      ...props.config,
      [ruleType.value]: ruleValue.value,
      tableProperties: properties,
    });
  }
  ruleDialog.value = false;
}
function addColumn() {
  update({
    ...props.config,
    appendColumns: [
      ...(props.config.appendColumns ?? []),
      { name: '', description: '', type: '', defaultValue: '' },
    ],
  });
}
function patchColumn(index: number, patch: Partial<FullSyncAppendColumn>) {
  update({
    ...props.config,
    appendColumns: (props.config.appendColumns ?? []).map((column, i) =>
      i === index ? { ...column, ...patch } : column,
    ),
  });
}
function appendText(column: FullSyncAppendColumn, key: unknown) {
  if (key === 'name') return column.name;
  if (key === 'description') return column.description;
  if (key === 'defaultValue') return column.defaultValue;
  return '';
}
function appendNumber(column: FullSyncAppendColumn, key: unknown) {
  if (key === 'length') return column.length;
  if (key === 'scale') return column.scale;
  return undefined;
}
function patchAppendText(index: number, key: unknown, value: string) {
  if (key === 'name') patchColumn(index, { name: value });
  if (key === 'description') patchColumn(index, { description: value });
  if (key === 'defaultValue') patchColumn(index, { defaultValue: value });
}
function patchAppendNumber(index: number, key: unknown, value: unknown) {
  const number = typeof value === 'number' ? value : undefined;
  if (key === 'length') patchColumn(index, { length: number });
  if (key === 'scale') patchColumn(index, { scale: number });
}
function removeColumn(index: number) {
  appendIds.value.splice(index, 1);
  update({
    ...props.config,
    appendColumns: (props.config.appendColumns ?? []).filter(
      (_, i) => i !== index,
    ),
  });
}
defineExpose({
  validate: () =>
    rows.value.every((row) => row.targetTable.trim()) &&
    (props.config.appendColumns ?? []).every(
      (column) => column.name.trim() && column.type,
    ),
});
</script>

<template>
  <div class="fsm-step">
    <h3 class="fsm-heading">配置多表映射</h3>
    <p class="fsm-description">
      可以批量配置目标表前缀、分区、分桶、副本规则、添加固定字段等
    </p>
    <div class="fsm-toolbar">
      <div v-if="!readonly" class="fsm-toolbar-buttons">
        <Button class="fsm-primary" disabled>新增</Button>
        <Button
          class="fsm-primary"
          :loading="matching"
          :disabled="!selected.length"
          @click="autoMatch"
          >
自动匹配目标表
</Button>
        <Button class="fsm-primary" :disabled="!selected.length" @click="openPrefix">
配置目标表默认前缀
</Button>
        <Button
          class="fsm-primary"
          :disabled="!selected.length"
          @click="openRule('partitionRule')"
          >
目标表分区设置
</Button>
        <Button
          class="fsm-primary"
          :disabled="!selected.length"
          @click="openRule('bucketRule')"
          >
目标表分桶设置
</Button>
        <Button class="fsm-primary" :disabled="!selected.length" @click="resetRows">
列表重置
</Button>
      </div>
      <Input
        v-model:value="query"
        class="fsm-filter"
        allow-clear
        placeholder="请输入来源物理表名称或来源中文表名称"
        aria-label="筛选来源表"
      />
    </div>
    <Table
      class="fsm-table"
      :columns="columns"
      :data-source="visibleRows"
      :pagination="false"
      :row-selection="selection"
      :scroll="{ x: 1450 }"
      row-key="source"
      size="small"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'index'">{{ index + 1 }}</template>
        <template v-else-if="column.key === 'targetTable'">
          <span v-if="readonly">{{ record.targetTable }}</span>
          <Input
            v-else
            :value="record.targetTable"
            placeholder="请输入目标物理表名称"
            :aria-label="`${record.source}目标物理表名称`"
            @update:value="patchRow(record.source, { targetTable: $event })"
          />
        </template>
        <template v-else-if="column.key === 'targetComment'">
          <span v-if="readonly">{{ record.targetComment }}</span>
          <Input
            v-else
            :value="record.targetComment"
            placeholder="请输入目标中文表名称"
            :aria-label="`${record.source}目标中文表名称`"
            @update:value="patchRow(record.source, { targetComment: $event })"
          />
        </template>
        <template v-else-if="column.key === 'partitionRule'">
{{
          record.partitionRule ? '已配置分区' : '不分区'
        }}
</template>
        <template v-else-if="column.key === 'bucketRule'">
{{
          record.bucketRule ? '已配置分桶' : '不分桶'
        }}
</template>
        <template v-else-if="column.key === 'replicaCount'">
          <span v-if="readonly">{{ record.replicaCount }}</span>
          <InputNumber
            v-else
            :value="record.replicaCount"
            :min="1"
            :precision="0"
            :aria-label="`${record.source}副本数量`"
            @update:value="
              patchRow(record.source, { replicaCount: $event ?? 1 })
            "
          />
        </template>
        <template v-else-if="column.key === 'actions'">
          <div class="fsm-actions">
            <button type="button" @click="editRow(record)">
              {{ readonly ? '配置详情' : '配置' }}
            </button>
            <button v-if="!readonly" type="button" disabled>
              设计流程
            </button>
            <button
              v-if="!readonly"
              type="button"
              class="fsm-danger"
              @click="removeRow(record.source)"
            >
              <ReferenceIcon name="delete" />删除
            </button>
          </div>
        </template>
      </template>
      <template #emptyText><div class="fsm-empty">暂无记录</div></template>
    </Table>
    <section class="fsm-append">
      <h3 class="fsm-heading">追加字段</h3>
      <p class="fsm-description">
        自动建目标表时，将在原表基础上追加以下字段：
      </p>
      <Button
        v-if="!readonly"
        class="fsm-outline fsm-add-column"
        @click="addColumn"
        >
<ReferenceIcon name="add" />新增
</Button>
      <Table
        class="fsm-table fsm-append-table"
        :columns="appendColumns"
        :data-source="appendRows"
        :pagination="false"
        :scroll="{ x: 1250 }"
        row-key="key"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'index'">
{{
            record.index + 1
          }}
</template>
          <template v-else-if="column.key === 'type'">
<span v-if="readonly">{{ record.type }}</span><Select
              v-else
              :value="record.type || undefined"
              :options="dataTypes"
              placeholder="请选择数据类型"
              :aria-label="`追加字段${record.index + 1}数据类型`"
              @update:value="
                patchColumn(record.index, { type: String($event) })
              "
          />
</template>
          <template
            v-else-if="column.key === 'length' || column.key === 'scale'"
            >
<span v-if="readonly">{{ appendNumber(record, column.key) }}</span><InputNumber
              v-else
              :value="appendNumber(record, column.key)"
              :min="0"
              :precision="0"
              :placeholder="
                column.key === 'length' ? '请输入属性长度' : '请输入小数位'
              "
              @update:value="
                patchAppendNumber(record.index, column.key, $event)
              "
          />
</template>
          <template v-else-if="column.key === 'actions'">
<button
              v-if="!readonly"
              type="button"
              class="fsm-danger fsm-delete-column"
              @click="removeColumn(record.index)"
            >
              <ReferenceIcon name="delete" />删除
            </button>
</template>
          <template v-else>
<span v-if="readonly">{{ appendText(record, column.key) }}</span><Input
              v-else
              :value="appendText(record, column.key)"
              :placeholder="
                {
                  name: '请输入字段名称',
                  description: '请输入字段描述',
                  defaultValue: '请输入默认值',
                }[column.key as 'defaultValue' | 'description' | 'name']
              "
              @update:value="
                patchAppendText(record.index, column.key, $event)
              "
          />
</template>
        </template>
        <template #emptyText>
<div class="fsm-empty fsm-empty-large">
            <img
              src="/assets/qdata-fullsync/mapping-empty.png"
              alt=""
              aria-hidden="true"
            /><span>暂无记录</span>
          </div>
</template>
      </Table>
    </section>
    <Modal
      v-model:open="tableDialog"
      class="fsm-dialog"
      :title="readonly ? '配置详情' : '配置'"
      :width="800"
      :footer="readonly ? null : undefined"
      ok-text="确定"
      cancel-text="取消"
      @ok="saveRow"
    >
      <div class="fsm-form">
        <label>来源物理表名称</label><Input :value="draft.source" disabled />
        <label>来源中文表名称</label><Input :value="draft.sourceComment" disabled />
        <label><span class="fsm-required">*</span> 目标物理表名称</label><Input
          v-model:value="draft.targetTable"
          :disabled="readonly"
          placeholder="请输入目标物理表名称"
        />
        <label>目标中文表名称</label><Input
          v-model:value="draft.targetComment"
          :disabled="readonly"
          placeholder="请输入目标中文表名称"
        />
        <label>分区规则</label><Button
          class="fsm-primary fsm-rule-button"
          @click="openRule('partitionRule', draft.source)"
          >
{{ readonly ? '查看分区规则' : '配置分区规则' }}
</Button>
        <label>分桶规则</label><Button
          class="fsm-primary fsm-rule-button"
          @click="openRule('bucketRule', draft.source)"
          >
{{ readonly ? '查看分桶规则' : '配置分桶规则' }}
</Button>
      </div>
    </Modal>
    <Modal
      v-model:open="ruleDialog"
      class="fsm-dialog fsm-rule-dialog"
      :title="
        ruleType === 'partitionRule' ? '目标表分区设置' : '目标表分桶设置'
      "
      :width="800"
      :footer="readonly ? null : undefined"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="saveRule"
    >
      <SqlEditor
        v-if="ruleDialog"
        v-model="ruleValue"
        height="540px"
        :readonly="readonly"
        :minimap="false"
        :folding="false"
        :font-size="14"
        :line-numbers-min-chars="3"
        render-validation-decorations="off"
      />
    </Modal>
    <Modal
      v-model:open="prefixDialog"
      class="fsm-dialog fsm-prefix-dialog"
      title="配置目标表默认前缀"
      :width="520"
      centered
      ok-text="确定"
      cancel-text="取消"
      @ok="savePrefix"
      >
<div class="fsm-prefix-input">
        <Input
          v-model:value="prefix"
          placeholder="请输入目标表默认前缀"
        />
</div>
</Modal>
</div>
</template>

<style src="./mapping.css"></style>
