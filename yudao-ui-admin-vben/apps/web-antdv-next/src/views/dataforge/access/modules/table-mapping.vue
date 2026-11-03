<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { computed, h, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Input,
  InputNumber,
  Modal,
  Table,
  Tag,
} from 'antdv-next';

import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import DorisOptionsModal from './doris-options-modal.vue';
import {
  copyDorisOptions,
  getTableMapping,
  targetTableComment,
  targetTableName,
  updateTableMappings,
  validateTableMappings,
} from './table-mapping';

const props = defineProps<{
  doris: boolean;
  loading?: boolean;
  modelValue: Record<string, DataforgeAccessApi.TableMapping>;
  rows: DataforgeAccessApi.PreviewNameMapper[];
}>();
const emit = defineEmits<{
  remove: [name: string];
  selectSources: [];
  'update:modelValue': [value: Record<string, DataforgeAccessApi.TableMapping>];
}>();
const search = ref('');
const selectedNames = ref<string[]>([]);
const error = ref('');
const prefixOpen = ref(false);
const prefix = ref('');
const configOpen = ref(false);
const configNames = ref<string[]>([]);
const configSection = ref<'all' | 'distribution' | 'partition'>('all');
const configOptions = ref<DataforgeAccessApi.DorisOptions>();
const configTitle = ref('');
const visibleRows = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return props.rows.filter((row) =>
    [row.originalName, row.remarks ?? ''].some((text) =>
      text.toLowerCase().includes(keyword),
    ),
  );
});
const selectedRows = computed(() =>
  props.rows.filter((row) => selectedNames.value.includes(row.originalName)),
);
const columns = computed(() => [
  {
    title: '来源物理表名称',
    dataIndex: 'originalName',
    key: 'source',
    width: 170,
    ellipsis: true,
  },
  {
    title: '来源中文表名称',
    dataIndex: 'remarks',
    key: 'remarks',
    width: 190,
    ellipsis: false,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '目标物理表名称', key: 'targetName', width: 230 },
  { title: '目标中文表名称', key: 'targetComment', width: 230 },
  ...(props.doris
    ? [
        { title: '分区规则', key: 'partition', width: 115 },
        { title: '分桶规则', key: 'distribution', width: 150 },
        { title: '副本数量', key: 'replication', width: 100 },
      ]
    : []),
  {
    title: '操作',
    key: 'actions',
    width: props.doris ? 160 : 90,
    fixed: 'right' as const,
  },
]);

watch(
  () => props.rows,
  (rows) => {
    const available = new Set(rows.map((row) => row.originalName));
    selectedNames.value = selectedNames.value.filter((name) =>
      available.has(name),
    );
    if (configNames.value.some((name) => !available.has(name)))
      configOpen.value = false;
  },
);
watch(
  () => props.doris,
  () => {
    configOpen.value = false;
  },
);

function update(
  names: string[],
  change: (
    mapping: DataforgeAccessApi.TableMapping,
    name: string,
  ) => DataforgeAccessApi.TableMapping,
) {
  emit(
    'update:modelValue',
    updateTableMappings(props.modelValue, names, change),
  );
  error.value = '';
}

function editTarget(
  name: string,
  field: 'targetTableComment' | 'targetTableName',
  value: string,
) {
  update([name], (mapping) => ({ ...mapping, [field]: value }));
}

function matchTargets() {
  const rows = new Map(
    selectedRows.value.map((row) => [row.originalName, row]),
  );
  update([...rows.keys()], (mapping, name) => ({
    ...mapping,
    targetTableName: rows.get(name)!.targetName,
    targetTableComment: rows.get(name)!.remarks ?? '',
  }));
}

function openPrefix() {
  prefix.value = '';
  prefixOpen.value = true;
}

function applyPrefix() {
  const rows = new Map(
    selectedRows.value.map((row) => [row.originalName, row]),
  );
  update([...rows.keys()], (mapping, name) => ({
    ...mapping,
    targetTableName: `${prefix.value}${rows.get(name)!.targetName}`,
  }));
  prefixOpen.value = false;
}

