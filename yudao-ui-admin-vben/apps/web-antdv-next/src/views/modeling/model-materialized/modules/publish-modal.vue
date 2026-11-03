<script setup lang="ts">
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import {
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Radio,
  RadioGroup,
} from 'antdv-next';

import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import {
  getModelMaterialized,
  getModelMaterializedDatabases,
  publishModelMaterialized,
  republishModelMaterialized,
} from '#/api/modeling/model-materialized';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import { isUnresolvedMaterialization, materializedStatus } from '../state';
import DatabasePicker from './database-picker.vue';

defineOptions({ name: 'ModelMaterializedPublishModal' });
const emit = defineEmits<{ success: [] }>();
type Row = ModelMaterializedApi.Materialized;
const open = ref(false);
const submitting = ref(false);
const confirming = ref(false);
const republishId = ref<number | string>();
const originalDatasourceId = ref<number | string>();
const rows = ref<Array<{ modelAlias?: string; modelId: number | string }>>([]);
const batch = ref(false);
const databases = ref<ModelMaterializedApi.Database[]>([]);
const catalog = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const optionsLoading = ref(false);
const optionsFailed = ref(false);
const failures = ref<
  Array<{ message: string; modelId: number | string; name: string }>
>([]);
const form = reactive({
  datasourceId: undefined as number | string | undefined,
  publishMode: 'REBUILD',
  description: '',
});
const busy = computed(() => submitting.value || confirming.value);
const selected = computed(() =>
  databases.value.find(
    (database) => String(database.id) === String(form.datasourceId),
  ),
);
const title = computed(() => {
  if (batch.value) return '批量发布模型';
  return republishId.value === undefined ? '发布模型' : '重新发布模型';
});
const session = useModelingRequest(
  () => '',
  () => {
    open.value = false;
  },
);
const optionRequest = useModelingRequest(() => open.value);
let confirmation: ReturnType<typeof Modal.confirm> | undefined;
let submission = 0;
function clearConfirmation() {
  confirmation?.destroy();
  confirmation = undefined;
  confirming.value = false;
}
watch(
  open,
  (visible) => {
    if (!visible) {
      submission++;
      submitting.value = false;
      session.begin();
      optionRequest.begin();
      clearConfirmation();
      databases.value = [];
      catalog.value = [];
      rows.value = [];
      failures.value = [];
    }
  },
  { flush: 'sync' },
);
onBeforeUnmount(clearConfirmation);
async function loadOptions() {
  const current = optionRequest.begin();
  optionsLoading.value = true;
  optionsFailed.value = false;
  try {
    const [connections, definitions] = await Promise.all([
      getModelMaterializedDatabases(),
      getDatabaseTypeSimpleList(undefined, { silentError: true }).catch(
        () => [],
      ),
    ]);
    if (current()) {
      databases.value = connections;
      catalog.value = definitions;
    }
  } catch {
    if (current()) optionsFailed.value = true;
  } finally {
    if (current()) optionsLoading.value = false;
  }
}
function reset(
  targets: Array<{ modelAlias?: string; modelId: number | string }>,
  isBatch: boolean,
) {
  session.begin();
  rows.value = targets;
  batch.value = isBatch;
  republishId.value = undefined;
  originalDatasourceId.value = undefined;
  failures.value = [];
  Object.assign(form, {
    datasourceId: undefined,
    publishMode: 'REBUILD',
    description: '',
  });
  open.value = true;
}
async function openCreate(modelId?: number | string) {
  if (busy.value || modelId === undefined) return;
  reset([{ modelId }], false);
  await loadOptions();
}
async function openBatch(targets: Row[]) {
  if (busy.value || targets.length === 0) return;
  if (targets.some((row) => isUnresolvedMaterialization(row))) {
    message.warning('部署仍在执行或结果未知，请先核验结果');
    return;
  }
  reset(
    targets.map((row) => ({
      modelId: row.modelId,
      modelAlias: row.modelAlias,
    })),
    true,
  );
  await loadOptions();
}
async function openRepublish(row: Row) {
  if (busy.value) return;
  if (isUnresolvedMaterialization(row)) {
    message.warning('部署仍在执行或结果未知，请先核验结果');
    return;
  }
  const id = row.materializedId ?? row.id;
  if (id === undefined) {
    message.warning('发布记录不存在，请刷新后重试');
    return;
  }
  reset([{ modelId: row.modelId, modelAlias: row.modelAlias }], false);
  republishId.value = id;
  originalDatasourceId.value = row.datasourceId;
  form.datasourceId = row.datasourceId;
  await loadOptions();
}
function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '请求失败，请查看发布日志后重试';
}
function submit() {
  if (busy.value) return;
  if (form.description.length > 256) {
    message.warning('描述不能超过 256 字');
    return;
  }
  if (form.datasourceId === undefined) {
    message.warning('请选择数据库连接');
    return;
  }
  if (rows.value.length === 0) {
    message.warning('请选择要发布的模型');
    return;
  }
  const current = session.begin();
  const targets = rows.value.map((row) => ({ ...row }));
  const payload = {
    datasourceId: form.datasourceId,
    publishMode: form.publishMode,
    description: form.description || undefined,
  };
  const id =
    String(originalDatasourceId.value) === String(form.datasourceId)
      ? republishId.value
      : undefined;
  const execute = async () => {
    if (!current() || submitting.value) return;
    const execution = ++submission;
    submitting.value = true;
    failures.value = [];
    let submitted = 0;
    try {
      for (const target of targets) {
        if (!current()) break;
        try {
          const recordId =
            id === undefined
              ? await publishModelMaterialized({
                  modelId: target.modelId,
                  ...payload,
                })
              : await republishModelMaterialized({
                  id,
                  publishMode: payload.publishMode,
                  description: payload.description,
                });
          submitted++;
          if (!current()) break;
          if (batch.value) {
            const record = await getModelMaterialized(recordId);
            if (!current()) break;
            if (materializedStatus(record) === 'FAIL')
              failures.value.push({
                modelId: target.modelId,
                name: target.modelAlias || String(target.modelId),
                message: record.message || '发布失败，请查看发布日志',
              });
          }
        } catch (error) {
          if (!current()) break;
          if (!batch.value) throw error;
          failures.value.push({
            modelId: target.modelId,
            name: target.modelAlias || String(target.modelId),
            message: errorMessage(error),
          });
        }
      }
      if (!current()) return;
      emit('success');
      if (failures.value.length > 0) {
        const ids = new Set(
          failures.value.map((failure) => String(failure.modelId)),
        );
        rows.value = targets.filter((target) =>
          ids.has(String(target.modelId)),
        );
        message.warning(
          `已提交 ${submitted} 个，${failures.value.length} 个需要处理，请查看下方结果`,
        );
      } else {
        message.success(
          batch.value
            ? `已提交 ${submitted} 个模型的发布，请查看发布状态`
            : '已提交发布，请查看发布状态',
        );
        open.value = false;
      }
    } finally {
      if (execution === submission) {
        submitting.value = false;
        confirming.value = false;
      }
    }
  };
  confirming.value = true;
  confirmation = Modal.confirm({
    title: form.publishMode === 'REBUILD' ? '确认删除重建？' : '确认增量发布？',
    content:
      form.publishMode === 'REBUILD'
        ? '将删除目标库中同名表后重建，表内数据会丢失。是否继续？'
        : '将按当前模型更新目标表结构，可能影响已有数据。是否继续？',
    okType: 'danger',
    onOk: execute,
    onCancel: clearConfirmation,
  });
}
defineExpose({ open: openCreate, openBatch, openRepublish });
</script>

