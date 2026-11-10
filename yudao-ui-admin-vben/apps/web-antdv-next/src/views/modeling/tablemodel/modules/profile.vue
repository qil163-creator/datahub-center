<script lang="ts" setup>
import type { VxeGridPropTypes } from '#/adapter/vxe-table';
import type { StandardDocApi } from '#/api/governance/doc';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  Switch,
  TabPane,
  Tabs,
  Tag,
  TextArea,
  Tooltip,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getDocSimpleList } from '#/api/governance/doc';
import { getStandardElementPage } from '#/api/governance/standardelement';
import {
  getTableModel,
  getTableModelDesign,
  modelSaveData,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import DetailBackButton from '#/components/detail-back-button/index.vue';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import {
  formatBelongingHierarchy,
  getUserMobile,
  getUserNickname,
  optionLabel,
  TABLE_TYPE_OPTIONS,
} from '../../modelcommon/data';

defineOptions({ name: 'ModelingTableModelProfile' });

const props = defineProps<{ modelId: string }>();
const emit = defineEmits<{ back: [] }>();
const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();

const COLUMN_TYPES = [
  'TINYINT',
  'INTEGER',
  'BIGINT',
  'DECIMAL',
  'NUMERIC',
  'FLOAT',
  'DOUBLE',
  'NUMBER',
  'CHAR',
  'VARCHAR',
  'VARCHAR2',
  'TEXT',
  'DATE',
  'TIMESTAMP',
  'DATETIME',
].map((value) => ({ label: value, value }));
const DECIMAL_TYPES = new Set([
  'DECIMAL',
  'DOUBLE',
  'FLOAT',
  'NUMBER',
  'NUMERIC',
]);

const loading = ref(false);
const loadError = ref(false);
const saving = ref(false);
const model = ref<TableModelApi.Model>();
const fields = ref<FieldRow[]>([]);
const typeConfig = ref<ModelingModelApi.ModelTypeConfig | null>(null);
const currentDesign = ref<TableModelApi.Design>();
const documents = ref<StandardDocApi.SimpleDoc[]>([]);
const standards = ref<GovernanceStandardElementApi.StandardElement[]>([]);
let sequence = 0;

type FieldRow = ModelingModelApi.ModelField & { _key: string };

const editable = computed(
  () =>
    currentDesign.value !== undefined &&
    currentDesign.value.status !== 'DISCARDED',
);
const canEdit = computed(
  () => editable.value && hasAccessByCodes(['modeling:model:save']),
);
const standardOptions = computed(() =>
  standards.value.map((item) => ({
    label: item.code ? `${item.name} (${item.code})` : item.name,
    value: item.id,
  })),
);
const documentName = computed(() => {
  const found = documents.value.find(
    (item) => String(item.id) === String(model.value?.documentId),
  );
  if (!found) return '-';
  return found.stdCode ? `${found.nameCn} (${found.stdCode})` : found.nameCn;
});
const namingPreview = computed(() => {
  const current = model.value;
  if (!current) return '-';
  const parts = [current.layerCode, current.bizCategoryCode, current.code]
    .map((item) => (item || '').trim())
    .filter(Boolean);
  const name = parts.join('_');
  if (!name) return '-';
  return current.tableCase === 2 ? name.toLowerCase() : name.toUpperCase();
});

const [FieldsGrid, fieldsGrid] = useVbenVxeGrid<FieldRow>({
  gridOptions: {
    columns: [
      {
        title: '编号',
        field: 'sort',
        width: 70,
        sortable: true,
        slots: { default: 'sort' },
      },
      {
        title: '关联标准',
        field: 'standardName',
        minWidth: 150,
        sortable: true,
      },
      { title: '中文名称', field: 'cnName', minWidth: 140, sortable: true },
      { title: '英文名称', field: 'fieldName', minWidth: 140, sortable: true },
      { title: '描述', field: 'comment', minWidth: 180 },
      { title: '数据类型', field: 'dataType', width: 110, sortable: true },
      { title: '属性长度', field: 'dataLength', width: 100, sortable: true },
      {
        title: '是否主键',
        field: 'pkFlag',
        width: 100,
        slots: { default: 'pkFlag' },
      },
      {
        title: '操作',
        field: 'action',
        width: 240,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'action' },
      },
    ],
    align: 'left',
    height: 'auto',
    minHeight: 260,
    rowConfig: { keyField: '_key', isHover: true },
    sortConfig: { remote: true, multiple: false },
    toolbarConfig: { custom: true, refresh: false, search: false, zoom: false },
    pagerConfig: { pageSize: 10, pageSizes: [10, 20, 50, 100] },
    emptyText: '暂无属性字段',
    proxyConfig: {
      sort: true,
      ajax: {
        query: async ({
          page,
          sorts,
        }: VxeGridPropTypes.ProxyAjaxQueryParams<FieldRow>) => {
          const sorted = [...fields.value];
          const sort = sorts?.[0];
          if (sort?.field && sort.order) {
            const key = sort.field as keyof FieldRow;
            const direction = sort.order === 'desc' ? -1 : 1;
            sorted.sort((left, right) => {
              const first = left[key];
              const second = right[key];
              return (
                direction *
                (typeof first === 'number' && typeof second === 'number'
                  ? first - second
                  : String(first ?? '').localeCompare(
                      String(second ?? ''),
                      'zh-CN',
                      { numeric: true },
                    ))
              );
            });
          }
          const start = (page.currentPage - 1) * page.pageSize;
          return {
            list: sorted.slice(start, start + page.pageSize),
            total: sorted.length,
          };
        },
      },
    },
  },
});
watch([loading, saving], ([pending, persisting]) => {
  fieldsGrid.setLoading(pending || persisting);
});

