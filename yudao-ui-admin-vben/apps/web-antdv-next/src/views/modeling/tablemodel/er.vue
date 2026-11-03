<script lang="ts" setup>
import type { TableModelApi } from '#/api/modeling/tablemodel';
import type {
  ErConnectPayload,
  ErNodePosition,
  ErRelationEdge,
  ErTableNode,
} from '#/components/er-designer';

import { computed, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import { Button, Drawer, message, Modal, Tag, TreeSelect } from 'antdv-next';

import { getDwLayerList } from '#/api/modeling/dwlayer';
import {
  deleteTableModelRelation,
  getTableModelDiagram,
  saveTableModelLayout,
} from '#/api/modeling/tablemodel';
import { ErDesigner, RELATION_TYPE_LABELS } from '#/components/er-designer';

import { formatErFieldType, prepareErLayers } from '../modelcommon/er-view';
import { useModelingRequest } from '../modelcommon/use-modeling-request';
import ModelEditor from './modules/model-editor.vue';
import RelationForm from './modules/relation-form.vue';

defineOptions({ name: 'ModelingTableModelEr' });

const STATUS_META: Record<string, { color: string; label: string }> = {
  DRAFT: { label: '草稿', color: '#d97706' },
  PUBLISHED: { label: '已发布', color: '#059669' },
  DISCARDED: { label: '已废弃', color: '#94a3b8' },
};

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const readonly = computed(() => !hasAccessByCodes(['modeling:model:save']));

const layers = ref<ReturnType<typeof prepareErLayers>['options']>([]);
const themeTree = computed(() => handleTree(layers.value));
const layerId = ref<string>();

const diagram = ref<TableModelApi.Diagram>({});
const loading = ref(false);
const access = useAccessStore();
const layersRequest = useModelingRequest();
const diagramRequest = useModelingRequest(
  () => layerId.value,
  () => {
    diagram.value = {};
    loading.value = false;
  },
);
const mutationRequest = useModelingRequest(() => layerId.value);
const relationModalRef = ref<InstanceType<typeof RelationForm>>();
const designerRef = ref<InstanceType<typeof ErDesigner>>();
const editorRef = ref<InstanceType<typeof ModelEditor>>();
const editingId = ref<string>();
const layoutDirty = ref(false);
async function confirmLayoutLeave() {
  if (!layoutDirty.value) return true;
  return new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: '布局尚未保存',
      content: '离开将丢弃当前画布的位置调整，是否继续？',
      okText: '丢弃调整',
      cancelText: '继续编辑',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
}
onBeforeRouteLeave(async () => {
  if (editorRef.value && !(await editorRef.value.confirmLeave())) return false;
  return confirmLayoutLeave();
});
async function changeLayer(value?: string) {
  if (!(await confirmLayoutLeave())) return;
  layerId.value = value;
  layoutDirty.value = false;
  await loadDiagram();
}
async function closeEditor() {
  if (await editorRef.value?.confirmLeave()) {
    editingId.value = undefined;
    await loadDiagram();
  }
}

const nodes = computed<ErTableNode[]>(() =>
  (diagram.value.models ?? []).map((model) => {
    const status = STATUS_META[model.status ?? ''];
    return {
      id: String(model.id),
      title: model.name ?? '',
      subtitle: model.code ?? '',
      badge: status?.label,
      badgeColor: status?.color,
      rows: (model.fields ?? []).map((field) => ({
        name: field.fieldName ?? '',
        label: field.cnName ?? '',
        type: formatErFieldType(field),
        pk: field.pkFlag === true,
      })),
      x: model.posX,
      y: model.posY,
      raw: model,
    };
  }),
);

const edges = computed<ErRelationEdge[]>(() =>
  (diagram.value.relations ?? []).map((relation) => ({
    id: String(relation.id),
    sourceId: String(relation.sourceModelId),
    targetId: String(relation.targetModelId),
    sourceField: relation.sourceField,
    targetField: relation.targetField,
    label: RELATION_TYPE_LABELS[relation.relationType ?? ''] ?? '',
    name: relation.name ?? '',
    // 数仓模型关系为逻辑关联（Virtual Relation），不生成数据库外键
    virtual: true,
    raw: relation,
  })),
);

async function loadThemes() {
  const request = layersRequest.begin();
  const state = prepareErLayers(await getDwLayerList({}), route.query.layerId);
  if (!request()) return false;
  layers.value = state.options;
  layerId.value =
    typeof route.query.layerId === 'string' &&
    state.options.some(
      (layer) =>
        layer.nodeType === 'LAYER' && String(layer.id) === route.query.layerId,
    )
      ? route.query.layerId
      : undefined;
  return true;
}

async function loadDiagram() {
  const request = diagramRequest.begin();
  const selectedLayer = layerId.value;
  diagram.value = {};
  loading.value = true;
  try {
    const result = await getTableModelDiagram({ layerId: selectedLayer });
    if (request() && selectedLayer === layerId.value) {
      diagram.value = result;
    }
  } finally {
    if (request()) loading.value = false;
  }
}

function handleConnect(payload: ErConnectPayload) {
  if (readonly.value) return;
  const selectedModels = [payload.sourceId, payload.targetId].map((id) =>
    diagram.value.models?.find((model) => String(model.id) === String(id)),
  );
  if (selectedModels.some((model) => model?.status !== 'DRAFT')) {
    message.warning('请先双击模型并修订关联两端的模型，再建立关系');
    return;
  }
  if (!payload.sourceField || !payload.targetField) {
    message.warning('请从字段行的连接桩发起连线');
    return;
  }
  relationModalRef.value?.open({
    sourceModelId: payload.sourceId,
    targetModelId: payload.targetId,
    sourceField: payload.sourceField,
    targetField: payload.targetField,
  });
}

function handleEdgeDblclick(edge: ErRelationEdge) {
  if (readonly.value) return;
  if (
    [edge.sourceId, edge.targetId].some(
      (id) =>
        diagram.value.models?.find((model) => String(model.id) === String(id))
          ?.status !== 'DRAFT',
    )
  ) {
    message.warning('请先修订关联两端的模型，再编辑关系');
    return;
  }
  relationModalRef.value?.open(edge.raw as TableModelApi.Relation);
}

function handleEdgeRemove(edge: ErRelationEdge) {
  if (readonly.value || !hasAccessByCodes(['modeling:model:delete'])) return;
  const relation = edge.raw as TableModelApi.Relation;
  const current = mutationRequest.begin();
  Modal.confirm({
    title: '系统提示',
    content: `确认删除关系 ${relation.sourceModelName}.${relation.sourceField} → ${relation.targetModelName}.${relation.targetField} 吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      if (!current()) return;
      await deleteTableModelRelation(relation.id!, relation.revision ?? 0);
      if (!current()) return;
      message.success('关系已删除');
      loadDiagram();
    },
  });
}

async function handleNodeDblclick(node: ErTableNode) {
  const model = node.raw as TableModelApi.ErNode;
  if (editorRef.value && !(await editorRef.value.confirmLeave())) return;
  editingId.value = String(model.id);
}

async function handleSaveLayout(positions: ErNodePosition[]) {
  if (readonly.value || positions.length === 0) {
    return;
  }
  const current = mutationRequest.begin();
  await saveTableModelLayout({
    positions: positions.map((position) => ({
      modelId: position.id,
      posX: position.x,
      posY: position.y,
      expectedLayoutRevision:
        diagram.value.models?.find(
          (model) => String(model.id) === String(position.id),
        )?.layoutRevision ?? 0,
    })),
  });
  if (!current()) return;
  message.success('布局已保存');
  designerRef.value?.markLayoutSaved();
  await loadDiagram();
}

watch(
  () => `${access.accessToken}/${access.refreshToken}`,
  async () => {
    editingId.value = undefined;
    layoutDirty.value = false;
    layers.value = [];
    layerId.value = undefined;
    if (await loadThemes()) await loadDiagram();
  },
  { immediate: true },
);
</script>

<template>
  <Page auto-content-height>
    <div class="logic-er">
      <div class="logic-er__header">
        <div class="flex items-center gap-2">
          <Button size="small" @click="router.back()">返回</Button>
          <span class="text-base font-semibold">模型 ER 关系图</span>
          <TreeSelect
            :value="layerId"
            :field-names="{ children: 'children', label: 'name', value: 'id' }"
            :tree-data="themeTree as any"
            class="w-56"
            allow-clear
            placeholder="全部分层（可筛选）"
            show-search
            size="small"
            tree-default-expand-all
            tree-node-filter-prop="name"
            @change="changeLayer"
          />
          <Tag color="default">
            共 {{ diagram.models?.length ?? 0 }} 个模型 /
            {{ diagram.relations?.length ?? 0 }} 条关系
          </Tag>
          <span v-if="loading" class="text-muted-foreground text-xs">
            加载中…
          </span>
          <span class="text-muted-foreground text-xs">
            清空分层可查看跨层关联；拖拽字段连线，双击模型编辑设计
          </span>
        </div>
      </div>
      <div class="logic-er__canvas">
        <ErDesigner
          ref="designerRef"
          :readonly="readonly"
          :key="String(readonly)"
          :edges="edges"
          :export-name="`模型ER-${diagram.layerName ?? ''}`"
          :nodes="nodes"
          connect-mode="field"
          @connect="handleConnect"
          @edge-dblclick="handleEdgeDblclick"
          @edge-remove="handleEdgeRemove"
          @node-dblclick="handleNodeDblclick"
          @save-layout="handleSaveLayout"
          @dirty-change="layoutDirty = $event"
        />
      </div>
    </div>

    <RelationForm
      ref="relationModalRef"
      :models="diagram.models ?? []"
      @saved="loadDiagram"
    />
    <Drawer
      :open="!!editingId"
      title="模型设计"
      width="90vw"
      :mask-closable="false"
      destroy-on-hidden
      @close="closeEditor"
    >
      <ModelEditor
        v-if="editingId"
        ref="editorRef"
        :model-id="editingId"
        @changed="loadDiagram"
      />
    </Drawer>
  </Page>
</template>

<style scoped>
.logic-er {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: hsl(var(--card));
  border-radius: 8px;
}

.logic-er__header {
  flex: none;
  padding: 10px 14px;
  border-bottom: 1px solid hsl(var(--border));
}

.logic-er__canvas {
  flex: 1;
  min-height: 0;
}
</style>
