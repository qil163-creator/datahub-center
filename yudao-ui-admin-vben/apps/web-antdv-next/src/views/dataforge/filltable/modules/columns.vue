<script lang="ts" setup>
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { computed, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  RadioGroup,
  Select,
  Switch,
  Table,
  Tag,
} from 'antdv-next';

import {
  createFillTableColumn,
  deleteFillTableColumn,
  getFillTableColumnList,
  updateFillTableColumn,
} from '#/api/dataforge/filltable';

import { getSimpleDictTypeList } from '#/api/system/dict/type';

import {
  describeColumnRules,
  FIELD_TYPE_OPTIONS,
  FILL_TYPE_OPTIONS,
  fillTableOptionLabel,
  PK_TYPE_OPTIONS,
  rangePlaceholder,
  RULE_SEVERITY_OPTIONS,
  supportsRange,
} from '../data';

defineOptions({ name: 'DataforgeFillTableColumns' });

const fillTable = ref<DataforgeFillTableApi.FillTable>();
const columns = ref<DataforgeFillTableApi.FillTableColumn[]>([]);
const loading = ref(false);

const editable = computed(() => fillTable.value?.status !== 1);

const tableColumns = [
  { title: '顺序', dataIndex: 'sort', key: 'sort', width: 60 },
  { title: '字段名', dataIndex: 'name', key: 'name', width: 140 },
  { title: '中文名', dataIndex: 'cnName', key: 'cnName', width: 120 },
  { title: '类型', key: 'fieldType', width: 90 },
  { title: '长度/小数位', key: 'length', width: 100 },
  { title: '主键', key: 'pk', width: 90 },
  { title: '必填', key: 'nullable', width: 60 },
  { title: '录入', key: 'fillType', width: 90 },
  { title: '规则', key: 'rules', width: 180 },
  { title: '查询', key: 'ifQuery', width: 60 },
  { title: '隐藏', key: 'fillHide', width: 60 },
  { title: '操作', key: 'actions', width: 160, fixed: 'right' as const },
];

async function loadColumns() {
  if (!fillTable.value?.id) {
    return;
  }
  loading.value = true;
  try {
    columns.value = await getFillTableColumnList(fillTable.value.id);
  } finally {
    loading.value = false;
  }
}

/** ==================== 字段编辑 ==================== */
const editVisible = ref(false);

function defaultColumn(): DataforgeFillTableApi.FillTableColumn {
  return {
    id: undefined,
    tableId: fillTable.value?.id,
    name: '',
    cnName: '',
    commentStr: '',
    fieldType: 2,
    fillType: 1,
    fillValues: '',
    fillHide: 0,
    fieldLength: 50,
    fieldScale: 0,
    nullable: 1,
    ifQuery: 0,
    pk: 0,
    pkType: 1,
    sort: undefined,
    minValue: '',
    maxValue: '',
    regexPattern: '',
    regexMessage: '',
    uniqueFlag: 0,
    ruleSeverity: 1,
    dictType: '',
  };
}

/** 选择值来源：手工维护或系统字典 */
const optionSource = ref<'dict' | 'manual'>('manual');
const dictTypeOptions = ref<{ label: string; value: string }[]>([]);
let dictTypesLoading: null | Promise<void> = null;
function loadDictTypes() {
  dictTypesLoading ??= getSimpleDictTypeList()
    .then((list) => {
      dictTypeOptions.value = list.map((item) => ({
        label: `${item.name}（${item.type}）`,
        value: item.type,
      }));
    })
    .catch(() => {
      dictTypesLoading = null;
    });
  return dictTypesLoading;
}
function syncOptionSource() {
  optionSource.value = editModel.dictType?.trim() ? 'dict' : 'manual';
  if (optionSource.value === 'dict') void loadDictTypes();
}
function changeOptionSource(value: 'dict' | 'manual') {
  optionSource.value = value;
  if (value === 'dict') {
    void loadDictTypes();
  } else {
    editModel.dictType = '';
  }
}
// 切换字段类型后，不适用的规则一并清空，避免保存后被后端拒绝
function changeFieldType(value: number) {
  editModel.fieldType = value;
  if (!supportsRange(value)) {
    editModel.minValue = '';
    editModel.maxValue = '';
  }
  if (value !== 2) {
    editModel.regexPattern = '';
    editModel.regexMessage = '';
  }
}
const hasAnyRule = computed(
  () =>
    !!editModel.minValue?.trim() ||
    !!editModel.maxValue?.trim() ||
    !!editModel.regexPattern?.trim() ||
    editModel.uniqueFlag === 1,
);