const fieldOpen = ref(false);
const fieldEditing = ref<null | string>(null);
const detailOpen = ref(false);
const detailRow = ref<FieldRow>();
const draft = reactive<FieldRow>({ _key: '' });
const fieldTitle = computed(() =>
  fieldEditing.value === null ? '新增逻辑模型属性信息' : '修改逻辑模型属性信息',
);
const scaleDisabled = computed(
  () => !DECIMAL_TYPES.has(String(draft.dataType || '').toUpperCase()),
);

watch(
  () => [props.modelId, accessStore.accessToken, accessStore.refreshToken],
  () => {
    model.value = undefined;
    fields.value = [];
    currentDesign.value = undefined;
    fieldOpen.value = false;
    detailOpen.value = false;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  sequence++;
});

function newKey() {
  return (
    globalThis.crypto?.randomUUID?.() || `f-${Date.now()}-${Math.random()}`
  );
}
function toRow(field: ModelingModelApi.ModelField): FieldRow {
  return {
    ...field,
    _key: field.fieldUid || newKey(),
    pkFlag: !!field.pkFlag,
    nullableFlag: field.nullableFlag !== false,
  };
}
async function load() {
  const request = ++sequence;
  loading.value = true;
  loadError.value = false;
  try {
    const [latest, design, docs, elementPage] = await Promise.all([
      getTableModel(props.modelId),
      getTableModelDesign(props.modelId),
      getDocSimpleList().catch(() => []),
      getStandardElementPage({ pageNo: 1, pageSize: 200 }).catch(() => ({
        list: [],
        total: 0,
      })),
    ]);
    if (request !== sequence) return;
    assertCurrentDesign(latest, design);
    model.value = latest;
    documents.value = docs || [];
    standards.value = elementPage?.list || [];
    currentDesign.value = design;
    typeConfig.value = design.typeConfig ?? null;
    fields.value = design.fields.map((item) => toRow(item));
    await nextTick();
    if (request === sequence) await fieldsGrid.reload();
  } catch {
    if (request === sequence) loadError.value = true;
  } finally {
    if (request === sequence) loading.value = false;
  }
}
function resetDraft() {
  Object.keys(draft).forEach((key) => Reflect.deleteProperty(draft, key));
  draft._key = newKey();
  draft.dataLength = undefined;
  draft.dataScale = 0;
  draft.pkFlag = false;
  draft.nullableFlag = true;
}
function openField(row?: FieldRow) {
  if (!canEdit.value || saving.value || loading.value) {
    message.warning('当前没有修订中的版本，不能修改属性字段');
    return;
  }
  resetDraft();
  if (row) {
    Object.assign(draft, { ...row });
    fieldEditing.value = row._key;
  } else {
    fieldEditing.value = null;
  }
  fieldOpen.value = true;
}
function openFieldDetail(row: FieldRow) {
  detailRow.value = row;
  detailOpen.value = true;
}
function onStandardChange(value: unknown) {
  const picked = standards.value.find(
    (item) => String(item.id) === String(value),
  );
  if (!picked) {
    draft.standardId = undefined;
    draft.standardName = undefined;
    draft.standardCode = undefined;
    return;
  }
  draft.standardId = picked.id;
  draft.standardName = picked.name;
  draft.standardCode = picked.code;
  draft.standardColumnType = picked.columnType;
  draft.cnName = picked.name;
  draft.fieldName = (picked.engName || '').toUpperCase();
  draft.dataType = picked.columnType;
  if (picked.dataLength !== null && picked.dataLength !== undefined)
    draft.dataLength = picked.dataLength;
  if (picked.dataScale !== null && picked.dataScale !== undefined)
    draft.dataScale = picked.dataScale;
  draft.defaultValue = picked.defaultValue;
  draft.comment = picked.description;
  draft.nullableFlag = picked.nullableFlag !== 0;
}
function onFieldNameInput(value: string) {
  draft.fieldName = value.toUpperCase().replaceAll(/[^A-Z0-9_]/g, '');
}
watch(
  () => draft.pkFlag,
  (pk) => {
    if (pk) draft.nullableFlag = false;
  },
);
async function persist(next: FieldRow[]) {
  const current = model.value;
  if (
    !current?.id ||
    currentDesign.value === undefined ||
    !canEdit.value ||
    saving.value
  )
    return;
  const owner = sequence;
  saving.value = true;
  try {
    const saved = await saveTableModelDraft({
      ...modelSaveData(current),
      modelId: current.id,
      expectedRevision: current.revision,
      typeConfig: typeConfig.value,
      fields: next.map(({ _key, ...rest }, index) => ({
        ...rest,
        sort: rest.sort ?? index + 1,
      })),
    });
    if (saved === false || owner !== sequence) return false;
    if (owner === sequence) {
      message.success('属性字段已保存');
      await load();
    }
    return true;
  } finally {
    saving.value = false;
  }
}
async function saveField() {
  if (!canEdit.value || saving.value) return;
  if (!draft.cnName?.trim() || !draft.fieldName?.trim() || !draft.dataType) {
    message.warning('请填写中文名称、英文名称和数据类型');
    return;
  }
  const next = [...fields.value];
  const row: FieldRow = {
    ...draft,
    cnName: draft.cnName.trim(),
    _key: draft._key || newKey(),
  };
  if (fieldEditing.value === null) next.push(row);
  else {
    const index = next.findIndex((item) => item._key === fieldEditing.value);
    if (index === -1) return;
    next[index] = row;
  }
  if (await persist(next)) fieldOpen.value = false;
}
function removeField(row: FieldRow) {
  if (!canEdit.value || saving.value || loading.value) return;
  const owner = sequence;
  Modal.confirm({
    title: `确认删除属性「${row.cnName || row.fieldName || '-'}」？`,
    content: '删除后将保存到当前设计。',
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      if (owner !== sequence || !canEdit.value || saving.value) return;
      await persist(fields.value.filter((item) => item._key !== row._key));
    },
  });
}
function text(value?: null | number | string) {
  return value === undefined || value === null || value === ''
    ? '-'
    : String(value);
}
</script>

