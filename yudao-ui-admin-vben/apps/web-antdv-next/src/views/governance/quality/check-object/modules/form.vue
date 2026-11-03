<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { QualityCheckObjectApi } from '#/api/governance/quality/checkobject';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Form, FormItem, Input, message, Select, TextArea } from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import {
  createCheckObject,
  getCheckObject,
  updateCheckObject,
} from '#/api/governance/quality/checkobject';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number>();

const formData = reactive<QualityCheckObjectApi.CheckObject>({});
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const tablesLoading = ref(false);

const rules = {
  objectName: [{ required: true, message: '请输入检查对象名称' }],
  datasourceConnId: [{ required: true, message: '请选择数据源连接' }],
  tableName: [{ required: true, message: '请选择表' }],
};

async function open(id?: number) {
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  tables.value = [];
  modalApi.open();
  databases.value = await getDatabaseSimpleList();
  if (id) {
    Object.assign(formData, await getCheckObject(id));
    if (formData.datasourceConnId) {
      loadTables(formData.datasourceConnId);
    }
  }
}

async function loadTables(databaseId: number) {
  tablesLoading.value = true;
  try {
    tables.value = await getDatabaseTableList(databaseId);
  } finally {
    tablesLoading.value = false;
  }
}

function handleDatasourceChange(value: any) {
  formData.tableName = undefined;
  if (value) {
    loadTables(Number(value));
  } else {
    tables.value = [];
  }
}

async function handleOk() {
  await formRef.value?.validate();
  modalApi.lock();
  try {
    await (editId.value
      ? updateCheckObject({ ...formData, id: editId.value })
      : createCheckObject(formData));
    message.success(editId.value ? '修改成功' : '新增成功');
    await modalApi.close();
    emit('saved');
  } finally {
    modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await handleOk();
  },
});

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '编辑检查对象' : '新增检查对象'"
  >
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="检查对象名称" name="objectName">
        <Input
          v-model:value="formData.objectName"
          placeholder="请输入检查对象名称"
        />
      </FormItem>
      <FormItem label="数据源连接" name="datasourceConnId">
        <Select
          v-model:value="formData.datasourceConnId"
          :options="
            databases.map((db) => ({
              label: `${db.name}（${db.databaseTypeName ?? ''}）`,
              value: db.id,
            }))
          "
          placeholder="请选择数据源连接"
          show-search
          @change="handleDatasourceChange"
        />
      </FormItem>
      <FormItem label="数据连接类型" name="datasourceType">
        <Select
          v-model:value="formData.datasourceType"
          :options="[
            { label: '中台贴源库', value: 'ODS' },
            { label: '中台明细库', value: 'DWD' },
            { label: '中台主库', value: 'MAIN' },
            { label: '第三方库', value: 'THIRD_PARTY' },
          ]"
          allow-clear
          placeholder="请选择数据连接类型"
        />
      </FormItem>
      <FormItem label="选择表" name="tableName">
        <Select
          v-model:value="formData.tableName"
          :loading="tablesLoading"
          :options="
            tables.map((table) => ({
              label: table.remarks
                ? `${table.tableName}（${table.remarks}）`
                : table.tableName,
              value: table.tableName,
            }))
          "
          placeholder="请先选择数据源，再选择表"
          show-search
        />
      </FormItem>
      <FormItem label="格式名称" name="formatName">
        <Input
          v-model:value="formData.formatName"
          placeholder="请输入格式名称（选填）"
        />
      </FormItem>
      <FormItem label="备注" name="remark">
        <TextArea
          v-model:value="formData.remark"
          :rows="2"
          placeholder="请输入备注"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