function resetMappings() {
  update(
    props.rows.map((row) => row.originalName),
    (mapping) => {
      delete mapping.targetTableName;
      delete mapping.targetTableComment;
      if (props.doris) delete mapping.dorisOptions;
      return mapping;
    },
  );
  selectedNames.value = [];
}

function openConfig(
  section: 'all' | 'distribution' | 'partition',
  name?: string,
) {
  configNames.value = name === undefined ? [...selectedNames.value] : [name];
  configSection.value = section;
  const allConfigured = configNames.value.every((table) =>
    Boolean(getTableMapping(props.modelValue, table).dorisOptions),
  );
  configOptions.value =
    configNames.value.length === 1 || (section === 'partition' && allConfigured)
      ? getTableMapping(props.modelValue, configNames.value[0]!).dorisOptions
      : undefined;
  configTitle.value =
    name === undefined
      ? `批量设置${section === 'partition' ? '分区' : '分桶与副本'}（${configNames.value.length} 张表）`
      : `${name} · Doris 建表配置`;
  configOpen.value = true;
}

function applyDoris(options: DataforgeAccessApi.DorisOptions) {
  update(configNames.value, (mapping) => {
    const previous = copyDorisOptions(mapping.dorisOptions);
    const incoming = copyDorisOptions(options);
    let dorisOptions = incoming;
    if (configSection.value === 'partition') {
      dorisOptions = {
        ...(mapping.dorisOptions ? previous : incoming),
        partition: incoming.partition,
      };
    } else if (configSection.value === 'distribution') {
      dorisOptions = { ...incoming, partition: previous.partition };
    }
    return { ...mapping, dorisOptions };
  });
}

function setReplication(name: string, replicationNum: null | number | string) {
  update([name], (mapping) => ({
    ...mapping,
    dorisOptions: {
      ...copyDorisOptions(mapping.dorisOptions),
      replicationNum: typeof replicationNum === 'number' ? replicationNum : 0,
    },
  }));
}

function validate() {
  error.value = validateTableMappings(
    props.rows,
    props.modelValue,
    props.doris,
  );
  return !error.value;
}

defineExpose({ validate });
</script>