<template>
  <div v-if="!model" class="model-profile__state bg-card">
    <Spin v-if="loading" tip="正在加载模型详情…" />
    <Alert v-else type="error" show-icon message="模型详情加载失败">
      <template #action>
        <Button size="small" @click="load">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重试
        </Button>
        <DetailBackButton class="ml-2" @click="emit('back')" />
      </template>
    </Alert>
  </div>
  <div v-else class="model-profile">
    <section class="model-profile__summary bg-card" aria-label="模型概要">
      <div class="model-profile__head">
        <div class="model-profile__identity">
          <span class="model-profile__id bg-primary text-primary-foreground">{{
            model.id
          }}</span>
          <h1 class="model-profile__name">{{ model.name }}</h1>
          <Tag :color="model.enabled === 1 ? 'success' : 'default'">
            {{ model.enabled === 1 ? '启用' : '禁用' }}
          </Tag>
        </div>
        <DetailBackButton @click="emit('back')" />
      </div>
      <div class="model-profile__info">
        <div class="label">英文名称</div>
        <div>{{ text(model.code) }}</div>
        <div class="label">表类型</div>
        <div>
          <Tag color="blue">
            {{ optionLabel(TABLE_TYPE_OPTIONS, model.tableType) }}
          </Tag>
        </div>
        <div class="label">归属层级</div>
        <div>{{ formatBelongingHierarchy(model) }}</div>
        <div class="label">描述</div>
        <div class="span-5">{{ text(model.description) }}</div>
        <div class="label">创建人</div>
        <div>{{ getUserNickname(model.creator) }}</div>
        <div class="label">创建时间</div>
        <div>{{ formatDateTime(model.createTime) || '-' }}</div>
        <div class="label">更新时间</div>
        <div>{{ formatDateTime(model.updateTime) || '-' }}</div>
        <div class="label">备注</div>
        <div class="span-5">{{ text(model.remark) }}</div>
      </div>
    </section>
    <section
      class="model-profile__attributes bg-card"
      aria-label="模型属性与详细信息"
    >
      <Alert
        v-if="loadError"
        class="mb-3"
        type="error"
        show-icon
        message="刷新失败，请重试"
      />
      <Tabs class="model-profile__tabs">
        <TabPane key="fields" tab="属性字段">
          <FieldsGrid class="model-fields-grid">
            <template #toolbar-actions>
              <Button
                v-if="hasAccessByCodes(['modeling:model:save'])"
                :disabled="!canEdit || saving || loading"
                size="small"
                type="primary"
                ghost
                @click="openField()"
              >
                <IconifyIcon class="mr-1" icon="lucide:plus" />新增
              </Button>
            </template>
            <template #toolbar-tools>
              <Tooltip title="刷新属性字段">
                <Button
                  aria-label="刷新属性字段"
                  :disabled="saving"
                  size="small"
                  :loading="loading"
                  @click="load"
                >
                  <IconifyIcon icon="lucide:refresh-cw" />
                </Button>
              </Tooltip>
            </template>
            <template #sort="{ row }">
              {{
                row.sort ||
                fields.findIndex((item) => item._key === row._key) + 1
              }}
            </template>
            <template #pkFlag="{ row }">
              <Switch :checked="!!row.pkFlag" disabled size="small" />
            </template>
            <template #action="{ row }">
              <div class="model-profile__actions">
                <Button size="small" type="link" @click="openFieldDetail(row)">
                  <template #icon><IconifyIcon icon="lucide:eye" /></template>
                  详情
                </Button>
                <template v-if="hasAccessByCodes(['modeling:model:save'])">
                  <Button
                    size="small"
                    type="link"
                    :disabled="!canEdit || saving || loading"
                    @click="openField(row)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:edit" />
