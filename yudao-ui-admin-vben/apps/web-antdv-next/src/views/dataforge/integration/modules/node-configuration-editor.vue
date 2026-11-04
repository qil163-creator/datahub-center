<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { computed, ref } from 'vue';

import { Alert, ConfigProvider } from 'antdv-next';

import { getNodeEditor } from '../../etl/pipeline/node-editors/registry';
import ConnectorNodeEditor from './connector-node-editor.vue';
import DatabaseNodeEditor from './database-node-editor.vue';
import ManagedCryptoEditor from './managed-crypto-editor.vue';
import ManagedHttpEditor from './managed-http-editor.vue';
import NativeOrderEditor from './native-order-editor.vue';
import NativeScriptEditor from './native-script-editor.vue';
import ReferenceFlowEditor from './reference-flow-editor.vue';
import ReferenceNodeEditor from './reference-node-editor.vue';
import ReferenceTransformEditor from './reference-transform-editor.vue';
import ReferenceWebserviceEditor from './reference-webservice-editor.vue';
import RelationInputEditor from './relation-input-editor.vue';
import TransformNodeEditor from './transform-node-editor.vue';

const props = defineProps<{
  componentCode?: string;
  config: Record<string, any>;
  disabled?: boolean;
  downstreamNodes?: { targetId: string; targetName: string }[];
  engine?: string;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode?: string;
  resolveFields?: () => Promise<IntegrationApi.SchemaField[]>;
  revision?: number;
  taskId?: IntegrationApi.Id;
  toolCode: string;
  upstreamNodes?: {
    fields?: DataforgeEtlApi.FieldMeta[];
    name: string;
    nodeNo: string;
  }[];
}>();
const emit = defineEmits<{ requestCheck: [] }>();
const nodeEditor = ref<{
  canGetRules?: boolean;
  canMergeFields?: boolean;
  openFieldMerge?: () => void;
  openRulePicker?: () => void;
  validate?: () => boolean;
}>();
const orderEditor = ref<{ validate: () => boolean }>();
const editor = computed(() => getNodeEditor(props.toolCode));
const code = computed(() => props.componentCode ?? props.toolCode);
const requiresOrder = computed(
  () =>
    props.toolCode === 'data-clean' && code.value !== 'dedup-records' &&
    props.config.enableDeduplication &&
    (props.config.deduplicationStrategy === 'KEY_FIELDS' ||
      (Array.isArray(props.config.ignoreCaseFields)
        ? props.config.ignoreCaseFields.length > 0
        : props.config.ignoreCase === true)),
);
const customTransform = computed(() =>
  [
    'custom-token',
    'dedup-records',
    'set-field-value',
    'string-operation',
    'string-replace',
  ].includes(code.value),
);
const connector = computed(() =>
  /^(?:read|write)-(?:csv|odps|hive|hdfs|mongodb|redis|rabbitmq|tdengine|rocketmq|ftp|webservice)$/.test(
    props.toolCode,
  ),
);
const relationInput = computed(() =>
  (['read-odps', 'read-hive', 'read-mongodb', 'read-tdengine'] as const).find(
    (code) => code === props.toolCode,
  ),
);
const referenceEditor = computed(() =>
  (
    [
      'read-csv',
      'read-json',
      'read-excel',
      'read-kafka',
      'write-kafka',
      'column-map',
    ] as const
  ).find((code) => code === props.toolCode),
);
const referenceTransform = computed(() =>
  [
    'add-constant',
    'calculator',
    'field-derive',
    'field-split',
    'id-generator',
    'java-script',
    'number-range',
    'sort-records',
    'value-map',
  ].includes(code.value),
);
function validate() {
  return (
    (nodeEditor.value?.validate?.() ?? true) &&
    (orderEditor.value?.validate() ?? true)
  );
}
const canMergeFields = computed(
  () => nodeEditor.value?.canMergeFields === true,
);
const canGetRules = computed(
  () =>
    props.toolCode === 'data-clean' &&
    code.value === 'data-clean' &&
    nodeEditor.value?.canGetRules === true,
);
function openRulePicker() {
  if (!props.disabled && canGetRules.value)
    nodeEditor.value?.openRulePicker?.();
}
function openFieldMerge() {
  if (!props.disabled && canMergeFields.value)
    nodeEditor.value?.openFieldMerge?.();
}
defineExpose({
  validate,
  openFieldMerge,
  canMergeFields,
  canGetRules,
  openRulePicker,
});
</script>
<template>
  <ConfigProvider :component-disabled="disabled">
    <fieldset :disabled="disabled" class="qi-node-fields">
      <DatabaseNodeEditor
        v-if="toolCode === 'read-db' || toolCode === 'insert-db'"
        ref="nodeEditor"
        :config="config"
        :mode="mode"
        :engine="engine"
        :tool-code="toolCode"
        :input-fields="inputFields"
        :resolve-fields="resolveFields"
        :disabled="disabled"
      />
      <RelationInputEditor
        v-else-if="relationInput"
        ref="nodeEditor"
        :config="config"
        :tool-code="relationInput"
        :mode="mode"
        :engine="engine"
        :resolve-fields="resolveFields"
        :disabled="disabled"
      />
      <ReferenceNodeEditor
        v-else-if="referenceEditor"
        ref="nodeEditor"
        :config="config"
        :mode="mode"
        :tool-code="referenceEditor"
        :input-fields="inputFields"
        :disabled="disabled"
        :engine="engine"
      />
      <ReferenceWebserviceEditor
        v-else-if="toolCode === 'read-webservice'"
        ref="nodeEditor"
        :config="config"
        :disabled="disabled"
        :task-id="taskId"
        :revision="revision"
      >
        <template #basics><slot name="basics"></slot></template><template #description><slot name="description"></slot></template>
      </ReferenceWebserviceEditor>
      <ConnectorNodeEditor
        v-else-if="connector"
        ref="nodeEditor"
        :config="config"
        :tool-code="toolCode"
        :mode="mode"
        :input-fields="inputFields"
        :disabled="disabled"
        :engine="engine"
      />
      <NativeScriptEditor
        v-else-if="toolCode === 'java-script' || toolCode === 'custom-token'"
        ref="nodeEditor"
        :config="config"
        :tool-code="toolCode"
        :mode="mode"
        :engine="engine"
        :disabled="disabled"
        @request-check="emit('requestCheck')"
      >
        <template #description><slot name="description"></slot></template>
      </NativeScriptEditor>
      <ReferenceTransformEditor
        v-else-if="referenceTransform"
        ref="nodeEditor"
        :config="config"
        :tool-code="toolCode"
        :input-fields="inputFields"
        :disabled="disabled"
        :engine="engine"
      >
        <template #description><slot name="description"></slot></template>
      </ReferenceTransformEditor>
      <ManagedHttpEditor
        v-else-if="toolCode === 'rest-api'"
        ref="nodeEditor"
        :config="config"
        :disabled="disabled"
        :task-id="taskId"
        :revision="revision"
      >
        <template #basics><slot name="basics"></slot></template>
      </ManagedHttpEditor>
      <ManagedCryptoEditor
        v-else-if="toolCode === 'data-encrypt' || toolCode === 'data-decrypt'"
        ref="nodeEditor"
        :config="config"
        :tool-code="toolCode"
        :input-fields="inputFields"
        :disabled="disabled"
      >
        <template #description><slot name="description"></slot></template>
      </ManagedCryptoEditor>
      <TransformNodeEditor
        v-else-if="customTransform"
        ref="nodeEditor"
        :config="config"
        :engine="engine"
        :mode="mode"
        :tool-code="code"
        :input-fields="inputFields"
        :disabled="disabled"
      />
      <ReferenceFlowEditor
        v-else-if="
          [
            'data-clean',
            'multi-join',
            'row-column',
            'data-condition',
            'sub-pipeline',
          ].includes(toolCode)
        "
        ref="nodeEditor"
        :config="config"
        :engine="engine"
        :mode="mode"
        :tool-code="toolCode"
        :component-code="code"
        :input-fields="inputFields"
        :downstream-nodes="downstreamNodes"
        :upstream-nodes="upstreamNodes"
        :disabled="disabled"
      >
        <template #basics><slot name="basics"></slot></template>
      </ReferenceFlowEditor>
      <component
        :is="editor"
        ref="nodeEditor"
        v-else-if="editor"
        :key="toolCode"
        :config="config"
        :input-fields="inputFields"
        :downstream-nodes="downstreamNodes"
      />
      <Alert
        v-else
        message="无法识别该历史组件，原始配置仍会保留。"
        type="warning"
      />
      <NativeOrderEditor
        v-if="requiresOrder"
        ref="orderEditor"
        :config="config"
        :input-fields="inputFields"
        :disabled="disabled"
        :mode="mode"
      />
    </fieldset>
  </ConfigProvider>
</template>