<template>
  <Modal
    v-model:open="open"
    class="materialized-publish-modal"
    :title="title"
    :width="1120"
    :confirm-loading="submitting"
    :ok-button-props="{
      disabled: confirming || optionsLoading || optionsFailed,
    }"
    :cancel-button-props="{ disabled: busy }"
    :closable="!busy"
    :mask-closable="!busy"
    :keyboard="!busy"
    ok-text="确认"
    cancel-text="取消"
    destroy-on-hidden
    @ok="submit"
  >
    <Form
      class="materialized-publish-form"
      layout="horizontal"
      :label-col="{ flex: '120px' }"
      :wrapper-col="{ flex: 1 }"
      :disabled="busy"
    >
      <FormItem label="数据库连接" required>
        <DatabasePicker
          v-model:value="form.datasourceId"
          :databases="databases"
          :catalog="catalog"
          :loading="optionsLoading"
          :error="optionsFailed"
          :disabled="busy"
          @retry="loadOptions"
        />
      </FormItem>
      <FormItem label="数据库类型">
        <Input
          :value="selected?.databaseTypeName || selected?.productType"
          placeholder="请选择数据库类型"
          disabled
        />
      </FormItem>
      <FormItem label="数据库地址">
        <Input
          :value="selected?.databaseAddress"
          placeholder="请输入数据库地址"
          disabled
        />
      </FormItem>
      <FormItem label="发布模式" required>
        <RadioGroup v-model:value="form.publishMode">
          <Radio value="REBUILD">删除重建</Radio><Radio value="INCREMENT">增量发布</Radio>
        </RadioGroup>
      </FormItem>
      <FormItem label="描述">
        <Input.TextArea
          v-model:value="form.description"
          :rows="4"
          :maxlength="256"
          show-count
          placeholder="请输入描述"
        />
      </FormItem>
      <div
        v-if="failures.length"
        class="materialized-publish-results"
        role="status"
      >
        <p>以下模型需要处理，可重试剩余模型：</p>
        <ul>
          <li v-for="failure in failures" :key="String(failure.modelId)">
            <strong>{{ failure.name }}</strong>：{{ failure.message }}
          </li>
        </ul>
      </div>
    </Form>
  </Modal>