const editModel =
  reactive<DataforgeFillTableApi.FillTableColumn>(defaultColumn());
const isEditColumn = computed(() => !!editModel.id);

function handleAddColumn() {
  Object.assign(editModel, defaultColumn());
  syncOptionSource();
  editVisible.value = true;
}

function handleEditColumn(row: DataforgeFillTableApi.FillTableColumn) {
  Object.assign(editModel, defaultColumn(), row);
  syncOptionSource();
  editVisible.value = true;
}

async function handleSaveColumn() {
  if (!editModel.name?.trim()) {
    message.warning('请填写字段名');
    return;
  }
  if (!/^[a-z][a-z0-9_]*$/.test(editModel.name)) {
    message.warning('字段名须以小写字母开头，只能包含小写字母、数字和下划线');
    return;
  }
  if (!editModel.cnName?.trim()) {
    message.warning('请填写中文名');
    return;
  }
  if (
    editModel.fillType === 2 &&
    optionSource.value === 'dict' &&
    !editModel.dictType?.trim()
  ) {
    message.warning('请选择字典类型');
    return;
  }
  if (
    editModel.fillType === 2 &&
    optionSource.value === 'manual' &&
    !editModel.fillValues?.trim()
  ) {
    message.warning('下拉选择类型必须填写选择值（英文逗号分隔）');
    return;
  }
  if (editModel.fillType !== 2 || optionSource.value === 'manual') {
    editModel.dictType = '';
  }
  await (isEditColumn.value
    ? updateFillTableColumn(editModel)
    : createFillTableColumn(editModel));
  message.success(isEditColumn.value ? '字段已更新' : '字段已添加');
  editVisible.value = false;
  loadColumns();
}

async function handleDeleteColumn(row: DataforgeFillTableApi.FillTableColumn) {
  await deleteFillTableColumn(row.id!);
  message.success('字段已删除');
  loadColumns();
}

const getTitle = computed(() =>
  fillTable.value
    ? `字段配置 - ${fillTable.value.tableCnName ?? fillTable.value.tableEngName}${editable.value ? '' : '（已发布，只读）'}`
    : '字段配置',
);