<template>
  <div class="space-y-3">
    <Alert v-if="error" :message="error" type="error" show-icon />
    <div class="flex flex-wrap items-center gap-2">
      <Button type="primary" :disabled="loading" @click="emit('selectSources')">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>新增
      </Button>
      <Button
        type="primary"
        :disabled="!selectedRows.length || loading"
        @click="matchTargets"
      >
        <template #icon><IconifyIcon icon="lucide:wand-sparkles" /></template>
        自动匹配目标表
      </Button>
      <Button
        type="primary"
        :disabled="!selectedRows.length || loading"
        @click="openPrefix"
      >
        <template #icon><IconifyIcon icon="lucide:settings" /></template>
        配置目标表默认前缀
      </Button>
      <Button
        v-if="doris"
        type="primary"
        :disabled="!selectedRows.length || loading"
        @click="openConfig('partition')"
      >
        <template #icon><IconifyIcon icon="lucide:panels-top-left" /></template>
        目标表分区设置
      </Button>
      <Button
        v-if="doris"
        type="primary"
        :disabled="!selectedRows.length || loading"
        @click="openConfig('distribution')"
      >
        <template #icon><IconifyIcon icon="lucide:grid-2x2" /></template>
        目标表分桶设置
      </Button>
      <Button
        type="primary"
        :disabled="!rows.length || loading"
        @click="resetMappings"
      >
        <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>
        列表重置
      </Button>
      <Input
        v-model:value="search"
        class="ml-auto w-72"
        aria-label="搜索来源表"
        placeholder="搜索来源物理表名称或中文名称"
        allow-clear
      />
    </div>
    <p class="text-muted-foreground text-xs">
      已选择 {{ selectedRows.length }} /
      {{ rows.length }} 张表，批量配置作用于勾选的表。
    </p>
    <Table
      :columns="columns"
      :data-source="visibleRows"
      :loading="loading"
      :pagination="false"
      row-key="originalName"
      :scroll="{ x: doris ? 1280 : 900, y: 420 }"
      :row-selection="{
        selectedRowKeys: selectedNames,
        onChange: (keys) => {
          selectedNames = keys.map(String);
        },
        preserveSelectedRowKeys: true,
      }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'source'">
          {{ record.originalName }}
        </template>
        <template v-else-if="column.key === 'remarks'">
          {{ record.remarks || '-' }}
        </template>
        <template v-else-if="column.key === 'targetName'">
          <Input
            :value="targetTableName(record, modelValue)"
            :disabled="loading"
            :aria-label="`${record.originalName} 目标表名称`"
            placeholder="请输入目标物理表名称"
            :status="
              targetTableName(record, modelValue).trim() ? undefined : 'error'
            "
            @update:value="
              editTarget(record.originalName, 'targetTableName', $event)
            "
          />
        </template>
        <template v-else-if="column.key === 'targetComment'">
          <Input
            :value="targetTableComment(record, modelValue)"
            :disabled="loading"
            :aria-label="`${record.originalName} 目标表说明`"
            placeholder="请输入目标中文表名称"
            @update:value="
              editTarget(record.originalName, 'targetTableComment', $event)
            "
          />
        </template>
        <template v-else-if="column.key === 'partition'">
          <Tag>
            {{
              getTableMapping(modelValue, record.originalName).dorisOptions
                ? (getTableMapping(modelValue, record.originalName).dorisOptions
                    ?.partition?.kind ?? '不分区')
                : '默认'
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'distribution'">
          <span
            v-if="getTableMapping(modelValue, record.originalName).dorisOptions"
            class="text-xs"
            >{{
              getTableMapping(modelValue, record.originalName).dorisOptions
                ?.distributionType
            }}
            /
            {{
              getTableMapping(modelValue, record.originalName).dorisOptions
                ?.bucketMode === 'FIXED'
                ? `${getTableMapping(modelValue, record.originalName).dorisOptions?.bucketCount} 桶`
                : '自动分桶'
            }}</span
          >
          <span v-else class="text-xs">默认</span>
        </template>
        <template v-else-if="column.key === 'replication'">
          <InputNumber
            :value="
              getTableMapping(modelValue, record.originalName).dorisOptions
                ?.replicationNum ?? 1
            "
            :disabled="
              loading ||
              !getTableMapping(modelValue, record.originalName).dorisOptions
            "
            :min="1"
            :precision="0"
            class="w-20"
            :aria-label="`${record.originalName} 副本数量`"
            @update:value="setReplication(record.originalName, $event)"
          />
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button
            v-if="doris"
            type="link"
            size="small"
            :disabled="loading"
            :aria-label="`配置 ${record.originalName}`"
            @click="openConfig('all', record.originalName)"
          >
            <template #icon><IconifyIcon icon="lucide:settings" /></template>
            配置
          </Button>
          <Button
            type="link"
            size="small"
            danger
            :disabled="loading"
            :aria-label="`删除 ${record.originalName}`"
            @click="emit('remove', record.originalName)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>删除
          </Button>
        </template>
      </template>
    </Table>
    <Modal
      v-model:open="prefixOpen"
      title="配置目标表默认前缀"
      destroy-on-hidden
      ok-text="确定"
      cancel-text="取消"
      @ok="applyPrefix"
    >
      <p class="text-muted-foreground mb-3 text-sm">
        基于默认映射名称生成目标表名；可在列表中继续单独修改。
      </p>
      <Input
        v-model:value="prefix"
        aria-label="目标表默认前缀"
        placeholder="如 ods_"
      />
    </Modal>
    <DorisOptionsModal
      v-if="doris"
      v-model:open="configOpen"
      :options="configOptions"
      :section="configSection"
      :title="configTitle"
      @save="applyDoris"
    />
  </div>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
