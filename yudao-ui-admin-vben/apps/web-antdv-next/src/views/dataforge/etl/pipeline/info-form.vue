<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { Input, message, Modal, RadioGroup, TextArea } from 'antdv-next';

import { getEtlPipeline, saveEtlPipeline } from '#/api/dataforge/etl';
import { CronTab } from '#/components/cron-tab';

import QualityTaskField from '../../dev/modules/quality-task-field.vue';
import { TASK_TYPE_OPTIONS } from '../data';
import ParameterEditor from './parameter-editor.vue';

defineOptions({ name: 'DataforgeEtlPipelineInfoForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const visible = ref(false);
const saving = ref(false);
const loading = ref(false);
const access = useAccessStore();
const user = useUserStore();
let sequence = 0;
function close() {
  ++sequence;
  visible.value = false;
  saving.value = false;
  loading.value = false;
}
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  close,
  { flush: 'sync' },
);
onBeforeUnmount(close);
const form = reactive<Record<string, any>>({});
/** 编辑时保留画布快照，保存基础信息不覆盖画布 */
let graphSnapshot: { edges: any[]; nodes: any[] } = { nodes: [], edges: [] };

async function open(id?: DataforgeEtlApi.Id) {
  const current = ++sequence;
  visible.value = true;
  loading.value = !!id;
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, { taskType: 1, parameters: [] });
  graphSnapshot = { nodes: [], edges: [] };
  if (id) {
    let data: DataforgeEtlApi.Pipeline;
    try {
      data = await getEtlPipeline(id);
    } catch {
      if (current === sequence) close();
      return;
    }
    if (current !== sequence || !visible.value) return;
    Object.assign(form, {
      id: data.id,
      revision: data.revision,
      environmentId: data.environmentId,
      qualityTaskId: data.qualityTaskId,
      name: data.name,
      taskType: data.taskType ?? 1,
      cron: data.cron,
      description: data.description,
      parameters: data.parameters ?? [],
    });
    graphSnapshot = { nodes: data.nodes ?? [], edges: data.edges ?? [] };
  }
  if (current === sequence) loading.value = false;
}

defineExpose({ open });

async function handleOk() {
  if (saving.value || loading.value) return;
  const current = sequence;
  if (!form.name?.trim()) {
    message.warning('请填写流程名称');
    return;
  }
  if (form.taskType === 2 && !form.cron?.trim()) {
    message.warning('周期任务请配置 Cron 表达式');
    return;
  }
  saving.value = true;
  try {
    await saveEtlPipeline({
      id: form.id,
      revision: form.revision,
      environmentId: form.environmentId,
      qualityTaskId: form.qualityTaskId,
      name: form.name,
      taskType: form.taskType,
      cron: form.taskType === 2 ? form.cron : undefined,
      description: form.description,
      parameters: form.parameters,
      nodes: graphSnapshot.nodes,
      edges: graphSnapshot.edges,
    });
    if (current !== sequence || !visible.value) return;
    message.success(
      form.id ? '基础信息已更新' : '流程已创建，请点击「配置任务」设计流程',
    );
    visible.value = false;
    emit('success');
  } finally {
    if (current === sequence) saving.value = false;
  }
}
</script>

<template>
  <Modal
    :open="visible"
    :confirm-loading="saving"
    :ok-button-props="{ disabled: loading }"
    @cancel="close"
    :title="form.id ? '编辑信息' : '新建流程'"
    width="800px"
    @ok="handleOk"
  >
    <div class="flex flex-col gap-3 py-1">
      <QualityTaskField v-model:quality-task-id="form.qualityTaskId" />
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          流程名称
        </label>
        <Input
          v-model:value="form.name"
          allow-clear
          placeholder="如：用户数据清洗入仓"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          调度类型
        </label>
        <RadioGroup
          v-model:value="form.taskType"
          :options="TASK_TYPE_OPTIONS"
          option-type="button"
        />
        <div class="form-item__tip">
          保存更新草稿；发布冻结当前版本，实例按发布版本执行
        </div>
      </div>
      <div v-if="form.taskType === 2" class="form-item">
        <label class="form-item__label form-item__label--required">
          Cron 表达式
        </label>
        <CronTab
          v-model="form.cron"
          placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">描述</label>
        <TextArea v-model:value="form.description" :rows="2" />
      </div>
      <div class="form-item">
        <label class="form-item__label">
          流程参数（节点配置中用 ${params.名称} 引用）
        </label>
        <ParameterEditor v-model="form.parameters" />
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item__label {
  font-size: 14px;
  font-weight: 500;
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.form-item__tip {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
