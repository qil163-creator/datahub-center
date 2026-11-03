<script lang="ts" setup>
import type { GovernanceMetaImportApi } from '#/api/governance/metaimport';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Input,
  InputNumber,
  message,
  Select,
  Table,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getSystemSourcePage } from '#/api/dataforge/systemsource';
import { registerMetaTable } from '#/api/governance/metaimport';

defineOptions({ name: 'GovernanceMetaObjectRegisterForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<GovernanceMetaImportApi.RegisterTable>({
  columns: [],
});
const submitting = ref(false);
const databases = ref<{ label: string; value: number | string }[]>([]);
const systems = ref<{ label: string; value: number }[]>([]);

const FLAG_OPTIONS = [
  { label: '否', value: 0 },
  { label: '是', value: 1 },
];

function emptyColumn(): GovernanceMetaImportApi.RegisterColumn {
  return {
    columnName: '',
    columnComment: '',
    dataType: 'varchar',
    dataLength: 64,
    nullableFlag: 1,
    pkFlag: 0,
    autoIncrementFlag: 0,
  };
}

async function loadOptions() {
  const [databaseList, systemPage] = await Promise.all([
    getDatabaseSimpleList(),
    getSystemSourcePage({ pageNo: 1, pageSize: 100 }),
  ]);
  databases.value = databaseList.map((item) => ({
    label: item.name ?? `数据源#${item.id}`,
    value: item.id,
  }));
  systems.value = (systemPage.list ?? []).map((item) => ({
    label: item.name ?? `系统#${item.id}`,
    value: item.id,
  }));
}

function handleSystemChange(value: any) {
  model.sourceSystemName = systems.value.find(
    (item) => item.value === value,
  )?.label;
}

function handleAddColumn() {
  model.columns = [...(model.columns ?? []), emptyColumn()];
}

function handleRemoveColumn(index: number) {
  model.columns = (model.columns ?? []).filter((_, i) => i !== index);
}

const columnTableColumns = [
  { title: '字段名', key: 'columnName', width: 150 },
  { title: '注释', key: 'columnComment', width: 160 },
  { title: '类型', key: 'dataType', width: 110 },
  { title: '长度', key: 'dataLength', width: 90 },
  { title: '可空', key: 'nullableFlag', width: 90 },
  { title: '主键', key: 'pkFlag', width: 90 },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  confirmText: '登记入库',
  async onConfirm() {
    if (!model.datasourceId) {
      message.warning('请选择数据源（无直连场景请先登记逻辑数据源）');
      return;
    }
    if (!model.dbName || !model.tableName) {
      message.warning('请填写库名和表名');
      return;
    }
    const columns = (model.columns ?? []).filter((item) => item.columnName);
    if (columns.length === 0) {
      message.warning('请至少登记一个字段');
      return;
    }
    submitting.value = true;
    modalApi.lock();
    try {
      const tableId = await registerMetaTable({ ...model, columns });
      message.success(`登记完成，表编号 ${tableId}，已走差异引擎留痕`);
      emit('success');
      await modalApi.close();
    } finally {
      submitting.value = false;
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      Object.assign(model, {
        datasourceId: undefined,
        sourceSystemId: undefined,
        sourceSystemName: undefined,
        dbName: undefined,
        tableName: undefined,
        tableComment: undefined,
        columns: [],
      });
      return;
    }
    model.columns = [emptyColumn(), emptyColumn()];
    await loadOptions();
  },
});
</script>

<template>
  <ModalComponent class="w-[800px]" title="手工登记表元数据">
    <div
      class="register-form governance-modal-form governance-form-item-horizontal"
    >
      <Alert show-icon type="info">
        <template #message>
          面向无法直连的报送场景：手工登记库/表/字段后与采集、Excel
          导入走同一套差异引擎（版本、快照、变更来源=手工）。
        </template>
      </Alert>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label form-item__label--required"
            >数据源</label
          >
          <Select
            v-model:value="model.datasourceId"
            :options="databases"
            class="w-full"
            option-filter-prop="label"
            placeholder="请选择数据源"
            show-search
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">来源系统</label>
          <Select
            v-model:value="model.sourceSystemId"
            :options="systems"
            allow-clear
            class="w-full"
            option-filter-prop="label"
            placeholder="请选择来源系统"
            show-search
            @change="handleSystemChange"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required"
            >库名</label
          >
          <Input
            v-model:value="model.dbName"
            :maxlength="200"
            placeholder="如 ods"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required"
            >表名</label
          >
          <Input
            v-model:value="model.tableName"
            :maxlength="200"
            placeholder="如 ods_person"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">表注释</label>
        <Input v-model:value="model.tableComment" :maxlength="500" />
      </div>
      <div class="flex items-center justify-between">
        <span class="font-medium">字段清单</span>
        <Button size="small" type="link" @click="handleAddColumn">
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          新增字段
        </Button>
      </div>
      <Table
        :columns="columnTableColumns"
        :data-source="model.columns"
        :pagination="false"
        :scroll="{ x: 'max-content', y: 280 }"
        :row-key="(_record, index) => String(index)"
        size="small"
      >
        <template #bodyCell="{ column, index, record }">
          <template v-if="column.key === 'columnName'">
            <Input
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).columnName
              "
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'columnComment'">
            <Input
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).columnComment
              "
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'dataType'">
            <Input
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).dataType
              "
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'dataLength'">
            <InputNumber
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).dataLength
              "
              :min="0"
              class="w-full"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'nullableFlag'">
            <Select
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).nullableFlag
              "
              :options="FLAG_OPTIONS"
              class="w-full"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'pkFlag'">
            <Select
              v-model:value="
                (record as GovernanceMetaImportApi.RegisterColumn).pkFlag
              "
              :options="FLAG_OPTIONS"
              class="w-full"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              danger
              size="small"
              type="link"
              @click="handleRemoveColumn(index)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </template>
        </template>
      </Table>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
