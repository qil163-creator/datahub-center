<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  message,
  Select,
  Table,
} from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  createMasterModel,
  getMasterModel,
  updateMasterModel,
} from '#/api/governance/masterdata';
import { getMasterDomainSimpleList } from '#/api/governance/masterdomain';
import { getStandardElementPage } from '#/api/governance/standardelement';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import { FIELD_TYPE_OPTIONS, lengthEditable, scaleEditable } from '../data';
import {
  isMasterModelStructureChanged,
  isMasterModelStructureFrozen,
  masterModelSaveRequest,
} from '../model-save';
import PickColumns from './pick-columns.vue';

const emit = defineEmits<{ success: [] }>();
type Field = GovernanceMasterDataApi.Column & { rowKey: string };
const detail = ref<GovernanceMasterDataApi.Model>();
const columns = ref<Field[]>([]);
const loadFailed = ref(false);
const domains = ref<{ label: string; value: number | string }[]>([]);
const databases = ref<{ label: string; value: number | string }[]>([]);
const standards = ref<{ label: string; value: number | string }[]>([]);
const { hasAccessByCodes } = useAccess();
let nextRow = 0;
const scope = useMasterScope(() => {
  void modalApi.close();
});
const frozen = computed(() => isMasterModelStructureFrozen(detail.value));
const structureLocked = computed(
  () =>
    loadFailed.value ||
    (detail.value !== undefined &&
      detail.value.status !== 0 &&
      detail.value.status !== 1 &&
      detail.value.status !== 2) ||
    (detail.value?.publishState !== undefined &&
      detail.value.publishState !== null &&
      detail.value.publishState !== 'READY'),
);
const savesDraft = computed(
  () =>
    !frozen.value ||
    detail.value?.status === 0 ||
    isMasterModelStructureChanged(detail.value, columns.value),
);
const removedPublishedFields = computed(() => {
  if (!frozen.value) return [];
  const names = new Set(columns.value.map((row) => row.name));
  return (detail.value?.columns ?? [])
    .filter((row) => !names.has(row.name))
    .map((row) => row.name);
});
const title = computed(() => {
  if (detail.value?.id === undefined) return '新增主数据模型';
  return frozen.value ? '修改主数据模型' : '编辑模型草稿';
});
const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 110 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2',
  showDefaultActions: false,
  schema: [
    {
      fieldName: 'domainId',
      label: '所属域',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: () => ({
        options: domains.value,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择主数据域',
      }),
    },
    {
      fieldName: 'tableCn',
      label: '模型名称',
      component: 'Input',
      rules: 'required',
      componentProps: { placeholder: '请输入模型名称', maxlength: 100 },
    },
    {
      fieldName: 'tableName',
      label: '目标物理表',
      component: 'Input',
      rules: 'required',
      componentProps: () => ({
        disabled: frozen.value,
        placeholder: '如 md_person',
      }),
    },
    {
      fieldName: 'databaseId',
      label: '目标数据源',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: () => ({
        options: databases.value,
        disabled: frozen.value,
        showSearch: true,
        optionFilterProp: 'label',
        placeholder: '请选择目标数据源',
      }),
    },
    {
      fieldName: 'approvalEnabled',
      label: '数据审批',
      component: 'Switch',
      componentProps: { class: 'w-auto' },
      description:
        '开启后，该模型的数据新增、编辑、删除操作需审批通过后才会生效。',
      defaultValue: false,
    },
    {
      fieldName: 'approvalProcessKey',
      label: '审批流程标识',
      component: 'Input',
      dependencies: {
        triggerFields: ['approvalEnabled'],
        resolve: ({ values }) => ({ show: values.approvalEnabled === true }),
      },
      componentProps: { placeholder: '使用已部署的 BPM 流程标识' },
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'md:col-span-2',
      componentProps: { rows: 3, maxlength: 500 },
    },
  ],
});
const fieldColumns = [
  { title: '字段名', key: 'name', width: 160 },
  { title: '属性名称', key: 'commentStr', width: 150 },
  { title: '类型', key: 'fieldType', width: 125 },
  { title: '长度', key: 'fieldLength', width: 85 },
  { title: '小数位', key: 'fieldScale', width: 85 },
  { title: '主键', key: 'pk', width: 60 },
  { title: '可空', key: 'nullable', width: 60 },
  { title: '标准数据元', key: 'standardElementId', width: 170 },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];
