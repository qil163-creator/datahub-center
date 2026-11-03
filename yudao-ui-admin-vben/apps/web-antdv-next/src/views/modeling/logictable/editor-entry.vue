<script setup lang="ts">
import type { LogicTableKind } from './config';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Alert } from 'antdv-next';

import LogicEditor from './components/logic-editor.vue';
import SourceModal from './components/source-modal.vue';
import SummaryModal from './components/summary-modal.vue';

const route = useRoute();
const router = useRouter();
const source = ref<InstanceType<typeof SourceModal>>();
const summary = ref<InstanceType<typeof SummaryModal>>();
const kind = computed(() =>
  typeof route.params.kind === 'string' &&
  ['application', 'dimension', 'fact', 'source', 'summary'].includes(
    route.params.kind,
  )
    ? (route.params.kind as LogicTableKind)
    : undefined,
);
const modelId = computed(() =>
  typeof route.query.id === 'string' && /^[1-9][0-9]*$/.test(route.query.id)
    ? route.query.id
    : undefined,
);
const validId = computed(
  () => route.query.id === undefined || modelId.value !== undefined,
);
function back() {
  const from = typeof route.query.from === 'string' ? route.query.from : '';
  if (from.startsWith('/model/') || from.startsWith('/modeling/'))
    void router.push(from);
  else router.back();
}
watch(
  () => [kind.value, modelId.value, source.value, summary.value],
  () => {
    if (!validId.value) return;
    if (kind.value === 'source') void source.value?.open(modelId.value);
    if (kind.value === 'summary') void summary.value?.open(modelId.value);
  },
  { immediate: true, flush: 'post' },
);
</script>

<template>
  <Alert
    v-if="!kind || !validId"
    type="error"
    message="无效的逻辑表类型或编号"
  />
  <LogicEditor
    v-else-if="
      kind === 'dimension' || kind === 'fact' || kind === 'application'
    "
    :key="`${kind}/${modelId || 'new'}`"
    :kind="kind"
    :model-id="modelId"
    @saved="back"
    @cancel="back"
  />
  <template v-else>
    <div class="qd-logic-editor">
      <button type="button" class="qd-btn" @click="back">返回列表</button>
    </div>
    <SourceModal
      v-if="kind === 'source'"
      ref="source"
      @saved="back"
    /><SummaryModal v-if="kind === 'summary'" ref="summary" @saved="back" />
  </template>
</template>
