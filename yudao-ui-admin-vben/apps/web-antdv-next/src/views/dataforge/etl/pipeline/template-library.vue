<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  EtlTemplate,
  EtlTemplateGraph,
} from '#/api/dataforge/etl/template';

import { h, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Drawer,
  Input,
  Modal,
  Popconfirm,
  Table,
  TextArea,
} from 'antdv-next';

import {
  createEtlTemplate,
  deleteEtlTemplate,
  getEtlTemplate,
  getEtlTemplatePage,
} from '#/api/dataforge/etl/template';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

const props = defineProps<{ canEdit: boolean }>();
const emit = defineEmits<{ apply: [graph: EtlTemplateGraph] }>();
const accessStore = useAccessStore();
const visible = ref(false);
const loading = ref(false);
const error = ref('');
const rows = ref<EtlTemplate[]>([]);
const keyword = ref('');
const pageNo = ref(1);
const total = ref(0);
const busy = ref(false);
const createVisible = ref(false);
const name = ref('');
const description = ref('');
const fragment = ref<EtlTemplateGraph>({ nodes: [], edges: [] });
const createError = ref('');
const saving = ref(false);
const submitted = ref<Parameters<typeof createEtlTemplate>[0]>();
let listSeq = 0;
let actionSeq = 0;
let createSeq = 0;
const columns = [
  { title: '模板名称', dataIndex: 'name', key: 'name' },
  {
    title: '说明',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '节点数', dataIndex: 'nodeCount', key: 'nodeCount', width: 80 },
  {
    title: '创建人 ID',
    dataIndex: 'ownerUserId',
    key: 'ownerUserId',
    width: 160,
  },
  {
    title: '创建时间',
    key: 'createTime',
    dataIndex: 'createTime',
    width: 170,
    customRender: ({ text }: { text: number }) => formatDateTime(text),
  },
  { title: '操作', key: 'actions', width: 230, fixed: 'right' as const },
];
function close() {
  ++listSeq;
  ++actionSeq;
  ++createSeq;
  visible.value = false;
  createVisible.value = false;
  loading.value = false;
  busy.value = false;
  saving.value = false;
  rows.value = [];
  error.value = '';
  createError.value = '';
  submitted.value = undefined;
}
async function open() {
  close();
  visible.value = true;
  keyword.value = '';
  await load();
}
function openCreate(graph: EtlTemplateGraph) {
  if (!props.canEdit) return;
  close();
  fragment.value = JSON.parse(JSON.stringify(graph));
  name.value = '';
  description.value = '';
  createVisible.value = true;
}
defineExpose({ open, openCreate, close });
async function load(page = 1) {
  const seq = ++listSeq;
  loading.value = true;
  error.value = '';
  pageNo.value = page;
  try {
    const result = await getEtlTemplatePage({
      pageNo: page,
      pageSize: 20,
      name: keyword.value.trim() || undefined,
    });
    if (seq !== listSeq || !visible.value) return;
    rows.value = result.list;
    total.value = result.total;
  } catch {
    // 请求客户端已显示错误。
  } finally {
    if (seq === listSeq) loading.value = false;
  }
}
async function apply(id: DataforgeEtlApi.Id) {
  if (!props.canEdit || busy.value) return;
  const seq = ++actionSeq;
  busy.value = true;
  error.value = '';
  try {
    // Every application reads the server detail anew, checking current object grants.
    const detail = await getEtlTemplate(id);
    if (seq !== actionSeq || !visible.value || !props.canEdit) return;
    emit('apply', { nodes: detail.nodes, edges: detail.edges });
    close();
  } catch {
    if (seq === actionSeq)
      error.value = '模板读取或授权校验失败，当前画布保持不变。';
  } finally {
    if (seq === actionSeq) busy.value = false;
  }
}
async function remove(row: EtlTemplate) {
  if (!props.canEdit || !row.canDelete || busy.value) return;
  const seq = ++actionSeq;
  busy.value = true;
  error.value = '';
  try {
    await deleteEtlTemplate(row.id);
    if (seq === actionSeq && visible.value) await load();
  } catch {
    if (seq === actionSeq) error.value = '删除失败，请刷新确认模板状态后重试。';
  } finally {
    if (seq === actionSeq) busy.value = false;
  }
}
async function save() {
  if (!props.canEdit || saving.value) return;
  if (!name.value.trim()) {
    createError.value = '请输入模板名称。';
    return;
  }
  // Preserve the same request after an uncertain response; retry cannot create duplicates.
  submitted.value ??= {
    ...fragment.value,
    name: name.value.trim(),
    description: description.value.trim() || undefined,
    requestId: crypto.randomUUID(),
  };
  const seq = ++createSeq;
  saving.value = true;
  createError.value = '';
  try {
    await createEtlTemplate(submitted.value);
    if (seq !== createSeq || !createVisible.value) return;
    await open();
  } catch {
    if (seq === createSeq)
      createError.value =
        '保存未确认。可使用相同请求重试，服务器会防止重复创建。';
  } finally {
    if (seq === createSeq) saving.value = false;
  }
}
watch(() => [accessStore.accessToken, accessStore.refreshToken], close);
onBeforeUnmount(close);
</script>

