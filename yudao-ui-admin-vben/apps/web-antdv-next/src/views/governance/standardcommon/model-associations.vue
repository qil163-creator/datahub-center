<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, Table } from 'antdv-next';

import { getStandardCodeSetModelFields } from '#/api/governance/standardcodeset';
import { getStandardElementModelFields } from '#/api/governance/standardelement';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardPagination from './standard-pagination.vue';
import StandardToolbar from './standard-toolbar.vue';

const props = defineProps<{ kind: 'codeset' | 'element'; ownerId: number | string }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canQueryModels = computed(() => hasAccessByCodes(['modeling:model:query']));
const router = useRouter();
const route = useRoute();
type Field = Awaited<ReturnType<typeof getStandardElementModelFields>>[number];
const fields = ref<Field[]>([]);
const loading = ref(false);
const failed = ref(false);
const page = ref(1);
const pageSize = ref(10);
const users = ref<Record<string, string>>({});
const request = useObjectRequest(
  () => JSON.stringify([props.kind, props.ownerId, access.accessToken, access.accessCodes]),
  () => { fields.value = []; users.value = {}; loading.value = false; },
);

const statusLabels: Record<string, string> = {
  DISCARDED: '已废弃', DRAFT: '草稿', PUBLISHED: '已发布',
};
const models = computed(() => {
  const grouped = new Map<string, { created: string; creator: string; description: string; domain: string; fields: string[]; id: string; name: string; source: string; status: string; subject: string; table: string; }>();
  for (const field of fields.value) {
    const id = String(field.modelId);
    let model = grouped.get(id);
    if (!model) {
      model = {
        id, name: field.modelName || '-', table: field.tableName || '-',
        status: field.enabled == null ? (field.modelStatus ? (statusLabels[field.modelStatus] ?? field.modelStatus) : '-') : (field.enabled === 1 ? '启用' : '停用'),
        fields: [], domain: field.dataDomainName || '-', subject: field.subjectDomainName || '-',
        source: field.sourceInfo || '-', creator: field.creator ? (users.value[field.creator] || `用户 ${field.creator}`) : '-',
        created: field.createTime ? formatDate(field.createTime, 'YYYY-MM-DD HH:mm') : '-', description: field.description || '-',
      };
      grouped.set(id, model);
    }
    const name = field.cnName ? `${field.cnName}（${field.fieldName}）` : field.fieldName;
    if (name && !model.fields.includes(name)) model.fields.push(name);
  }
  return [...grouped.values()];
});
const visibleRows = computed(() => models.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));
const definitions = [
  { key: 'id', dataIndex: 'id', title: '编号', width: 100 },
  { key: 'model', dataIndex: 'model', title: '逻辑模型信息', width: 280 },
  { key: 'name', dataIndex: 'name', title: '中文名称', width: 180 },
  { key: 'domain', dataIndex: 'domain', title: '业务板块', width: 180 },
  { key: 'subject', dataIndex: 'subject', title: '主题域', width: 160 },
  { key: 'source', dataIndex: 'source', title: '来源信息', width: 180 },
  { key: 'status', dataIndex: 'status', title: '状态', width: 110 },
  { key: 'creator', dataIndex: 'creator', title: '创建人', width: 140 },
  { key: 'created', dataIndex: 'created', title: '创建时间', width: 170 },
  { key: 'fields', dataIndex: 'fields', title: '关联字段', width: 240 },
  { key: 'actions', dataIndex: 'actions', title: '操作', width: 100 },
];
const visibleColumns = ref(definitions.map((column) => column.key));
const columns = computed(() => definitions.filter((column) => visibleColumns.value.includes(column.key)));
async function load() {
  const current = request.begin();
  page.value = 1;
  fields.value = [];
  failed.value = false;
  if (!props.ownerId || !canQueryModels.value) return;
  loading.value = true;
  try {
    const result = props.kind === 'element'
      ? await getStandardElementModelFields(props.ownerId)
      : await getStandardCodeSetModelFields(props.ownerId);
    if (current()) fields.value = result;
    if (result.some((row) => row.creator)) {
      const list = await getSimpleUserList().catch(() => []);
      if (current()) users.value = Object.fromEntries(list.map((user) => [String(user.id), user.nickname || String(user.id)]));
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
watch(() => [props.kind, props.ownerId, access.accessToken, access.accessCodes], () => {
  page.value = 1;
  void load();
}, { immediate: true, deep: true });
</script>

<template>
  <section class="standard-model-associations">
    <StandardToolbar v-model:visible-columns="visibleColumns" :columns="definitions" hide-search settings-icon="lucide:settings" @refresh="load">
      <template #title><span class="text-muted-foreground">展示当前数据元或代码表关联的逻辑模型及相关字段，便于查看标准在数据建模中的应用情况。</span></template>
    </StandardToolbar>
    <Alert v-if="!canQueryModels" class="mb-3" type="warning" message="暂无逻辑模型查询权限" show-icon />
    <template v-else-if="failed"></template>
    <Table v-if="canQueryModels" :columns="columns" :data-source="visibleRows" :loading="loading" :pagination="false" :scroll="{ x: 1600 }" row-key="id" size="small">
      <template #bodyCell="{ column, record }">
        <span v-if="column.dataIndex === 'fields'">{{ record.fields.join('、') || '-' }}</span>
        <div v-else-if="column.dataIndex === 'model'">{{ record.name }}（{{ record.table }}）<div class="text-muted-foreground">{{ record.description }}</div></div>
        <Button v-else-if="column.dataIndex === 'actions' && hasAccessByCodes(['modeling:model:query'])" type="link" @click="router.push({ name: 'ModelingTableModelDetail', params: { id: record.id }, query: { from: route.fullPath } })">详情</Button>
      </template>
    </Table>
    <StandardPagination v-if="canQueryModels" v-model:page="page" v-model:page-size="pageSize" :total="models.length" />
  </section>
</template>

<style scoped>
.standard-model-associations :deep(.standard-toolbar) {
  min-height: 30px;
  margin-bottom: 8px;
}

.standard-model-associations :deep(.standard-toolbar-tools) {
  gap: 8px;
}

.standard-model-associations :deep(.standard-toolbar-tools .ant-btn) {
  width: 26px;
  min-width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 50%;
}

.standard-model-associations :deep(.standard-toolbar-tools svg) {
  width: 12px;
  height: 12px;
}

.standard-model-associations :deep(.ant-table-thead > tr > th) {
  padding: 7px 10px;
  font-size: 12px;
  line-height: 20px;
}

.standard-model-associations :deep(.ant-table-tbody > tr > td) {
  padding: 8px 10px;
  font-size: 12px;
}

.standard-model-associations :deep(.ant-table-content) {
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.standard-model-associations :deep(.ant-table-content::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.standard-model-associations :deep(.ant-table-content::-webkit-scrollbar-thumb) {
  background: hsl(var(--muted-foreground) / 20%);
  border-radius: 3px;
}

.standard-model-associations :deep(.ant-table-content::-webkit-scrollbar-track) {
  background: transparent;
}
</style>
