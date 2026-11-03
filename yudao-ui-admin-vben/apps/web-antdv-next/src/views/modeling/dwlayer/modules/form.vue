<script lang="ts" setup>
import type { DwLayerCreateMode } from '../data';

import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';

import { computed, onBeforeUnmount, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, message, Table, Tag } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createDwLayer,
  getDwLayer,
  getDwLayerPrefixPreview,
  updateDwLayer,
} from '#/api/modeling/dwlayer';
import { $t } from '#/locales';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import {
  DW_LAYER_NODE_TYPE,
  getCategoryCreateDefaults,
  useFormSchema,
} from '../data';

type DwLayerFormData = FoundationDwLayerApi.DwLayer & {
  createMode?: DwLayerCreateMode;
  parentLayerName?: string;
};

const emit = defineEmits(['success']);
const formData = ref<DwLayerFormData>();
const createMode = ref<DwLayerCreateMode>('category');
const openRevision = ref(0);
const formRequest = useModelingRequest(
  () => openRevision.value,
  () => {
    formData.value = undefined;
  },
);

const getTitle = computed(() => {
  if (formData.value?.id) {
    return $t('ui.actionTitle.edit', ['数仓分层']);
  }
  return createMode.value === 'layer' ? '新增二级分层' : '新增一级分类';
});

/** ==================== 前缀修改影响预览 ==================== */

const PREFIX_PREVIEW_DELAY = 300;
const preview = ref<FoundationDwLayerApi.PrefixPreview>();
const previewLoading = ref(false);
const previewFailed = ref(false);
let previewTimer: ReturnType<typeof setTimeout> | undefined;

function clearPreviewTimer() {
  if (previewTimer !== undefined) {
    clearTimeout(previewTimer);
    previewTimer = undefined;
  }
}

function resetPreview() {
  clearPreviewTimer();
  preview.value = undefined;
  previewLoading.value = false;
  previewFailed.value = false;
}

const previewRequest = useModelingRequest(
  () => formData.value?.id ?? '',
  resetPreview,
);

const previewVisible = computed(
  () =>
    previewLoading.value || previewFailed.value || preview.value !== undefined,
);
const previewReferenceCount = computed(() =>
  preview.value ? preview.value.logicCount + preview.value.physicalCount : 0,
);

const previewColumns = [
  {
    title: '模型名称',
    dataIndex: 'modelName',
    key: 'modelName',
    ellipsis: true,
  },
  { title: '编码', dataIndex: 'code', key: 'code', width: 120, ellipsis: true },
  {
    title: '当前表名',
    dataIndex: 'currentTableName',
    key: 'currentTableName',
    width: 150,
    ellipsis: true,
  },
  { title: '候选表名', key: 'candidateTableName', width: 150, ellipsis: true },
  { title: '变化', key: 'changed', width: 80 },
  { title: '说明', dataIndex: 'note', key: 'note', ellipsis: true },
];

async function loadPrefixPreview(id: FoundationDwLayerApi.Id, prefix: string) {
  const current = previewRequest.begin();
  try {
    const result = await getDwLayerPrefixPreview(id, prefix);
    if (current()) preview.value = result;
  } catch {
    if (current()) previewFailed.value = true;
  } finally {
    if (current()) previewLoading.value = false;
  }
}

/** 仅编辑实际分层时预览；与打开时的原前缀相同则不展示 */
function schedulePrefixPreview(value: unknown) {
  previewRequest.begin();
  resetPreview();
  const layer = formData.value;
  if (!layer?.id || layer.nodeType !== DW_LAYER_NODE_TYPE.LAYER) return;
  const prefix = typeof value === 'string' ? value.trim() : '';
  if (prefix === (layer.tablePrefix ?? '').trim()) return;
  const id = layer.id;
  previewLoading.value = true;
  previewTimer = setTimeout(() => {
    previewTimer = undefined;
    void loadPrefixPreview(id, prefix);
  }, PREFIX_PREVIEW_DELAY);
}