</template>
                    修改
                  </Button>
                  <Button
                    danger
                    size="small"
                    type="link"
                    :disabled="!canEdit || saving || loading"
                    @click="removeField(row)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:trash-2" />
</template>
                    删除
                  </Button>
                </template>
              </div>
            </template>
          </FieldsGrid>
        </TabPane>
        <TabPane key="detail" tab="详细信息">
          <div class="model-profile__info">
            <div class="label">命名大小写</div>
            <div>
              <Tag>{{ model.tableCase === 2 ? '小写' : '大写' }}</Tag>
            </div>
            <div class="label">表命名规范</div>
            <div>{{ namingPreview }}</div>
            <div class="label">标准类型</div>
            <div>
              {{
                getDictLabel(DICT_TYPE.STANDARD_STD_TYPE, model.documentType) ||
                '-'
              }}
            </div>
            <div class="label">标准登记</div>
            <div>{{ documentName }}</div>
            <div class="label">创建人</div>
            <div>{{ getUserNickname(model.creator) }}</div>
            <div class="label">联系方式</div>
            <div>
              {{
                model.contactNumber || getUserMobile(model.contactUserId) || '-'
              }}
            </div>
            <div class="label">更新人</div>
            <div>{{ getUserNickname(model.updater) }}</div>
            <div class="label">联系方式</div>
            <div>{{ getUserMobile(model.updater) || '-' }}</div>
          </div>
        </TabPane>
      </Tabs>
    </section>

    <Modal
      v-model:open="fieldOpen"
      :footer="null"
      :title="fieldTitle"
      width="720px"
    >
      <div class="field-form">
        <label>关联标准</label>
        <Select
          v-model:value="draft.standardId"
          allow-clear
          :options="standardOptions"
          placeholder="请选择关联标准"
          show-search
          option-filter-prop="label"
          @change="onStandardChange"
        />
        <label class="required">中文名称</label>
        <Input v-model:value="draft.cnName" placeholder="请输入中文名称" />
        <label class="required">英文名称</label>
        <Input
          :value="draft.fieldName"
          placeholder="请输入英文名称"
          @update:value="onFieldNameInput"
        />
        <label class="required">数据类型</label>
        <Select
          v-model:value="draft.dataType"
          :options="COLUMN_TYPES"
          placeholder="请选择数据类型"
          show-search
        />
        <label>属性长度</label>
        <InputNumber
          v-model:value="draft.dataLength"
          :min="1"
          placeholder="请输入属性长度"
        />
        <label>小数位数</label>
        <InputNumber
          v-model:value="draft.dataScale"
          :disabled="scaleDisabled"
          :min="0"
          placeholder="请输入小数长度"
        />
        <label>默认值</label>
        <Input v-model:value="draft.defaultValue" placeholder="请输入默认值" />
        <label>描述</label>
        <TextArea
          v-model:value="draft.comment"
          :maxlength="500"
          placeholder="请输入描述"
          :rows="3"
          show-count
        />
        <label>备注</label>
        <TextArea
          v-model:value="draft.remark"
          :maxlength="500"
          placeholder="请输入备注"
          :rows="3"
          show-count
        />
        <label>是否主键</label>
        <RadioGroup v-model:value="draft.pkFlag">
          <Radio :value="true">是</Radio>
          <Radio :value="false">否</Radio>
        </RadioGroup>
        <label>是否必填</label>
        <RadioGroup
          :disabled="!!draft.pkFlag"
          :value="draft.nullableFlag === false ? 'Y' : 'N'"
          @update:value="
            (value: string) => {
              draft.nullableFlag = value !== 'Y';
            }
          "
        >
          <Radio value="N">否</Radio>
          <Radio value="Y">是</Radio>
        </RadioGroup>
      </div>
      <div class="mt-4 flex justify-end gap-2">
        <Button @click="fieldOpen = false">取消</Button>
        <Button type="primary" :loading="saving" @click="saveField">
          确定
        </Button>
      </div>
    </Modal>

    <Modal
      v-model:open="detailOpen"
      :footer="null"
      title="逻辑模型属性信息详情"
      width="760px"
    >
      <div v-if="detailRow" class="detail-grid">
        <label>编号</label><Input disabled :value="text(detailRow.sort)" />
        <label>关联数据标准</label><Input disabled :value="text(detailRow.standardName)" />
        <label>中文名称</label><Input disabled :value="text(detailRow.cnName)" />
        <label>英文名称</label><Input disabled :value="text(detailRow.fieldName)" />
        <label>数据类型</label><Input disabled :value="text(detailRow.dataType)" />
        <label>属性长度</label><Input disabled :value="text(detailRow.dataLength)" />
        <label>小数位数</label><Input disabled :value="text(detailRow.dataScale ?? 0)" />
        <label>默认值</label><Input disabled :value="text(detailRow.defaultValue)" />
        <label>是否主键</label><Input disabled :value="detailRow.pkFlag ? '是' : '否'" />
        <label>是否必填</label><Input
          disabled
          :value="detailRow.nullableFlag === false ? '是' : '否'"
        />
        <label>排序</label><Input disabled :value="text(detailRow.sort)" />
        <span></span><span></span> <label>描述</label><Input disabled :value="text(detailRow.comment)" /> <span></span><span></span> <label>备注</label><Input disabled :value="text(detailRow.remark)" /> <span></span><span></span>
      </div>
      <div class="mt-4 flex justify-end">
        <Button @click="detailOpen = false">关闭</Button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.model-fields-grid :deep(.vxe-body--column) {
  font-size: 14px;
}

