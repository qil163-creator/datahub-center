<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message, Select } from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import { triggerSafetyScan } from '#/api/governance/identify';

defineOptions({ name: 'SafetyScanModal' });

const emit = defineEmits<{ (e: 'success'): void }>();

const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const tablesLoading = ref(false);
const databaseId = ref<number>();
const tableNames = ref<string[]>([]);

function databaseOptions() {
  return databases.value.map((db) => ({ label: db.name, value: db.id }));
}

function tableOptions() {
  return tables.value.map((table) => ({
    label: table.remarks
      ? `${table.tableName}（${table.remarks}）`
      : table.tableName,
    value: table.tableName,
  }));
}

async function handleDatabaseChange(value: any) {
  tableNames.value = [];
  tables.value = [];
  if (!value) {
    return;
  }
  tablesLoading.value = true;
  try {
    tables.value = (await getDatabaseTableList(Number(value))) ?? [];
  } finally {
    tablesLoading.value = false;
  }
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!databaseId.value) {
      message.error('请选择要扫描的数据源');
      return;
    }
    modalApi.lock();
    try {
      await triggerSafetyScan({
        databaseId: databaseId.value,
        tableNames: tableNames.value.length > 0 ? tableNames.value : undefined,
      });
      await modalApi.close();
      emit('success');
      message.success('扫描已提交，完成后可在「扫描记录」中查看结果');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      databaseId.value = undefined;
      tableNames.value = [];
      tables.value = [];
      return;
    }
    databases.value = (await getDatabaseSimpleList()) ?? [];
  },
});
</script>

<template>
  <ModalComponent title="执行敏感数据扫描">
    <div
      class="scan-form governance-modal-form governance-form-item-horizontal"
    >
      <div class="form-item">
        <label class="form-item__label form-item__label--required">数据源</label>
        <Select
          v-model:value="databaseId"
          :options="databaseOptions()"
          allow-clear
          show-search
          option-filter-prop="label"
          class="w-full"
          placeholder="选择要扫描的数据源"
          @change="handleDatabaseChange"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">扫描表范围</label>
        <Select
          v-model:value="tableNames"
          :options="tableOptions()"
          :loading="tablesLoading"
          allow-clear
          mode="multiple"
          option-filter-prop="label"
          class="w-full"
          placeholder="留空表示扫描该数据源下全部表"
        />
        <div class="form-item__tip">
          将按启用中的识别规则匹配字段名/注释/采样数据，命中字段生成"待确认"打标
        </div>
      </div>
    </div>
  </ModalComponent>
</template>

<style lang="scss" scoped>
@import '../../../common/custom-modal-form.css';
</style>
