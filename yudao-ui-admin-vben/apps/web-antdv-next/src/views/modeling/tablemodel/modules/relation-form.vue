<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, message, Modal, Select } from 'antdv-next';

import { saveTableModelRelation } from '#/api/modeling/tablemodel';
import { RELATION_TYPE_LABELS } from '#/components/er-designer';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';

const props = defineProps<{ models: TableModelApi.ErNode[] }>();
const emit = defineEmits<{ saved: [] }>();
const visible = ref(false);
const saving = ref(false);
const form = ref<TableModelApi.Relation>({});
const request = useModelingRequest(
  () => visible.value,
  () => {
    saving.value = false;
    form.value = {};
  },
);
const source = computed(() =>
  props.models.find(
    (row) => String(row.id) === String(form.value.sourceModelId),
  ),
);
const target = computed(() =>
  props.models.find(
    (row) => String(row.id) === String(form.value.targetModelId),
  ),
);
function open(value: TableModelApi.Relation) {
  visible.value = true;
  form.value = JSON.parse(JSON.stringify(value)) as TableModelApi.Relation;
  form.value.relationType ||= 'ONE_TO_MANY';
  if (!form.value.fieldPairs?.length)
    form.value.fieldPairs = [
      {
        sourceFieldUid:
          source.value?.fields?.find(
            (field) => field.fieldName === value.sourceField,
          )?.fieldUid ?? '',
        targetFieldUid:
          target.value?.fields?.find(
            (field) => field.fieldName === value.targetField,
          )?.fieldUid ?? '',
      },
    ];
}
async function save() {
  if (saving.value || !source.value || !target.value) return;
  const pairs = form.value.fieldPairs ?? [];
  if (
    pairs.length === 0 ||
    pairs.some((pair) => !pair.sourceFieldUid || !pair.targetFieldUid)
  ) {
    message.warning('请完整选择每组关联字段');
    return;
  }
  const current = request.begin();
  saving.value = true;
  try {
    await saveTableModelRelation({
      ...form.value,
      sourceRevision: source.value.revision,
      targetRevision: target.value.revision,
      expectedRevision: form.value.revision,
      sourceField: source.value.fields?.find(
        (field) => field.fieldUid === pairs[0]?.sourceFieldUid,
      )?.fieldName,
      targetField: target.value.fields?.find(
        (field) => field.fieldUid === pairs[0]?.targetFieldUid,
      )?.fieldName,
    });
    if (!current()) return;
    message.success('关系已保存');
    visible.value = false;
    emit('saved');
  } finally {
    if (current()) saving.value = false;
  }
}
defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :title="form.id ? '编辑关联' : '建立关联'"
    :confirm-loading="saving"
    :closable="!saving"
    :mask-closable="!saving"
    width="760px"
    @ok="save"
  >
    <p class="mb-3">
      {{ source?.name }} →
      {{ target?.name }}。字段按稳定标识关联，模型字段改名后可保留关联。
    </p>
    <div class="space-y-3">
      <Input v-model:value="form.name" placeholder="关联名称（可选）" />
      <Select
        v-model:value="form.relationType"
        class="w-full"
        :options="
          Object.entries(RELATION_TYPE_LABELS).map(([value, label]) => ({
            value,
            label,
          }))
        "
      />
      <div
        v-for="(pair, index) in form.fieldPairs"
        :key="index"
        class="flex gap-2"
      >
        <Select
          v-model:value="pair.sourceFieldUid"
          class="flex-1"
          placeholder="来源字段"
          :options="
            source?.fields
              ?.filter((field) => field.fieldUid)
              .map((field) => ({
                label: `${field.fieldName} ${field.cnName ?? ''}`,
                value: field.fieldUid,
              }))
          "
        />
        <span>→</span>
        <Select
          v-model:value="pair.targetFieldUid"
          class="flex-1"
          placeholder="目标字段"
          :options="
            target?.fields
              ?.filter((field) => field.fieldUid)
              .map((field) => ({
                label: `${field.fieldName} ${field.cnName ?? ''}`,
                value: field.fieldUid,
              }))
          "
        />
        <Button danger @click="form.fieldPairs?.splice(index, 1)"><template #icon><IconifyIcon icon="lucide:trash-2" /></template>移除</Button>
      </div>
      <Button
        @click="
          form.fieldPairs?.push({ sourceFieldUid: '', targetFieldUid: '' })
        "
      >
<template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加关联字段组
      </Button>
      <Input.TextArea
        v-model:value="form.description"
        placeholder="业务关联说明"
      />
    </div>
  </Modal>
</template>