<template>
  <Drawer
    :open="visible"
    title="团队模板"
    size="960"
    :push="false"
    destroy-on-hidden
    @close="close"
  >
    <Alert
      class="mb-3"
      type="info"
      show-icon
      message="模板为平台共享的结构化流程片段。应用时重新校验数据源与治理规则权限，追加到当前草稿，可撤销；保存和发布仍需完整校验。"
    />
    <div class="mb-3 flex gap-2">
      <Input
        v-model:value="keyword"
        placeholder="搜索模板名称"
        :maxlength="100"
        @press-enter="load()"
      /><Button :loading="loading" @click="load()">搜索</Button>
    </div>
    <Alert v-if="error" class="mb-3" type="error" :message="error" />
    <Table
      :scroll="{ x: 'max-content' }"
      :columns="columns"
      :data-source="rows"
      :row-key="(row: EtlTemplate) => String(row.id)"
      :loading="loading"
      :pagination="{
        current: pageNo,
        pageSize: 20,
        total,
        showSizeChanger: false,
      }"
      @change="(pagination) => load(pagination.current ?? 1)"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'actions'">
          <Button
            size="small"
            :disabled="!canEdit || busy"
            @click="apply(record.id)"
          >
            <template #icon><IconifyIcon icon="lucide:workflow" /></template>
            应用到画布
          </Button>
          <Popconfirm
            v-if="canEdit && record.canDelete"
            title="删除此团队模板？已应用的草稿不受影响。"
            :disabled="busy"
            @confirm="remove(record)"
          >
            <Button size="small" danger :disabled="busy">
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </Popconfirm>
        </template>
      </template>
    </Table>
  </Drawer>
  <Modal
    :open="createVisible"
    title="保存为团队模板"
    :confirm-loading="saving"
    :closable="!saving"
    :mask-closable="false"
    :cancel-button-props="{ disabled: saving }"
    :ok-button-props="{ disabled: !canEdit }"
    @ok="save"
    @cancel="close"
  >
    <Alert
      class="mb-3"
      type="info"
      :message="`共享 ${fragment.nodes.length} 个节点及其内部连线。支持结构化配置与整表读取，不共享脚本、外部文件、HTTP、MQ、凭据或流程参数默认值。`"
    />
    <p class="mb-3 text-sm">
      有选中节点时保存选中片段，否则保存整个画布。请确认常量和映射值适合平台团队共享。
    </p>
    <Input
      v-model:value="name"
      class="mb-3"
      :disabled="!!submitted"
      :maxlength="100"
      placeholder="模板名称（必填）"
    />
    <TextArea
      v-model:value="description"
      :disabled="!!submitted"
      :maxlength="500"
      placeholder="用途与适用条件"
    />
    <Alert
      v-if="createError"
      class="mt-3"
      type="error"
      :message="createError"
    />
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