function field(row: unknown) {
  return row as Field;
}
function makeField(value: GovernanceMasterDataApi.Column = {}): Field {
  return {
    name: '',
    commentStr: '',
    fieldType: 2,
    nullable: 1,
    pk: 0,
    ...value,
    rowKey: `field-${++nextRow}`,
  };
}
function move(index: number, direction: number) {
  const next = index + direction;
  if (next < 0 || next >= columns.value.length) return;
  const [row] = columns.value.splice(index, 1);
  columns.value.splice(next, 0, row!);
}
function picked(fields: GovernanceMasterDataApi.Column[]) {
  for (const value of fields) {
    const index = columns.value.findIndex((row) => row.name === value.name);
    if (index === -1)
      columns.value.push(makeField({ ...value, id: undefined }));
    else columns.value.splice(index, 1, makeField({ ...value, id: undefined }));
  }
  columns.value = columns.value.filter((row) => row.name?.trim());
}
const [PickModal, pickApi] = useVbenModal({
  connectedComponent: PickColumns,
  destroyOnClose: true,
});
async function pick() {
  const values = await formApi.getValues();
  const value = values.databaseId;
  if (value === undefined) {
    message.warning('请先选择目标数据源');
    return;
  }
  pickApi.setData({ databaseId: value }).open();
}
const [Modal, modalApi] = useVbenModal({
  async onOpenChange(open) {
    scope.invalidate();
    const current = scope.begin('detail');
    detail.value = undefined;
    columns.value = [];
    loadFailed.value = false;
    modalApi.unlock();
    await formApi.reset();
    if (!current() || !open) return;
    const initial = modalApi.getData() as {
      domainId?: GovernanceMasterDataApi.Id;
      id?: GovernanceMasterDataApi.Id;
    };
    modalApi.lock();
    try {
      const [domainList, databaseList, standardPage, row] = await Promise.all([
        getMasterDomainSimpleList(),
        getDatabaseSimpleList(),
        hasAccessByCodes(['governance:standard-element:query'])
          ? getStandardElementPage({ pageNo: 1, pageSize: 100 })
          : Promise.resolve({ list: [], total: 0 }),
        initial?.id === undefined
          ? Promise.resolve(undefined)
          : getMasterModel(initial.id),
      ]);
      if (!current()) return;
      detail.value = row;
      domains.value = domainList
        .filter(
          (value) =>
            value.status === 0 || String(value.id) === String(row?.domainId),
        )
        .map((value) => ({ label: value.name, value: value.id! }));
      databases.value = databaseList.map((value) => ({
        label: value.name ?? String(value.id),
        value: value.id,
      }));
      standards.value = standardPage.list.map((value) => ({
        label: value.name ?? String(value.id),
        value: value.id!,
      }));
      columns.value = row?.columns?.map(makeField) ?? [];
      for (const column of columns.value) {
        if (
          column.standardElementId !== undefined &&
          !standards.value.some(
            (value) => String(value.value) === String(column.standardElementId),
          )
        ) {
          standards.value.push({
            label: String(column.standardElementId),
            value: column.standardElementId,
          });
        }
      }
      await formApi.setValues(
        row ?? { domainId: initial?.domainId, approvalEnabled: false },
      );
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onConfirm() {
    const current = scope.begin('save');
    const validation = await formApi.validate();
    if (structureLocked.value || !validation.valid || !current()) return;
    const names = new Set<string>();
    for (const row of columns.value) {
      if (!row.name?.trim() || names.has(row.name)) {
        message.warning('字段名必填且不能重复');
        return;
      }
      names.add(row.name);
    }
    const values = await formApi.getValues();
    if (!current()) return;
    const payload = masterModelSaveRequest(
      values,
      detail.value?.id,
      columns.value,
      detail.value,
    );
    modalApi.lock();
    try {
      await (detail.value?.id === undefined
        ? createMasterModel(payload)
        : updateMasterModel(payload));
      if (!current()) return;
      message.success(savesDraft.value ? '模型草稿已保存' : '模型修改已保存');
      emit('success');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="title"
    :confirm-text="savesDraft ? '保存草稿' : '保存修改'"
    :confirm-disabled="structureLocked"
    class="w-[1120px]"
  >
    <PickModal @picked="picked" />
    <div class="mx-4">
      <Alert
        v-if="loadFailed"
        type="error"
        message="模型或选项加载失败，请关闭后重新打开。"
        show-icon
        class="mb-4"
      />
      <Form />
      <Alert
        v-if="!frozen"
        class="mb-4"
        type="info"
        message="保存草稿仅保存模型和属性。完善字段并设置主键后，在列表中发布生成目标表。"
        show-icon
      />
      <Alert
        v-if="frozen"
        class="mb-4"
        type="info"
        message="修改字段结构后保存为草稿，再次发布时同步目标表；目标数据源和物理表保持不变。"
        show-icon
      />
      <Alert
        v-if="removedPublishedFields.length > 0"
        class="mb-4"
        type="warning"
        :message="`再次发布将删除字段 ${removedPublishedFields.join('、')} 及其已有数据，请确认字段变更。`"
        show-icon
      />
      <div class="mb-3 flex items-center justify-between border-t pt-4">
        <span class="text-base font-semibold">模型属性</span>
        <div v-if="!structureLocked" class="flex gap-2">
          <Button @click="pick">
            <template #icon><IconifyIcon icon="lucide:database" /></template>
            从已有表拾取
          </Button>
          <Button type="primary" ghost @click="columns.push(makeField())">
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加属性
          </Button>
        </div>
      </div>
      <Table
        :columns="fieldColumns"
        :data-source="columns"
        :pagination="false"
        :scroll="{ x: 1095, y: 340 }"
        row-key="rowKey"
        size="small"
      >
        <template #bodyCell="{ column, record, index }">
          <Input
            v-if="column.key === 'name'"
            v-model:value="field(record).name"
            :disabled="structureLocked"
            size="small"
            placeholder="字段名"
          />
          <Input
            v-else-if="column.key === 'commentStr'"
            v-model:value="field(record).commentStr"
            :disabled="structureLocked"
            size="small"
            placeholder="属性名称"
          />
          <Select
            v-else-if="column.key === 'fieldType'"
            v-model:value="field(record).fieldType"
            :disabled="structureLocked"
            :options="FIELD_TYPE_OPTIONS"
            class="w-full"
            size="small"
          />
          <InputNumber
            v-else-if="column.key === 'fieldLength'"
            v-model:value="field(record).fieldLength"
            :disabled="
              structureLocked || !lengthEditable(field(record).fieldType)
            "
            :min="1"
            :max="65535"
            size="small"
            class="w-full"
          />
          <InputNumber
            v-else-if="column.key === 'fieldScale'"
            v-model:value="field(record).fieldScale"
            :disabled="
              structureLocked || !scaleEditable(field(record).fieldType)
            "
            :min="0"
            :max="30"
            size="small"
            class="w-full"
          />
          <Checkbox
            v-else-if="column.key === 'pk'"
            :checked="field(record).pk === 1"
            :disabled="structureLocked"
            @update:checked="
              (value) => {
                field(record).pk = value ? 1 : 0;
                if (value) field(record).nullable = 0;
              }
            "
          />
          <Checkbox
            v-else-if="column.key === 'nullable'"
            :checked="field(record).nullable === 1"
            :disabled="structureLocked || field(record).pk === 1"
            @update:checked="
              (value) => {
                field(record).nullable = value ? 1 : 0;
              }
            "
          />
          <Select
            v-else-if="column.key === 'standardElementId'"
            v-model:value="field(record).standardElementId"
            :disabled="structureLocked"
            :options="standards"
            allow-clear
            show-search
            option-filter-prop="label"
            class="w-full"
            size="small"
          />
          <div
            v-else-if="column.key === 'actions' && !structureLocked"
            class="flex gap-1"
          >
            <Button
              type="text"
              size="small"
              aria-label="上移属性"
              :disabled="index === 0"
              @click="move(index, -1)"
            >
              <IconifyIcon icon="lucide:arrow-up" />
            </Button>
            <Button
              type="text"
              size="small"
              aria-label="下移属性"
              :disabled="index === columns.length - 1"
              @click="move(index, 1)"
            >
              <IconifyIcon icon="lucide:arrow-down" />
            </Button>
            <Button
              danger
              type="text"
              size="small"
              aria-label="删除属性"
              @click="columns.splice(index, 1)"
            >
              <IconifyIcon icon="lucide:trash-2" />
            </Button>
          </div>
        </template>
      </Table>
    </div>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
