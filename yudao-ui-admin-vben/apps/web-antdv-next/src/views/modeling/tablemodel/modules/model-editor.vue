<script setup lang="ts">
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Card,
  message,
  Modal,
  Spin,
  Tabs,
  Tag,
} from 'antdv-next';

import {
  discardTableModel,
  getTableModel,
  getTableModelDesign,
  modelSaveData,
  publishTableModel,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';

import CurrentDesignEditor from '../../modelcommon/current-design-editor.vue';
import { optionLabel, TABLE_TYPE_OPTIONS } from '../../modelcommon/data';
import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import ModelForm from './form.vue';

const props = defineProps<{ modelId: number | string }>();
const emit = defineEmits<{ changed: [] }>();
const access = useAccessStore();
const model = ref<TableModelApi.Model>();
const loading = ref(false);
const failed = ref(false);
const tab = ref('design');
const panel = ref<InstanceType<typeof CurrentDesignEditor>>();
const form = ref<InstanceType<typeof ModelForm>>();
const request = useModelingRequest(
  () => props.modelId,
  () => {
    model.value = undefined;
    loading.value = false;
    failed.value = false;
  },
);
async function refresh() {
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const value = await getTableModel(props.modelId);
    if (current()) model.value = value;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function saveDraft(data: {
  expectedRevision: number | string;
  fields: ModelingModelApi.ModelField[];
  typeConfig: ModelingModelApi.ModelTypeConfig | null;
}) {
  const currentModel = model.value;
  if (!currentModel) throw new Error('模型尚未加载');
  if (String(currentModel.revision) !== String(data.expectedRevision)) {
    message.warning('模型信息与设计快照已变化，请刷新后重新编辑');
    return false;
  }
  const current = request.begin();
  const saved = await saveTableModelDraft({
    ...modelSaveData(currentModel),
    ...data,
    modelId: props.modelId,
  });
  if (!current() || saved === false) return false;
  await refresh();
  emit('changed');
  return true;
}
const api = {
  getDesign: getTableModelDesign,
  saveDraft,
  publish: async (id: number | string, expectedRevision: number | string) => {
    const current = request.begin();
    const result = await publishTableModel(id, expectedRevision);
    if (current() && result !== false) await refresh();
    return result;
  },
};
async function editHeader() {
  if (model.value && (await panel.value?.confirmLeave()))
    form.value?.open('FORWARD', model.value);
}
async function headerSaved() {
  await refresh();
  await panel.value?.reload();
  emit('changed');
}
async function discard() {
  if (!(await panel.value?.confirmLeave())) return;
  const id = props.modelId;
  const current = request.begin();
  Modal.confirm({
    title: '废弃模型',
    content: '废弃后不可再修改与发布，已有部署记录将保留。',
    onOk: async () => {
      if (!current()) return;
      await discardTableModel(id);
      if (!current()) return;
      await refresh();
      await panel.value?.reload();
      emit('changed');
    },
  });
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${props.modelId}`,
  () => {
    tab.value = 'design';
    refresh();
  },
  { immediate: true },
);
defineExpose({
  confirmLeave: () => panel.value?.confirmLeave() ?? Promise.resolve(true),
});
</script>

<template>
  <Spin :spinning="loading">
    <template v-if="model">
      <Card class="mb-3" size="small">
        <div class="flex items-center justify-between gap-3">
          <div>
            <span class="mr-3 text-lg font-semibold">{{ model.name }}</span>
            <Tag>{{ optionLabel(TABLE_TYPE_OPTIONS, model.tableType) }}</Tag>
            <span class="text-muted-foreground">
              {{ model.code }} · {{ model.layerName || '未分层' }} ·
              {{ model.dataDomainName || '待归属' }}
            </span>
          </div>
          <div class="flex gap-2">
            <Button
              v-if="model.status !== 'DISCARDED'"
              v-access:code="'modeling:model:save'"
              @click="editHeader"
            >
              基本信息
            </Button>
            <Button
              v-if="model.status !== 'DISCARDED'"
              v-access:code="'modeling:model:publish'"
              @click="discard"
            >
              <template #icon>
<IconifyIcon icon="lucide:circle-off" />
</template>
              废弃
            </Button>
          </div>
        </div>
        <p v-if="model.description" class="mt-2 text-muted-foreground">
          {{ model.description }}
        </p>
        <p v-if="model.grain" class="mt-2 text-muted-foreground">
          一行代表什么：{{ model.grain }}
        </p>
      </Card>
      <Alert
        v-if="model.status === 'DISCARDED'"
        message="此模型已废弃，当前设计和部署记录仍可查看。"
        type="warning"
        class="mb-3"
      />
      <Tabs
        v-model:active-key="tab"
        :items="[{ key: 'design', label: '当前设计' }]"
      />
      <Card v-show="tab === 'design'" size="small">
        <CurrentDesignEditor
          ref="panel"
          :api="api"
          :model-id="model.id"
          :readonly="model.status === 'DISCARDED'"
          :table-type="model.tableType"
          :show-standard="true"
          @changed="emit('changed')"
        />
      </Card>
      <ModelForm ref="form" @saved="headerSaved" />
    </template>
  </Spin>
</template>
