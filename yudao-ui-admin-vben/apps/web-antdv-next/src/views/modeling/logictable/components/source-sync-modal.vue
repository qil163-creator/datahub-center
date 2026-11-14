<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useAntdDesignTokens } from '@vben/hooks';

import { Alert, Button, ConfigProvider, message, Modal, Spin } from 'antdv-next';

import { applySourceSync, previewSourceSync } from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import ModelDiffView from './model-diff-view.vue';

import '../modal.css';
import '../version.css';

const emit = defineEmits<{ synced: [] }>();

const { tokens } = useAntdDesignTokens();
const { hasAccessByCodes } = useAccess();

const visible = ref(false);
const loading = ref(false);
const applying = ref(false);
const modelName = ref('');
const preview = ref<TableModelApi.SourceSyncPreview>();
const modelId = ref<TableModelApi.Id>();
const guard = useObjectRequest(() => String(modelId.value ?? ''));

/** 写回结构需要同时具备保存与发布权限，与后端接口校验一致。 */
const canApply = computed(
  () =>
    hasAccessByCodes(['modeling:model:save']) &&
    hasAccessByCodes(['modeling:model:publish']),
);
const changed = computed(() => {
  const summary = preview.value?.diff.summary;
  return summary ? summary.added + summary.removed + summary.modified : 0;
});

async function open(id: TableModelApi.Id, name = '') {
  modelId.value = id;
  modelName.value = name;
  preview.value = undefined;
  applying.value = false;
  visible.value = true;
  const current = guard.begin();
  loading.value = true;
  try {
    const result = await previewSourceSync(id);
    if (current()) preview.value = result;
  } finally {
    if (current()) loading.value = false;
  }
}

async function apply() {
  if (!preview.value || changed.value === 0 || applying.value || !canApply.value) return;
  const current = guard.begin();
  applying.value = true;
  try {
    const result = await applySourceSync({
      modelId: preview.value.modelId,
      expectedRevision: preview.value.revision,
    });
    if (!current()) return;
    if (result.published) message.success('已按源表结构同步，并发布了新版本');
    else if (result.publishMessage)
      message.warning(`字段已同步并保存为草稿，重新发布未成功：${result.publishMessage}`);
    else message.success('已按源表结构同步，模型保持草稿状态');
    visible.value = false;
    emit('synced');
  } finally {
    if (current()) applying.value = false;
  }
}

function close() {
  if (applying.value) return;
  guard.begin();
  visible.value = false;
}

defineExpose({ open });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      :title="modelName ? `同步结构 - ${modelName}` : '同步结构'"
      class="qd-logic-modal"
      :width="1000"
      :footer="null"
      :style="{ top: '68px' }"
      :mask-closable="!applying"
      :closable="!applying"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading || applying">
        <div class="qd-modal-scroll">
          <template v-if="preview">
            <Alert
              type="info"
              show-icon
              :message="`读取来源表 ${preview.sourceTable} 的当前结构，与当前设计比对。确认后已对齐字段保留中文名、标准、分级等设计属性，只更新结构；${preview.status === 'PUBLISHED' ? '模型将重新发布并生成新版本。' : '模型保持草稿。'}`"
              style="margin-bottom: 16px"
            />
            <ModelDiffView :diff="preview.diff" />
          </template>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button :disabled="applying" @click="close">取消</Button><Button
          v-if="canApply"
          type="primary"
          :loading="applying"
          :disabled="!preview || changed === 0"
          @click="apply"
        >
          {{ preview && changed === 0 ? '结构一致，无需同步' : '确认同步' }}
        </Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
