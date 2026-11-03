<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { SafetyCategoryApi } from '#/api/governance/category';
import type { SafetyIdentifyApi } from '#/api/governance/identify';
import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message, Select, TextArea } from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import { getCategoryPage } from '#/api/governance/category';
import { createFieldTag } from '#/api/governance/identify';
import { getLevelPage } from '#/api/governance/level';
import { getSafetyStaticTaskColumnList } from '#/api/governance/statictask';

defineOptions({ name: 'SafetyFieldTagForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<SafetyIdentifyApi.FieldTag>({});
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const columns = ref<SafetyStaticTaskApi.TableColumn[]>([]);
const categories = ref<SafetyCategoryApi.Category[]>([]);
const levels = ref<SafetyLevelApi.Level[]>([]);
const tablesLoading = ref(false);
const columnsLoading = ref(false);

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

function columnOptions() {
  return columns.value.map((column) => ({
    label: column.typeName
      ? `${column.columnName}（${column.typeName}）`
      : column.columnName,
    value: column.columnName,
  }));
}

function categoryOptions() {
  return categories.value.map((item) => ({ label: item.name, value: item.id }));
}

function levelOptions() {
  return levels.value.map((item) => ({
    label: item.safetyName ?? String(item.id),
    value: item.id,
  }));
}

function resetModel() {
  Object.assign(model, {
    id: undefined,
    databaseId: undefined,
    tableName: undefined,
    columnName: undefined,
    categoryId: undefined,
    categoryName: undefined,
    levelId: undefined,
    levelName: undefined,
    levelColor: undefined,
    identifyRuleId: undefined,
    scanRunId: undefined,
    source: undefined,
    tagStatus: undefined,
    hitDesc: undefined,
    confirmTime: undefined,
    remark: undefined,
    createTime: undefined,
  });
  tables.value = [];
  columns.value = [];
}

async function handleDatabaseChange(value: any) {
  model.tableName = undefined;
  model.columnName = undefined;
  tables.value = [];
  columns.value = [];
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

async function handleTableChange(value: any) {
  model.columnName = undefined;
  columns.value = [];
  if (!model.databaseId || !value) {
    return;
  }
  columnsLoading.value = true;
  try {
    columns.value =
      (await getSafetyStaticTaskColumnList(model.databaseId, value)) ?? [];
  } finally {
    columnsLoading.value = false;
  }
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!model.databaseId || !model.tableName || !model.columnName) {
      message.error('请选择数据源、表和字段');
      return;
    }
    if (!model.categoryId) {
      message.error('请选择数据分类');
      return;
    }
    modalApi.lock();
    try {
      await createFieldTag({ ...model });
      await modalApi.close();
      emit('success');
      message.success('打标成功，已回写元数据分级与敏感标记');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      resetModel();
      return;
    }
    resetModel();
    const [databaseList, categoryPage, levelPage] = await Promise.all([
      getDatabaseSimpleList(),
      getCategoryPage({ pageNo: 1, pageSize: 100 }),
      getLevelPage({ pageNo: 1, pageSize: 100 }),
    ]);
    databases.value = databaseList ?? [];
    categories.value = categoryPage.list ?? [];
    levels.value = levelPage.list ?? [];
  },
});
</script>

<template>
  <ModalComponent title="人工打标">
    <div class="tag-form governance-modal-form governance-form-item-horizontal">
      <div class="form-item">
        <label class="form-item__label form-item__label--required">数据源</label>
        <Select
          v-model:value="model.databaseId"
          :options="databaseOptions()"
          allow-clear
          show-search
          option-filter-prop="label"
          class="w-full"
          placeholder="请选择数据源"
          @change="handleDatabaseChange"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">数据表</label>
        <Select
          v-model:value="model.tableName"
          :options="tableOptions()"
          :loading="tablesLoading"
          allow-clear
          show-search
          option-filter-prop="label"
          class="w-full"
          placeholder="请选择数据表"
          @change="handleTableChange"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">字段</label>
        <Select
          v-model:value="model.columnName"
          :options="columnOptions()"
          :loading="columnsLoading"
          allow-clear
          show-search
          option-filter-prop="label"
          class="w-full"
          placeholder="请选择字段"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          数据分类
        </label>
        <Select
          v-model:value="model.categoryId"
          :options="categoryOptions()"
          allow-clear
          show-search
          option-filter-prop="label"
          class="w-full"
          placeholder="请选择数据分类"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">数据分级</label>
        <Select
          v-model:value="model.levelId"
          :options="levelOptions()"
          allow-clear
          class="w-full"
          placeholder="留空取分类上配置的分级"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">备注</label>
        <TextArea v-model:value="model.remark" :rows="2" placeholder="选填" />
      </div>
      <div class="form-item__tip">
        人工打标视为已确认，保存后立即回写元数据字段的安全分级与敏感标记
      </div>
    </div>
  </ModalComponent>
</template>

<style lang="scss" scoped>
@import '../../../common/custom-modal-form.css';
</style>