const [ModalComponent, modalApi] = useVbenModal({
  showCancelButton: false,
  showConfirmButton: false,
  class: 'w-[1080px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      columns.value = [];
      editVisible.value = false;
      return;
    }
    fillTable.value = modalApi.getData() as DataforgeFillTableApi.FillTable;
    await loadColumns();
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div class="px-2 pb-2">
      <div class="mb-3 flex items-center justify-between">
        <span class="text-xs text-gray-400">
          发布后字段不可修改；需调整结构请先取消发布（物理表与已合并数据保留）
        </span>
        <Button
          v-if="editable"
          size="small"
          type="primary"
          @click="handleAddColumn"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          添加字段
        </Button>
      </div>
      <Table
        :columns="tableColumns"
        :data-source="columns"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 900, y: 420 }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'fieldType'">
            {{ fillTableOptionLabel(FIELD_TYPE_OPTIONS, record.fieldType) }}
          </template>
          <template v-else-if="column.key === 'length'">
            {{ record.fieldLength ?? '-'
            }}{{ record.fieldType === 1 ? ` / ${record.fieldScale ?? 0}` : '' }}
          </template>
          <template v-else-if="column.key === 'pk'">
            <Tag v-if="record.pk === 1" color="gold">
              {{ fillTableOptionLabel(PK_TYPE_OPTIONS, record.pkType) }}
            </Tag>
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'nullable'">
            {{ record.nullable === 0 ? '是' : '否' }}
          </template>
          <template v-else-if="column.key === 'fillType'">
            {{ fillTableOptionLabel(FILL_TYPE_OPTIONS, record.fillType) }}
          </template>
          <template v-else-if="column.key === 'rules'">
            <template v-if="describeColumnRules(record).length > 0">
              <Tag
                v-for="tag in describeColumnRules(record)"
                :key="tag"
                class="mb-1"
              >
                {{ tag }}
              </Tag>
            </template>
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'ifQuery'">
            {{ record.ifQuery === 1 ? '是' : '否' }}
          </template>
          <template v-else-if="column.key === 'fillHide'">
            {{ record.fillHide === 1 ? '是' : '否' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <div v-if="editable" class="flex gap-1">
              <Button
                size="small"
                type="link"
                @click="handleEditColumn(record as any)"
              >
                <template #icon><IconifyIcon icon="lucide:edit" /></template>
                编辑
              </Button>
              <Popconfirm
                title="确认删除该字段吗？"
                @confirm="handleDeleteColumn(record as any)"
              >
                <Button danger size="small" type="link"
                  ><template #icon
                    ><IconifyIcon icon="lucide:trash-2" /></template
                  >删除</Button
                >
              </Popconfirm>
            </div>
            <span v-else>-</span>
          </template>
        </template>
      </Table>
    </div>

    <!-- 字段编辑弹窗 -->
    <Modal
      v-model:open="editVisible"
      :title="isEditColumn ? '编辑字段' : '添加字段'"
      width="800px"
      @ok="handleSaveColumn"
    >
      <div class="column-form">
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            字段名
          </label>
          <Input
            v-model:value="editModel.name"
            allow-clear
            placeholder="如 id_card"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            中文名
          </label>
          <Input
            v-model:value="editModel.cnName"
            allow-clear
            placeholder="如 身份证号"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">数据类型</label>
          <Select
            :value="editModel.fieldType"
            :options="FIELD_TYPE_OPTIONS"
            class="w-full"
            @update:value="(value: any) => changeFieldType(value)"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">注释</label>
          <Input
            v-model:value="editModel.commentStr"
            allow-clear
            placeholder="物理列注释"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">长度</label>
          <InputNumber
            v-model:value="editModel.fieldLength"
            :min="1"
            :max="999999"
            class="w-full"
          />
        </div>
        <div v-if="editModel.fieldType === 1" class="form-item">
          <label class="form-item__label">小数位数</label>
          <InputNumber
            v-model:value="editModel.fieldScale"
            :min="0"
            :max="20"
            class="w-full"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">录入类型</label>
          <RadioGroup
            v-model:value="editModel.fillType"
            :options="FILL_TYPE_OPTIONS"
          />
        </div>
        <div v-if="editModel.fillType === 2" class="form-item">
          <label class="form-item__label">选择值来源</label>
          <RadioGroup
            :value="optionSource"
            :options="[
              { label: '手工维护', value: 'manual' },
              { label: '系统字典', value: 'dict' },
            ]"
            @update:value="(value: any) => changeOptionSource(value)"
          />
        </div>
        <div
          v-if="editModel.fillType === 2 && optionSource === 'manual'"
          class="form-item"
        >
          <label class="form-item__label form-item__label--required">
            选择值
          </label>
          <Input
            v-model:value="editModel.fillValues"
            allow-clear
            placeholder="英文逗号分隔，如 男,女"
          />
        </div>
        <div
          v-if="editModel.fillType === 2 && optionSource === 'dict'"
          class="form-item"
        >
          <label class="form-item__label form-item__label--required">
            字典类型
          </label>
          <div class="min-w-0 flex-1">
            <Select
              v-model:value="editModel.dictType"
              :options="dictTypeOptions"
              class="w-full"
              show-search
              option-filter-prop="label"
              placeholder="选择系统字典"
            />
            <p class="rule-hint">
              发布时固化字典当前启用项的显示名称作为可选值；之后字典变化不影响已发布版本。
            </p>
          </div>
        </div>
        <div v-if="supportsRange(editModel.fieldType)" class="form-item">
          <label class="form-item__label">最小值</label>
          <Input
            v-model:value="editModel.minValue"
            allow-clear
            :placeholder="rangePlaceholder(editModel.fieldType)"
          />
        </div>
        <div v-if="supportsRange(editModel.fieldType)" class="form-item">
          <label class="form-item__label">最大值</label>
          <Input
            v-model:value="editModel.maxValue"
            allow-clear
            :placeholder="rangePlaceholder(editModel.fieldType)"
          />
        </div>
        <div v-if="editModel.fieldType === 2" class="form-item">
          <label class="form-item__label">格式正则</label>
          <Input
            v-model:value="editModel.regexPattern"
            allow-clear
            :maxlength="200"
            placeholder="整体匹配，如 [0-9]{18}"
          />
        </div>
        <div v-if="editModel.fieldType === 2" class="form-item">
          <label class="form-item__label">格式提示语</label>
          <Input
            v-model:value="editModel.regexMessage"
            allow-clear
            :maxlength="100"
            placeholder="不符合时显示，如 请输入 18 位数字"
          />
        </div>
        <div v-if="hasAnyRule" class="form-item">
          <label class="form-item__label">规则级别</label>
          <RadioGroup
            v-model:value="editModel.ruleSeverity"
            :options="RULE_SEVERITY_OPTIONS"
          />
        </div>
        <div class="form-item form-item--switches">
          <span class="switch-item">
            <Switch
              :checked="editModel.pk === 1"
              size="small"
              @change="(v: any) => (editModel.pk = v ? 1 : 0)"
            />
            主键
          </span>
          <span class="switch-item">
            <Switch
              :checked="editModel.nullable === 0"
              size="small"
              @change="(v: any) => (editModel.nullable = v ? 0 : 1)"
            />
            必填
          </span>
          <span class="switch-item">
            <Switch
              :checked="editModel.uniqueFlag === 1"
              size="small"
              @change="(v: any) => (editModel.uniqueFlag = v ? 1 : 0)"
            />
            同一次填报内取值唯一
          </span>
          <span class="switch-item">
            <Switch
              :checked="editModel.ifQuery === 1"
              size="small"
              @change="(v: any) => (editModel.ifQuery = v ? 1 : 0)"
            />
            作为查询条件
          </span>
          <span class="switch-item">
            <Switch
              :checked="editModel.fillHide === 1"
              size="small"
              @change="(v: any) => (editModel.fillHide = v ? 1 : 0)"
            />
            录入隐藏
          </span>
        </div>
        <div v-if="editModel.pk === 1" class="form-item">
          <label class="form-item__label">主键类型</label>
          <RadioGroup
            v-model:value="editModel.pkType"
            :options="PK_TYPE_OPTIONS"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">顺序</label>
          <InputNumber
            v-model:value="editModel.sort"
            :min="0"
            class="w-full"
            placeholder="留空自动排在末尾"
          />
        </div>
      </div>
    </Modal>
  </ModalComponent>
</template>

<style scoped>
.column-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
}

.form-item {
  display: flex;
  flex-flow: row nowrap;
  gap: 12px;
  align-items: flex-start;
  min-width: 0;
}

.form-item--switches {
  display: flex;
  flex-flow: row wrap;
  grid-column: 1 / -1;
  gap: 20px;
}

.switch-item {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 13px;
}

.form-item__label {
  flex-shrink: 0;
  width: 130px;
  padding-top: 5px;
  font-size: 14px;
  font-weight: 500;
  text-align: right;
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.rule-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: hsl(var(--muted-foreground));
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
