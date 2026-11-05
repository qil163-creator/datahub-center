<script setup lang="ts">
import type { SubflowPipeline } from '#/api/dataforge/etl/subflow';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Select } from 'antdv-next';

import { getSubflowPipelinePage } from '#/api/dataforge/etl/subflow';

import '../../etl/pipeline/node-editors/editor-style.css';

const props = defineProps<{ config: Record<string, any>; disabled?: boolean }>();
const access = useAccessStore();
const user = useUserStore();
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const search = ref('');
const pipelineId = ref<string>();
const pipelines = ref<SubflowPipeline[]>([]);
const pipelinePage = ref(1);
const pipelineTotal = ref(0);
const pipelineLoading = ref(false);
const error = ref('');
let active = true;
let pipelineSequence = 0;
const pipelineOptions = computed(() =>
  pipelines.value.map((item) => ({ value: String(item.id), label: item.name })),
);
function invalidate() {
  ++pipelineSequence;
  pipelineLoading.value = false;
  error.value = '';
}
function resetIdentity() {
  invalidate();
  search.value = '';
  pipelineId.value = undefined;
  pipelines.value = [];
  pipelineTotal.value = 0;
}
async function searchPipelines(page = 1) {
  const current = ++pipelineSequence;
  const context = owner();
  pipelineLoading.value = true;
  error.value = '';
  pipelinePage.value = page;
  try {
    const result = await getSubflowPipelinePage({
      name: search.value.trim() || undefined,
      pageNo: page,
      pageSize: 20,
    });
    if (current !== pipelineSequence || !active || context !== owner()) return;
    pipelines.value = result.list;
    pipelineTotal.value = result.total;
  } catch {
    if (current === pipelineSequence && active)
      error.value = '子流程列表加载失败，请重试。';
  } finally {
    if (current === pipelineSequence) pipelineLoading.value = false;
  }
}
watch(search, () => {
  ++pipelineSequence;
  pipelines.value = [];
  pipelineTotal.value = 0;
  pipelineLoading.value = false;
});
watch(owner, resetIdentity, { flush: 'sync' });
onBeforeUnmount(() => {
  active = false;
  invalidate();
});
</script>
<template>
  <div class="etl-editor">
    <fieldset :disabled="disabled" :inert="disabled || undefined">
    <Alert v-if="error" type="error" :message="error" />
    <div class="qi-reference-form-row">
      <label class="qi-subflow-required">子流程</label>
      <div>
        <Select v-model:value="pipelineId" :options="pipelineOptions" :loading="pipelineLoading" :disabled="disabled" show-search :filter-option="false" placeholder="请选择子流程" @search="(value) => { search = value; searchPipelines(); }" @focus="searchPipelines()" />
        <p class="qi-reference-help">子流程用于调用已有流程，适合封装公共处理逻辑，实现流程复用。注意：子流程变更不会自动同步，需重新打开并保存当前节点以使配置生效。</p>
        <div v-if="pipelineTotal > 20" class="qi-reference-file-actions"><Button :disabled="disabled || pipelinePage <= 1 || pipelineLoading" @click="searchPipelines(pipelinePage - 1)">上一页流程</Button><span>第{{ pipelinePage }}页</span><Button :disabled="disabled || pipelinePage * 20 >= pipelineTotal || pipelineLoading" @click="searchPipelines(pipelinePage + 1)">下一页流程</Button></div>
      </div>
    </div>
    <slot name="description"></slot>
    </fieldset>
  </div>
</template>

<style scoped>
.qi-subflow-required::before {
  margin-right: 4px;
  color: #f56c6c;
  content: '*';
}

.qi-reference-help {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: #909399;
}
</style>