.model-profile {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 100%;
}

.model-profile__summary,
.model-profile__attributes {
  padding: 16px;
  border-radius: 6px;
}

.model-profile__summary {
  flex-shrink: 0;
}

.model-profile__attributes {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 360px;
}

.model-profile__state {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: 280px;
  padding: 24px;
  border-radius: 6px;
}

.model-profile__head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.model-profile__identity {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.model-profile__id {
  padding: 2px 6px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  border-radius: 2px;
}

.model-profile__name {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.model-profile__tabs {
  display: flex;
  flex: 1;
  min-height: 0;
}

.model-profile__tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.model-profile__tabs :deep(.ant-tabs-body),
.model-profile__tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.model-profile__tabs :deep(.ant-tabs-content) {
  min-height: 300px;
}

.model-profile__actions {
  display: flex;
  align-items: center;
  white-space: nowrap;
}

.model-profile__actions :deep(button) {
  padding-inline: 6px;
}

.model-profile__info {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 110px minmax(0, 1fr) 110px minmax(
      0,
      1fr
    );
  border: 1px solid hsl(var(--border));
  border-right: 0;
  border-bottom: 0;
}

.model-profile__info > div {
  min-height: 40px;
  padding: 8px 12px;
  overflow-wrap: anywhere;
  border-right: 1px solid hsl(var(--border));
  border-bottom: 1px solid hsl(var(--border));
}

.model-profile__info .label {
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 45%);
}

.model-profile__info .span-5 {
  grid-column: span 5;
}

.field-form,
.detail-grid {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 12px 8px;
  align-items: center;
}

.detail-grid {
  grid-template-columns: 100px 1fr 110px 1fr;
}

.field-form :deep(.ant-select),
.field-form :deep(.ant-input),
.field-form :deep(.ant-input-number),
.field-form :deep(textarea.ant-input),
.detail-grid :deep(.ant-input) {
  width: 100%;
}

.required::before {
  margin-right: 2px;
  color: #ff4d4f;
  content: '*';
}

@media (max-width: 1100px) {
  .model-profile__info {
    grid-template-columns: 100px minmax(0, 1fr);
  }

  .model-profile__info .span-5 {
    grid-column: auto;
  }
}

@media (max-width: 640px) {
  .model-profile__summary,
  .model-profile__attributes {
    padding: 12px;
  }

  .model-profile__head {
    align-items: flex-start;
  }

  .model-profile__info {
    grid-template-columns: 80px minmax(0, 1fr);
  }

  .detail-grid {
    grid-template-columns: 90px minmax(0, 1fr);
  }

  .detail-grid > span {
    display: none;
  }
}
</style>
