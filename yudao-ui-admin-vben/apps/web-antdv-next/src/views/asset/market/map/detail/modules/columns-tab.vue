<script lang="ts" setup>
import type { TablePaginationConfig } from 'antdv-next';

import type { DataAssetApi } from '#/api/asset/dataasset';

import { computed, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  CheckboxGroup,
  Dropdown,
  Input,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import { getSimpleLevelList } from '#/api/governance/level';
import ListPagination from '#/components/list-pagination/index.vue';

import { columnDisplayName } from '../../data';
import ColumnForm from './column-form.vue';
import TermBindForm from './term-bind-form.vue';

import '#/components/detail-fill-table.css';

defineOptions({ name: 'AssetMarketMapColumnsTab' });

const props = defineProps<{
  assetId: number | string;
  columns: DataAssetApi.MetadataColumn[];
  loading?: boolean;
}>();
const emit = defineEmits<{ (e: 'refresh'): void }>();
const { hasAccessByCodes } = useAccess();

const keyword = ref('');
const page = ref(1);
const pageSize = ref(10);
const levelNames = ref<Record<number, { color?: string; name: string }>>({});

const filteredColumns = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) return props.columns;
  return props.columns.filter(
    (column) =>
      column.columnName?.toLowerCase().includes(text) ||
      columnDisplayName(column).toLowerCase().includes(text) ||
      column.termName?.toLowerCase().includes(text),
  );
});

const pagination = computed<TablePaginationConfig>(() => ({
  current: page.value,
  pageSize: pageSize.value,
  placement: ['none'],
  onChange: changePage,
}));

function changePage(current: number, size: number) {
  page.value = current;
  pageSize.value = size;
}

watch([() => String(props.assetId), keyword], () => {
  page.value = 1;
});

watch(
  () => filteredColumns.value.length,
  (total) => {
    page.value = Math.min(
      page.value,
      Math.max(1, Math.ceil(total / pageSize.value)),
    );
  },
);

const baseColumns = [
  { title: '编号', key: 'id', dataIndex: 'id', width: 70 },
  { title: '中文名称', key: 'displayName', width: 150 },
  { title: '英文名称', key: 'columnName', dataIndex: 'columnName', width: 170 },
  { title: '描述', key: 'description', ellipsis: true, minWidth: 160 },
  { title: '字段类型', key: 'dataType', dataIndex: 'dataType', width: 100 },
  { title: '字段长度', key: 'dataLength', width: 90 },
  { title: '小数位', key: 'dataScale', width: 70 },
  { title: '是否可空', key: 'nullableFlag', width: 90 },
  { title: '是否主键', key: 'pkFlag', width: 90 },
  { title: '数据分类', key: 'safetyLevel', width: 100 },
  { title: '字段术语', key: 'term', width: 130 },
  { title: '关联代码表', key: 'codeTable', width: 150 },
  { title: '关联数据元', key: 'element', width: 150 },
  { title: '操作', key: 'actions', width: 180, fixed: 'right' as const },
];
const visibleKeys = ref(baseColumns.map((column) => column.key));
const tableColumns = computed(() =>
  baseColumns.filter((column) => visibleKeys.value.includes(column.key)),
);

const [ColumnFormModal, columnFormApi] = useVbenModal({
  connectedComponent: ColumnForm,
  destroyOnClose: true,
});
const [TermBindModal, termBindApi] = useVbenModal({
  connectedComponent: TermBindForm,
  destroyOnClose: true,
});

function handleEdit(column: DataAssetApi.MetadataColumn) {
  if (column.id === undefined || !column.resourceUrn) return;
  columnFormApi.setData({ assetId: props.assetId, column }).open();
}

function handleBindTerm(column: DataAssetApi.MetadataColumn) {
  if (column.id === undefined || !column.resourceUrn) return;
  termBindApi.setData({ assetId: props.assetId, column }).open();
}

onMounted(async () => {
  if (!hasAccessByCodes(['governance:level:query'])) return;
  const levels = await getSimpleLevelList().catch(() => []);
  const map: Record<number, { color?: string; name: string }> = {};
  levels.forEach((level) => {
    map[level.id] = {
      name: level.safetyName || level.safetyLevel || `等级#${level.id}`,
      color: level.color,
    };
  });
  levelNames.value = map;
});
</script>

