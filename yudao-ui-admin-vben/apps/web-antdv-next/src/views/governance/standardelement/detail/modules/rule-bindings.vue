<script setup lang="ts">
import type { StandardElementRuleApi } from '#/api/governance/standardelement/rule';

import { computed, h, nextTick, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { confirm } from '@vben/common-ui';
import { IconifyIcon, Plus } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Button, message, Table } from 'antdv-next';

import {
  deleteElementRule,
  getElementRulePage,
} from '#/api/governance/standardelement/rule';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import { emptyImage } from '../../../quality/task/data';
import StandardPagination from '../../../standardcommon/standard-pagination.vue';
import StandardToolbar from '../../../standardcommon/standard-toolbar.vue';
import RuleEditor from './rules/rule-editor.vue';

const props = defineProps<{
  elementId: StandardElementRuleApi.Id;
  kind: StandardElementRuleApi.Kind;
}>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const can = (action: string) =>
  hasAccessByCodes([`governance:standard-element:${action}`]);
const audit = computed(() => props.kind === 'AUDIT');
const page = ref(1);
const pageSize = ref(10);
const rows = ref<StandardElementRuleApi.Rule[]>([]);
const total = ref(0);
const failed = ref(false);
const loading = ref(false);
const editor = ref<InstanceType<typeof RuleEditor>>();
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      props.elementId,
      props.kind,
      page.value,
      pageSize.value,
      access.accessCodes,
    ]),
  () => {
    rows.value = [];
    total.value = 0;
    loading.value = false;
  },
);
const mutations = useObjectRequest(() =>
  JSON.stringify([props.elementId, props.kind, access.accessCodes]),
);
const definitions = computed(() => [
  { key: 'id', title: '编号', dataIndex: 'id', width: 80 },
  {
    key: 'name',
    title: audit.value ? '稽查名称' : '清洗名称',
    dataIndex: 'name',
    width: 220,
  },
  {
    key: 'description',
    title: '描述',
    dataIndex: 'description',
    width: 250,
    ellipsis: false,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  {
    key: 'templateName',
    title: audit.value ? '稽查规则' : '清洗规则',
    dataIndex: 'templateName',
    width: 220,
  },
  ...(audit.value
    ? [
        {
          key: 'dimensionName',
          title: '质量维度',
          dataIndex: 'dimensionName',
          width: 130,
        },
      ]
    : []),
  { key: 'status', title: '状态', width: 110 },
  { key: 'creatorName', title: '创建人', dataIndex: 'creatorName', width: 150 },
  { key: 'createTime', title: '创建时间', width: 190 },
  { key: 'actions', title: '操作', width: 220, fixed: 'right' as const },
]);
const visible = ref<string[]>(definitions.value.map((row) => row.key));
const columns = computed(() =>
  definitions.value.filter((row) => visible.value.includes(row.key)),
);
async function load() {
  const current = requests.begin();
  failed.value = false;
  if (!can('query')) return;
  loading.value = true;
  try {
    const result = await getElementRulePage({
      elementId: props.elementId,
      kind: props.kind,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function remove(row: StandardElementRuleApi.Rule) {
  if (row.id === undefined || !can('update')) return;
  const current = mutations.begin();
  await confirm(`确认删除规则“${row.name}”吗？`);
  if (!current()) return;
  await deleteElementRule(props.elementId, row.id);
  if (current()) {
    message.success('删除成功');
    await load();
  }
}
watch(
  () => [
    props.elementId,
    props.kind,
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
  ],
  async () => {
    page.value = 1;
    await nextTick();
    void load();
  },
  { immediate: true, deep: true },
);
</script>
<template>
  <div class="standard-rule-bindings">
    <StandardToolbar
      v-model:visible-columns="visible"
      :columns="definitions"
      hide-search
      settings-icon="lucide:settings"
      @refresh="load"
    >
      <template #title>
        <Button
          v-if="can('update')"
          size="small"
          type="primary"
          @click="editor?.open()"
        >
          <template #icon><Plus :size="12" /></template>
          关联
        </Button>
        <span v-else aria-hidden="true"></span>
      </template>
    </StandardToolbar>
    <Table
      class="standard-table standard-detail-table"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      row-key="id"
      :scroll="{ x: 1300 }"
    >
      <template #emptyText>
        <div class="standard-empty">
          <img :src="emptyImage" alt="" /><span>暂无记录</span>
        </div>
      </template>
      <template #bodyCell="{ column, record }">
        <span v-if="column.key === 'status'">{{
          record.status === 0 ? '上线' : '下线'
        }}</span>
        <span v-else-if="column.key === 'createTime'">{{
          record.createTime ? formatDate(record.createTime) : '-'
        }}</span>
        <div v-else-if="column.key === 'actions'" class="standard-row-actions">
          <Button
            type="link"
            v-if="can('update')"
            @click="editor?.open(record)"
          >
            <IconifyIcon
              icon="lucide:edit"
              class="mr-1 inline-block size-4 align-middle"
            />
            修改
          </Button>
          <Button
            type="link"
            danger
            v-if="can('update')"
            @click="remove(record)"
          >
            <IconifyIcon
              icon="lucide:trash-2"
              class="mr-1 inline-block size-4 align-middle"
            />
            删除
          </Button>
          <Button type="link" @click="editor?.open(record, true)">
            <IconifyIcon
              icon="lucide:eye"
              class="mr-1 inline-block size-4 align-middle"
            />
            详情
          </Button>
        </div>
      </template>
    </Table>
    <StandardPagination
      v-model:page="page"
      v-model:page-size="pageSize"
      :total="total"
      @change="load"
    />
    <RuleEditor
      ref="editor"
      :element-id="elementId"
      :kind="kind"
      @saved="load"
    />
  </div>
</template>

<style scoped>
.standard-rule-bindings :deep(.standard-toolbar) {
  min-height: 30px;
  margin-bottom: 8px;
}

.standard-rule-bindings :deep(.standard-toolbar-actions) {
  margin-right: auto;
  margin-left: 0;
}

.standard-rule-bindings :deep(.standard-toolbar-tools) {
  gap: 8px;
}

.standard-rule-bindings :deep(.standard-toolbar-tools .ant-btn) {
  width: 26px;
  min-width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 50%;
}

.standard-rule-bindings :deep(.standard-toolbar-tools svg) {
  width: 12px;
  height: 12px;
}

.standard-detail-table :deep(.ant-table-thead > tr > th) {
  padding-block: 7px;
  padding-inline: 10px;
  font-size: 12px;
  line-height: 20px;
  background: hsl(var(--muted));
}

.standard-detail-table :deep(.ant-table-tbody > tr > td) {
  padding-inline: 10px;
  font-size: 12px;
}

.standard-detail-table :deep(.ant-table-content) {
  scrollbar-color: #dfe0e3 transparent;
  scrollbar-width: thin;
}
</style>