onBeforeUnmount(clearPreviewTimer);

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-2',
    labelWidth: 130,
  },
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
  handleValuesChange: (values, changedFields) => {
    if (changedFields.includes('tablePrefix')) {
      schedulePrefixPreview(values.tablePrefix);
    }
  },
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const current = formRequest.begin();
    const { valid } = await formApi.validate();
    if (!valid || !current()) {
      return;
    }
    modalApi.lock();
    const data = (await formApi.getValues()) as DwLayerFormData;
    if (!current()) return;
    if (
      !formData.value?.id &&
      (data.isBuiltin === null || data.isBuiltin === undefined)
    ) {
      data.isBuiltin = 0;
    }
    if (data.nodeType === DW_LAYER_NODE_TYPE.CATEGORY) {
      delete data.layerCode;
      delete data.tablePrefix;
      delete data.defaultTableType;
    }
    try {
      await (formData.value?.id ? updateDwLayer(data) : createDwLayer(data));
      if (!current()) return;
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    openRevision.value += 1;
    const current = formRequest.begin();
    modalApi.unlock();
    previewRequest.begin();
    resetPreview();
    if (!isOpen) {
      formData.value = undefined;
      createMode.value = 'category';
      await formApi.resetForm();
      return;
    }

    const data = modalApi.getData() as DwLayerFormData;
    await formApi.resetForm();
    if (!current()) return;

    if (!data?.id) {
      createMode.value = data?.createMode ?? 'category';
      await formApi.setValues({
        ...(data?.createMode === 'layer' ? data : getCategoryCreateDefaults()),
        ...data,
        createMode: createMode.value,
      });
      return;
    }

    modalApi.lock();
    try {
      const result = await getDwLayer(data.id);
      if (!current()) return;
      formData.value = result;
      await formApi.setValues(formData.value);
    } catch {
      if (current()) await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
    <div v-if="previewVisible" class="mx-4 mt-2">
      <div class="mb-2 text-sm font-medium">前缀修改影响预览</div>
      <div v-if="previewLoading" class="text-sm text-muted-foreground">
        正在分析前缀修改的影响…
      </div>
      <Alert
        v-else-if="previewFailed"
        type="error"
        message="前缀影响预览失败，不能据此认定无影响"
      />
      <template v-else-if="preview">
        <Alert
          v-if="!preview.applicable"
          type="warning"
          :message="preview.reason || '当前节点不适用前缀预览'"
        />
        <template v-else>
          <Alert
            v-if="preview.blocked"
            class="mb-2"
            type="error"
            :message="`该分层已被 ${previewReferenceCount} 个模型引用，当前策略不允许修改前缀；以下为候选表名预览，不会自动重命名已发布物理表`"
          />
          <Alert
            v-else-if="preview.prefixChanged"
            class="mb-2"
            type="info"
            message="保存后新建模型将按新前缀命名；已有物理表名不会被改动"
          />
          <Alert
            v-if="preview.truncated"
            class="mb-2"
            type="warning"
            message="仅显示前 100 个物理模型"
          />
          <Table
            :columns="previewColumns"
            :data-source="preview.items"
            :pagination="false"
            :row-key="
              (record: FoundationDwLayerApi.PrefixPreviewItem) =>
                String(record.modelId)
            "
            :scroll="{ y: 300 }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'candidateTableName'">
                {{ record.candidateTableName || '—' }}
              </template>
              <template v-else-if="column.key === 'changed'">
                <Tag :color="record.changed ? 'warning' : 'default'">
                  {{ record.changed ? '将变化' : '不变' }}
                </Tag>
              </template>
            </template>
            <template #emptyText>
              <span class="text-muted-foreground">
                该分层下暂无需要预览表名的物理模型
              </span>
            </template>
          </Table>
        </template>
      </template>
    </div>
  </Modal>
</template>