</template>

<style>
.materialized-publish-modal .ant-modal-container,
.materialized-publish-modal .ant-modal-content {
  padding: 0;
  overflow: visible;
  border-radius: var(--radius);
}

.materialized-publish-modal .ant-modal-header {
  padding: 12px 20px;
  margin: 0;
  background: #f7f7f7;
  border-radius: var(--radius) var(--radius) 0 0;
}

.materialized-publish-modal .ant-modal-title {
  font-size: 16px;
  font-weight: 600;
}

.materialized-publish-modal .ant-modal-close {
  top: 7px;
  right: 10px;
}

.materialized-publish-modal .ant-modal-body {
  padding: 20px 40px 16px 20px;
}

.materialized-publish-modal .ant-modal-footer {
  padding: 15px 20px;
  margin: 0;
  border-top: 1px solid #ebeef5;
}

.materialized-publish-modal .ant-modal-footer .ant-btn {
  border-radius: calc(var(--radius) - 2px);
}

.materialized-publish-form .ant-form-item {
  margin-bottom: 16px;
}

.materialized-publish-form .ant-form-item-label {
  padding-right: 12px;
}

.materialized-publish-form .ant-input {
  min-height: 32px;
  font-size: 14px;
  border-radius: calc(var(--radius) - 2px);
}

.materialized-publish-form .ant-input-disabled {
  color: #606266;
  background: #f5f7fa;
}

.materialized-publish-form .ant-radio-group {
  min-height: 32px;
  padding-top: 5px;
}

.materialized-publish-form textarea.ant-input {
  min-height: 96px;
  resize: vertical;
}

.materialized-publish-results {
  padding: 12px;
  margin-left: 120px;
  color: #b45309;
  background: #fffbeb;
}

.materialized-publish-results ul {
  padding-left: 20px;
}

@media (max-width: 600px) {
  .materialized-publish-modal .ant-modal-body {
    padding: 16px;
  }

  .materialized-publish-results {
    margin-left: 0;
  }
}
</style>
