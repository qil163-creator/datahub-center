<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Form,
  FormItem,
  Input,
  message,
  Select,
  Spin,
} from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import { useObjectRequest } from '#/hooks/use-object-request';
const emit = defineEmits<{ save: [value: QualityTaskApi.Object] }>();
const form = ref<Partial<QualityTaskApi.Object>>({});
const readonly = ref(false);
const formRef = ref<FormInstance>();
const sources = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const loading = ref(false);
const tableLoading = ref(false);
const failed = ref(false);
const requests = useObjectRequest(() => form.value.clientKey);
const tableRequests = useObjectRequest(() => form.value.datasourceConnId);
const source = computed(() =>
  sources.value.find(
    (entry) => String(entry.id) === String(form.value.datasourceConnId),
  ),
);
const [Modal, modalApi] = useVbenModal({
  onClosed() {
    requests.begin();
    tableRequests.begin();
    loading.value = false;
    tableLoading.value = false;
  },
  async onConfirm() {
    await save();
  },
});
useObjectRequest(
  () => '',
  () => {
    sources.value = [];
    tables.value = [];
    void modalApi.close();
  },
);
async function open(value?: QualityTaskApi.Object, detail = false) {
  const initial = value
    ? { ...value }
    : { clientKey: crypto.randomUUID(), objectName: '', tableName: '' };
  form.value = initial;
  readonly.value = detail;
  sources.value = [];
  tables.value = [];
  modalApi.open();
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getDatabaseSimpleList();
    if (current()) {
      sources.value = result;
      if (form.value.datasourceConnId !== undefined) await loadTables();
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadTables() {
  const current = tableRequests.begin();
  tables.value = [];
  if (form.value.datasourceConnId === undefined) return;
  tableLoading.value = true;
  try {
    const result = await getDatabaseTableList(form.value.datasourceConnId);
    if (current()) tables.value = result;
  } finally {
    if (current()) tableLoading.value = false;
  }
}
function changeSource() {
  form.value.tableName = '';
  form.value.tableId = undefined;
  form.value.datasourceType = source.value?.databaseTypeName;
  form.value.datasourceConnName = source.value?.name;
  form.value.datasourceInstance = source.value?.databaseSchema;
  void loadTables();
}
async function save() {
  if (readonly.value) {
    await modalApi.close();
    return;
  }
  await formRef.value?.validate();
  if (
    form.value.datasourceConnId === undefined ||
    !form.value.tableName ||
    !form.value.objectName?.trim()
  ) {
    message.warning('请完整填写稽查对象');
    return;
  }
  emit('save', JSON.parse(JSON.stringify(form.value)) as QualityTaskApi.Object);
  await modalApi.close();
}
defineExpose({ open });
</script>
<template>
  <Modal
    :fullscreen-button="false"
    :open-auto-focus="false"
    header-class="q-quality-dialog-header"
    footer-class="q-quality-dialog-footer"
    class="q-quality-modal q-quality-object-modal"
    :title="
      readonly ? '稽查对象详情' : form.id ? '修改稽查对象' : '新增稽查对象'
    "
    :show-confirm-button="!readonly"
    cancel-text="关闭"
  >
    <Spin :spinning="loading">
      <Form
        ref="formRef"
        :model="form"
        :label-col="{ style: { width: '140px' } }"
        :disabled="readonly"
        class="q-object-form"
      >
        <FormItem
          label="名称"
          name="objectName"
          :rules="[{ required: true, message: '请输入名称' }]"
        >
          <Input
            v-model:value="form.objectName"
            :maxlength="128"
            placeholder="请输入名称"
          />
        </FormItem>
        <FormItem
          label="数据连接"
          name="datasourceConnId"
          :rules="[{ required: true, message: '请选择数据连接' }]"
        >
          <Select
            v-model:value="form.datasourceConnId"
            :options="
              sources.map((entry) => ({ label: entry.name, value: entry.id }))
            "
            show-search
            option-filter-prop="label"
            placeholder="请选择数据连接"
            @change="changeSource"
          />
        </FormItem>
        <FormItem label="数据连接类型">
          <Input
            :value="form.datasourceType || source?.databaseTypeName"
            readonly
          />
        </FormItem>
        <FormItem label="实例/模式">
          <Input v-model:value="form.datasourceInstance" readonly />
        </FormItem>
        <FormItem
          label="表名称"
          name="tableName"
          :rules="[{ required: true, message: '请选择表名称' }]"
        >
          <Select
            v-model:value="form.tableName"
            :loading="tableLoading"
            :options="
              tables.map((entry) => ({
                label: entry.remarks
                  ? `${entry.tableName}/${entry.remarks}`
                  : entry.tableName,
                value: entry.tableName,
              }))
            "
            placeholder="请选择表名称"
            show-search
            option-filter-prop="label"
            @change="form.tableId = undefined"
          />
        </FormItem>
      </Form>
    </Spin>
  </Modal>
</template>