<template>
  <div class="columns-tab">
    <ColumnFormModal @success="emit('refresh')" />
    <TermBindModal @success="emit('refresh')" />
    <div class="columns-tab__toolbar">
      <Button aria-label="刷新字段" size="small" @click="emit('refresh')">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
      </Button>
      <Dropdown :trigger="['click']">
        <Button aria-label="表格设置" size="small">
          <IconifyIcon icon="lucide:grid-2x2" />
        </Button>
        <template #popupRender>
          <div class="columns-tab__settings" @click.stop>
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="搜索字段名 / 中文名 / 术语"
              size="small"
            />
            <CheckboxGroup
              v-model:value="visibleKeys"
              :options="
                baseColumns.map((column) => ({
                  label: column.title,
                  value: column.key,
                }))
              "
            />
          </div>
        </template>
      </Dropdown>
    </div>
    <Table
      class="detail-fill-table"
      :columns="tableColumns"
      :data-source="filteredColumns"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1500 }"
      :row-key="
        (row: DataAssetApi.MetadataColumn) =>
          String(row.id ?? row.resourceUrn ?? row.columnName)
      "
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'displayName'">
          <span class="font-medium">{{
            columnDisplayName(record as any)
          }}</span>
        </template>
        <template v-else-if="column.key === 'columnName'">
          <span class="font-mono text-sm">{{
            (record as any).columnName
          }}</span>
        </template>
        <template v-else-if="column.key === 'description'">
          <Tooltip
            :title="
              (record as any).businessDefinition ||
              (record as any).customComment
            "
          >
            {{
              (record as any).businessDefinition ||
              (record as any).customComment ||
              '-'
            }}
          </Tooltip>
        </template>
        <template v-else-if="column.key === 'dataLength'">
          {{
            (record as any).dataLength ?? (record as any).dataPrecision ?? '-'
          }}
        </template>
        <template v-else-if="column.key === 'dataScale'">
          {{ (record as any).dataScale ?? 0 }}
        </template>
        <template v-else-if="column.key === 'nullableFlag'">
          <Tag :bordered="false" color="blue">
            {{
              (record as DataAssetApi.MetadataColumn).nullableFlag === 1
                ? '是'
                : '否'
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'pkFlag'">
          <Tag :bordered="false" color="purple">
            {{
              (record as DataAssetApi.MetadataColumn).pkFlag === 1 ? '是' : '否'
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'safetyLevel'">
          <Tag
            v-if="
              (record as any).safetyLevelId &&
              levelNames[(record as any).safetyLevelId]
            "
            :color="
              levelNames[(record as any).safetyLevelId]?.color || 'orange'
            "
          >
            {{ levelNames[(record as any).safetyLevelId]?.name }}
          </Tag>
          <Tag v-else-if="(record as any).safetyLevelId" color="orange">
            等级 #{{ (record as any).safetyLevelId }}
          </Tag>
          <Tag v-else-if="(record as any).sensitiveFlag === 1" color="red">
            敏感
          </Tag>
          <span v-else>-</span>
        </template>
        <template v-else-if="column.key === 'term'">
          <Tag
            v-if="
              (record as any).termId !== undefined &&
              (record as any).termId !== null
            "
            color="purple"
          >
            {{ (record as any).termName || `术语 #${(record as any).termId}` }}
          </Tag>
          <span v-else>-</span>
        </template>
        <template v-else-if="column.key === 'element'">
          <Tooltip
            v-if="(record as any).standardElementId"
            :title="(record as any).standardElementCode"
          >
            <Tag color="geekblue">
              {{
                (record as any).standardElementName ||
                `#${(record as any).standardElementId}`
              }}
            </Tag>
          </Tooltip>
          <span v-else>-</span>
        </template>
        <template v-else-if="column.key === 'codeTable'">-</template>
        <template v-else-if="column.key === 'actions'">
          <Tooltip
            v-if="
              !(record as DataAssetApi.MetadataColumn).id ||
              !(record as DataAssetApi.MetadataColumn).resourceUrn
            "
            title="该定版快照缺少字段身份，请从最新元数据维护；生成含字段身份的新版本并刷新资产后可在此编辑。"
          >
            <span class="text-muted-foreground text-xs">定版字段只读</span>
          </Tooltip>
          <Button
            v-access:code="'asset:data-asset:update'"
            :disabled="
              !(record as DataAssetApi.MetadataColumn).id ||
              !(record as DataAssetApi.MetadataColumn).resourceUrn
            "
            size="small"
            type="link"
            @click="handleEdit(record as any)"
          >
            <template #icon><IconifyIcon icon="lucide:pencil" /></template>
            修改
          </Button>
          <Button
            v-access:code="'asset:data-asset:term'"
            :disabled="
              !(record as DataAssetApi.MetadataColumn).id ||
              !(record as DataAssetApi.MetadataColumn).resourceUrn
            "
            size="small"
            type="link"
            @click="handleBindTerm(record as any)"
          >
            <template #icon><IconifyIcon icon="lucide:hand" /></template>
            绑定术语
          </Button>
        </template>
      </template>
    </Table>
    <ListPagination
      :current="page"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      :total="filteredColumns.length"
      aria-label="资产字段分页"
      @change="changePage"
    />
  </div>
</template>

<style scoped>
.columns-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.columns-tab__toolbar {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  height: 48px;
  padding-top: 6px;
}

.columns-tab__settings {
  display: grid;
  gap: 14px;
  width: 300px;
  padding: 16px;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  box-shadow: 0 4px 12px #0002;
}

.columns-tab__settings :deep(.ant-checkbox-group) {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
</style>

<style scoped src="../../../../table-font.css"></style>
