<script setup lang="ts">
import type { EditableLogicKind, LogicFieldRow } from '../editor-state';

import type { BusinessEntityApi } from '#/api/modeling/business-entity';
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions, useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import { useMediaQuery } from '@vueuse/core';
import {
  Alert,
  Button,
  ConfigProvider,
  Input,
  message,
  Popover,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import { getDataDomain } from '#/api/modeling/datadomain';
import {
  createTableModel,
  getTableModel,
  getTableModelDesign,
  getTableModelPage,
  modelSaveData,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';
import eventIcon from '#/views/modeling/planning/reference-assets/entity-event.png';
import hierarchyIcon from '#/views/modeling/planning/reference-assets/entity-hierarchy.png';
import objectIcon from '#/views/modeling/planning/reference-assets/entity-object.png';
import processIcon from '#/views/modeling/planning/reference-assets/entity-process.png';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import domainIcon from '../assets/data-section-default-icon-small.svg';
import { LOGIC_TABLE_PAGES } from '../config';
import {
  logicFieldPayload,
  logicNamingParts,
  newLogicField,
  validateLogicFields,
} from '../editor-state';
import { QdIcon } from '../icons';
import { useLogicOptions } from '../options';
import FieldGrid from './field-grid.vue';

import '../editor.css';

const props = defineProps<{
  kind: EditableLogicKind;
  modelId?: number | string;
}>();

const emit = defineEmits<{ cancel: []; saved: [id: number | string] }>();

const { tokens } = useAntdDesignTokens();

const page = computed(() => LOGIC_TABLE_PAGES[props.kind]);
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canSave = computed(() => hasAccessByCodes(['modeling:model:save']));
const options = useLogicOptions();
const {
  domains: subjects,
  dataDomains,
  entities,
  users,
  documents,
  standards,
  codeTables,
  classifications,
  levels,
  layers,
  loading: optionsLoading,
} = options;
const fields = ref<LogicFieldRow[]>([]);
const step = ref(0);
const loading = ref(false);
const failed = ref(false);
const domainLoading = ref(false);
const domainFailed = ref(false);
const domainDetail = ref<ModelingDataDomainApi.DataDomain>();
const saving = ref(false);
const checkingTimeliness = ref(false);
const timelinessWarning = ref('');
const errors = ref<Record<string, string>>({});
const entityPopover = ref(false);
const narrowScreen = useMediaQuery('(max-width: 768px)');
const entityKeyword = ref('');
const entityGroup = ref('ALL');
const typeConfig = ref<ModelingModelApi.ModelTypeConfig | null>(null);
const form = reactive<
  Partial<TableModelApi.Model> & {
    businessType?: string;
    dataTimeliness?: string;
    entityId?: number | string;
    objType?: string;
    tableNameNotation?: string;
  }
>({});
const guard = useObjectRequest(
  () =>
    `${props.kind}/${props.modelId ?? 'new'}/${access.accessCodes.join(',')}`,
);
const isApplication = computed(() => props.kind === 'application');
const domainGuard = useObjectRequest(
  () => `${form.dataDomainId ?? ''}/${access.accessCodes.join(',')}`,
);
const isEdit = computed(() => props.modelId !== undefined);
const readonly = computed(() => form.status === 'DISCARDED');
const disabled = computed(
  () =>
    saving.value ||
    loading.value ||
    optionsLoading.value ||
    failed.value ||
    checkingTimeliness.value ||
    domainLoading.value ||
    domainFailed.value ||
    readonly.value ||
    !canSave.value,
);
const currentDomain = computed(
  () =>
    domainDetail.value ||
    dataDomains.value.find(
      (item) => String(item.id) === String(form.dataDomainId),
    ),
);
const currentSubject = computed(() =>
  subjects.value.find(
    (item) => String(item.id) === String(form.subjectDomainId),
  ),
);
const currentEntity = computed(() =>
  entities.value.find((item) => String(item.id) === String(form.entityId)),
);
const subjectOptions = computed(() =>
  subjects.value
    .filter(
      (item) =>
        String(item.dataDomainId) === String(form.dataDomainId) &&
        item.status !== 1,
    )
    .map((item) => ({
      label: item.code ? `${item.name} / ${item.code}` : item.name,
      value: String(item.id),
    })),
);
const documentOptions = computed(() =>
  documents.value
    .filter((item) => item.stdType === form.documentType)
    .map((item) => ({ label: item.nameCn, value: String(item.id) })),
);
const standardTypeOptions = computed(() =>
  getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
);
const timelinessOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
const domainOptions = computed(() =>
  dataDomains.value
    .filter((item) => item.status !== 1)
    .map((item) => ({
      label: item.name || String(item.id),
      value: String(item.id),
    })),
);
const userOptions = computed(() =>
  users.value.map((item) => ({
    label: item.nickname || item.username,
    value: String(item.id),
  })),
);
const entityGroups = computed(() =>
  props.kind === 'dimension'
    ? [
        { value: 'ALL', label: '全部', icon: undefined },
        { value: 'NORMAL', label: '普通对象', icon: objectIcon },
        { value: 'HIERARCHICAL', label: '层级对象', icon: hierarchyIcon },
      ]
    : [
        { value: 'ALL', label: '全部', icon: undefined },
        { value: 'EVENT', label: '业务事件', icon: eventIcon },
        { value: 'PROCESS', label: '业务流程', icon: processIcon },
      ],
);
const entityOptions = computed(() =>
  entities.value.filter(
    (item) =>
      item.status === 0 &&
      String(item.dataDomainId) === String(form.dataDomainId) &&
      (!form.subjectDomainId ||
        String(item.subjectDomainId) === String(form.subjectDomainId)) &&
      item.entityType ===
        (props.kind === 'dimension' ? 'OBJECT' : 'ACTIVITY') &&
      (entityGroup.value === 'ALL' ||
        item.entitySubtype === entityGroup.value) &&
      `${item.name} ${item.code}`
        .toLowerCase()
        .includes(entityKeyword.value.toLowerCase()),
  ),
);
const prefix = computed(
  () =>
    logicNamingParts(
      props.kind,
      currentDomain.value,
      '',
      currentEntity.value?.entitySubtype || '',
      currentSubject.value?.abbreviation || currentSubject.value?.code || '',
      form.dataTimeliness || '1',
      form.tableCase || 1,
    ).prefix,
);
const entityTypeLabel = computed(() => {
  const subtype = currentEntity.value?.entitySubtype;
  const labels: Record<string, string> = {
    NORMAL: '普通维度',
    HIERARCHICAL: '层级维度',
    EVENT: '业务事件',
    PROCESS: '业务流程',
  };
  return labels[subtype || ''] || '';
});
const classificationOptions = computed(() =>
  classifications.value.map((item) => ({
    label: item.name || String(item.id),
    value: String(item.id),
    dataGrade:
      levels.value.find(
        (level) => String(level.id) === String(item.dataLevelId),
      )?.safetyName || String(item.dataLevelId),
  })),
);
const codeTableOptions = computed(() =>
  codeTables.value.map((item) => ({
    label: item.name || String(item.id),
    value: String(item.id),
  })),
);
const dimensionOptions = computed(() =>
  entities.value
    .filter(
      (item) =>
        item.entityType === 'OBJECT' &&
        item.status === 0 &&
        String(item.dataDomainId) === String(form.dataDomainId),
    )
    .map((item) => ({ label: item.name, value: String(item.id) })),
);

async function loadModel() {
  const current = guard.begin();
  saving.value = false;
  checkingTimeliness.value = false;
  timelinessWarning.value = '';
  entityPopover.value = false;
  step.value = 0;
  failed.value = false;
  domainDetail.value = undefined;
  domainLoading.value = false;
  domainFailed.value = false;
  errors.value = {};
  fields.value =
    props.kind === 'application'
      ? []
      : [
          newLogicField({
            dataType: 'NUMBER',
            dataPrecision: 19,
            dataScale: 0,
            pkFlag: true,
            nullableFlag: false,
            fieldCategory: 'PRIMARY_KEY',
          }),
        ];
  typeConfig.value = null;
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, {
    tableType: page.value.tableType,
    createMode: 'FORWARD',
    tableCase: 1,
    enabled: 0,
    dataTimeliness: '1',
    description: '',
  });
  loading.value = true;
  try {
    if (props.modelId !== undefined) {
      const [model, design] = await Promise.all([
        getTableModel(props.modelId),
        getTableModelDesign(props.modelId),
      ]);
      if (!current()) return;
      assertCurrentDesign(model, design);
      if (model.tableType !== page.value.tableType) {
        failed.value = true;
        return;
      }
      if (model.dataDomainId !== undefined) {
        domainDetail.value = await getDataDomain(model.dataDomainId);
        if (!current()) return;
      }
      Object.assign(form, model);
      for (const key of [
        'dataDomainId',
        'subjectDomainId',
        'entityId',
        'contactUserId',
        'documentId',
      ] as const) {
        const id = model[key];
        form[key] = id === undefined || id === null ? undefined : String(id);
      }
      typeConfig.value = design.typeConfig ?? null;
      fields.value = design.fields.map((field) => newLogicField(field));
      const tableName = model.tableNameNotation || model.code || '';
      const tablePrefix = logicNamingParts(
        props.kind,
        domainDetail.value,
        '',
        model.businessType === '2' ? 'PROCESS' : 'EVENT',
        '',
        model.dataTimeliness || '1',
        model.tableCase || 1,
      ).prefix;
      if (tableName.toLowerCase().startsWith(tablePrefix.toLowerCase()))
        form.code = tableName.slice(tablePrefix.length);
    }
    if (!current()) return;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
  if (!current()) return;
  await options.load();
  if (current() && form.layerId === undefined)
    form.layerId = layers.value.find(
      (item) =>
        item.nodeType === 'LAYER' &&
        item.status !== 1 &&
        item.defaultTableType === page.value.tableType,
    )?.id;
}
function changeDomain() {
  timelinessWarning.value = '';
  form.subjectDomainId = undefined;
  form.entityId = undefined;
  form.objType = undefined;
  form.businessType = undefined;
}
function changeStandardType() {
  form.documentId = undefined;
}
function changeSubject() {
  timelinessWarning.value = '';
  form.entityId = undefined;
  form.objType = undefined;
  form.businessType = undefined;
}
function changeTimeliness() {
  timelinessWarning.value = '';
  const entity = currentEntity.value;
  if (!entity) return;
  form.code = logicNamingParts(
    props.kind,
    currentDomain.value,
    entity.code,
    entity.entitySubtype,
    currentSubject.value?.abbreviation || currentSubject.value?.code || '',
    form.dataTimeliness || '1',
    form.tableCase || 1,
  ).text;
}
function chooseEntity(entity: BusinessEntityApi.Summary) {
  if (disabled.value) return;
  form.entityId = String(entity.id);
  form.objType = undefined;
  form.businessType = undefined;
  if (props.kind === 'dimension')
    form.objType = entity.entitySubtype === 'HIERARCHICAL' ? '2' : '1';
  else if (props.kind === 'fact')
    form.businessType = entity.entitySubtype === 'PROCESS' ? '2' : '1';
  if (!form.subjectDomainId)
    form.subjectDomainId = String(entity.subjectDomainId);
  const parts = logicNamingParts(
    props.kind,
    currentDomain.value,
    entity.code,
    entity.entitySubtype,
    currentSubject.value?.abbreviation || currentSubject.value?.code || '',
    form.dataTimeliness || '1',
    form.tableCase || 1,
  );
  if (!isEdit.value) {
    form.code = parts.text;
    form.name = `${entity.name}${props.kind === 'dimension' ? '维度逻辑表' : '事实逻辑表'}`;
    fields.value = fields.value.map((field) =>
      field.pkFlag
        ? {
            ...field,
            fieldName: `${entity.code.toLowerCase()}_id`,
            cnName: `${entity.name}ID`,
            dimensionName: entity.name,
          }
        : field,
    );
  }
  entityPopover.value = false;
}
function changeOwner() {
  form.contactNumber =
    users.value.find((item) => String(item.id) === String(form.contactUserId))
      ?.mobile || '';
}
watch(
  () => form.dataDomainId,
  async (id) => {
    const current = domainGuard.begin();
    if (id !== undefined && String(domainDetail.value?.id) === String(id))
      return;
    domainDetail.value = undefined;
    domainFailed.value = false;
    domainLoading.value = false;
    if (id === undefined || id === '') return;
    domainLoading.value = true;
    try {
      const result = await getDataDomain(id);
      if (current()) domainDetail.value = result;
    } catch {
      if (current()) domainFailed.value = true;
    } finally {
      if (current()) domainLoading.value = false;
    }
  },
);
function toggleCase() {
  form.tableCase = form.tableCase === 2 ? 1 : 2;
  form.code =
    form.tableCase === 2 ? form.code?.toLowerCase() : form.code?.toUpperCase();
}
function validateHeader() {
  const result: Record<string, string> = {};
  if (form.dataDomainId === undefined || form.dataDomainId === '')
    result.dataDomainId = '请选择业务板块';
  if (form.subjectDomainId === undefined || form.subjectDomainId === '')
    result.subjectDomainId = '请选择主题域';
  if (
    !isApplication.value &&
    (form.entityId === undefined || form.entityId === '')
  )
    result.entityId = `请选择${props.kind === 'dimension' ? '业务对象' : '业务活动'}`;
  if (!form.code?.trim()) result.code = '请输入表名';
  else if (form.code.trim().length > 64) result.code = '表名不能超过 64 个字符';
  else if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(form.code.trim()))
    result.code = '表名须以字母开头，仅支持字母、数字和下划线';
  if (!form.name?.trim()) result.name = '请输入中文名称';
  else if (form.name.trim().length > 100)
    result.name = '中文名称不能超过 100 个字符';
  if (form.contactUserId === undefined || form.contactUserId === '')
    result.contactUserId = '请选择责任人';
  errors.value = result;
  return Object.keys(result).length === 0;
}
async function next() {
  if (!validateHeader() || disabled.value) return;
  if (isApplication.value) {
    step.value = 1;
    return;
  }
  const current = guard.begin();
  checkingTimeliness.value = true;
  timelinessWarning.value = '';
  try {
    for (let pageNo = 1; ; pageNo++) {
      const result = await getTableModelPage({
        pageNo,
        pageSize: 100,
        tableType: page.value.tableType,
        dataDomainId: form.dataDomainId,
        subjectDomainId: form.subjectDomainId,
      });
      if (!current()) return;
      if (
        result.list.some(
          (model) =>
            String(model.id) !== String(props.modelId) &&
            String(model.entityId) === String(form.entityId) &&
            model.dataTimeliness === form.dataTimeliness,
        )
      ) {
        timelinessWarning.value = `同${props.kind === 'dimension' ? '业务对象' : '业务活动'}、同数据时效下已创建${page.value.label}，建议修改。`;
        return;
      }
      if (pageNo * 100 >= result.total || result.list.length === 0) break;
    }
    step.value = 1;
  } finally {
    if (current()) checkingTimeliness.value = false;
  }
}
async function save() {
  if (disabled.value || !validateHeader()) return;
  const fieldError = validateLogicFields(fields.value);
  if (fieldError) {
    message.warning(fieldError);
    return;
  }
  if (form.layerId === undefined) {
    message.warning('请先配置对应表类型的数仓分层');
    return;
  }
  const current = guard.begin();
  saving.value = true;
  try {
    const rows = logicFieldPayload(fields.value);
    const payload = {
      ...modelSaveData(form),
      entityId: form.entityId,
      objType: form.objType,
      businessType: form.businessType,
      dataTimeliness: isApplication.value ? undefined : form.dataTimeliness,
      tableNameNotation: `${prefix.value}${form.code?.trim() || ''}`,
      code: form.code?.trim(),
      name: form.name?.trim(),
      fields: rows,
    };
    let id = props.modelId;
    if (id === undefined) id = await createTableModel(payload);
    else {
      const saved = await saveTableModelDraft({
        ...payload,
        modelId: id,
        expectedRevision: form.revision!,
        fields: rows,
        typeConfig: typeConfig.value,
      });
      if (saved === false) return;
    }
    if (!current() || id === undefined) return;
    message.success('保存成功');
    emit('saved', id);
  } finally {
    if (current()) saving.value = false;
  }
}
watch(
  () =>
    `${access.accessToken}/${access.refreshToken}/${access.accessCodes.join(',')}/${props.kind}/${props.modelId ?? 'new'}`,
  loadModel,
  { immediate: true },
);
defineExpose({ confirmLeave: () => Promise.resolve(!saving.value) });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <div class="qd-logic-editor">
      <div class="qd-editor-steps">
        <div class="qd-editor-steps__track">
          <div
            class="qd-editor-steps__item"
            :class="[{ 'is-active': step === 0, 'is-finished': step > 0 }]"
          >
            <span>1</span>基础信息
          </div>
          <div
            class="qd-editor-steps__item"
            :class="[{ 'is-active': step === 1 }]"
          >
            <span>2</span>{{ isApplication ? '表结构设计' : '属性字段设计' }}
          </div>
        </div>
      </div>
      <div class="qd-editor-panel">
        <Spin :spinning="loading || optionsLoading">
          <Alert
            v-if="failed || options.error.value || domainFailed"
            type="error"
            message="页面数据加载失败，请重试。"
            show-icon
          >
            <template #action>
              <Button @click="loadModel">重试</Button>
            </template>
          </Alert>
          <Alert
            v-if="readonly"
            type="warning"
            message="当前模型已废弃，不能修改。"
            show-icon
          />
          <div v-show="step === 0" class="qd-editor-basics">
            <h3 class="qd-editor-section">基础信息</h3>
            <div class="qd-editor-form">
              <div class="qd-editor-form__item">
                <label class="is-required" for="logic-board">业务板块</label>
                <div class="qd-editor-form__control">
                  <Select
                    id="logic-board"
                    v-model:value="form.dataDomainId"
                    :disabled="disabled || isEdit"
                    :options="domainOptions"
                    placeholder="请选择数据板块"
                    show-search
                    option-filter-prop="label"
                    :status="errors.dataDomainId ? 'error' : undefined"
                    @change="changeDomain"
                  >
                    <template #optionRender="{ option }">
                      <img
                        :src="domainIcon"
                        class="qd-editor-domain-icon"
                        alt=""
                      />{{ option.label }}
                    </template>
</Select><small v-if="errors.dataDomainId">{{
                    errors.dataDomainId
                  }}</small>
                </div>
              </div>
              <div class="qd-editor-form__item">
                <label class="is-required" for="logic-subject">主题域</label>
                <div class="qd-editor-form__control">
                  <Select
                    id="logic-subject"
                    v-model:value="form.subjectDomainId"
                    :disabled="
                      disabled || isEdit || form.dataDomainId === undefined
                    "
                    :options="subjectOptions"
                    placeholder="请选择主题域"
                    :status="errors.subjectDomainId ? 'error' : undefined"
                    @change="changeSubject"
                    show-search
                    option-filter-prop="label"
                  /><small v-if="errors.subjectDomainId">{{
                    errors.subjectDomainId
                  }}</small>
                </div>
              </div>
              <template v-if="!isApplication">
                <div class="qd-editor-form__item">
                  <label class="is-required">{{
                    kind === 'dimension' ? '业务对象' : '业务活动'
                  }}</label>
                  <div class="qd-editor-form__control">
                    <Popover
                      v-model:open="entityPopover"
                      trigger="click"
                      placement="bottomLeft"
                      :auto-adjust-overflow="true"
                      destroy-on-hidden
                      :arrow="!narrowScreen"
                      :classes="{ root: 'qd-entity-popover' }"
                    >
                      <template #content>
                        <div class="qd-entity-picker">
                          <Input
                            v-model:value="entityKeyword"
                            :placeholder="
                              kind === 'dimension'
                                ? '请输入业务对象名称'
                                : '请输入业务活动名称'
                            "
                          >
                            <template #prefix>
                              <QdIcon name="search" />
                            </template>
                          </Input>
                          <div class="qd-entity-picker__body">
                            <div class="qd-entity-picker__groups">
                              <button
                                v-for="group in entityGroups"
                                :key="group.value"
                                :class="{
                                  'is-active': entityGroup === group.value,
                                }"
                                type="button"
                                @click="entityGroup = group.value"
                              >
                                <img
                                  v-if="group.icon"
                                  :src="group.icon"
                                  alt=""
                                  class="qd-entity-picker__group-icon"
                                /><QdIcon v-else name="menu" />{{ group.label }}
                              </button>
                            </div>
                            <div class="qd-entity-picker__rows">
                              <Spin :spinning="optionsLoading">
                                <button
                                  v-for="entity in entityOptions"
                                  :key="String(entity.id)"
                                  type="button"
                                  :class="{
                                    'is-selected':
                                      String(form.entityId) ===
                                      String(entity.id),
                                  }"
                                  @click="chooseEntity(entity)"
                                >
                                  <span class="qd-entity-picker__radio"></span><span
                                    :title="`${entity.name} (${entity.code})`"
                                    >{{ entity.name }}（{{
                                      entity.code
                                    }}）</span><span
                                    class="qd-entity-picker__state"
                                    :class="[
                                      {
                                        'is-created':
                                          (entity.logicalModelCount || 0) > 0,
                                      },
                                    ]"
                                    >{{
                                      (entity.logicalModelCount || 0) > 0
                                        ? '已创建逻辑表'
                                        : '未创建逻辑表'
                                    }}</span>
                                </button>
                                <p v-if="entityOptions.length === 0">
                                  暂无可选{{
                                    kind === 'dimension'
                                      ? '业务对象'
                                      : '业务活动'
                                  }}
                                </p>
                              </Spin>
                            </div>
                          </div>
                        </div>
</template><button
                        type="button"
                        class="qd-entity-trigger"
                        :class="{ 'is-error': errors.entityId }"
                        :disabled="
                          disabled || isEdit || form.dataDomainId === undefined
                        "
                      >
                        <span :class="{ 'is-placeholder': !currentEntity }">{{
                          currentEntity
                            ? `${currentEntity.name}（${currentEntity.code}）`
                            : `请选择${kind === 'dimension' ? '业务对象' : '业务活动'}`
                        }}</span><QdIcon name="arrowDown" />
                      </button>
</Popover><small v-if="errors.entityId">{{ errors.entityId }}</small>
                  </div>
                </div>
                <div class="qd-editor-form__item">
                  <label>{{
                    kind === 'dimension' ? '维度类型' : '业务类型'
                  }}</label><Input
                    :value="entityTypeLabel"
                    disabled
                    :placeholder="
                      kind === 'dimension' ? '请选择维度类型' : '请选择事实类型'
                    "
                  />
                </div>
                <div class="qd-editor-form__item">
                  <label class="is-required">数据时效</label><RadioGroup
                    v-model:value="form.dataTimeliness"
                    :disabled="disabled"
                    @change="changeTimeliness"
                  >
                    <Radio
                      v-for="option in timelinessOptions"
                      :key="String(option.value)"
                      :value="option.value"
                    >
                      {{ option.label }}
                    </Radio>
                  </RadioGroup>
                  <small
                    v-if="timelinessWarning"
                    class="qd-editor-timeliness-warning"
                    >{{ timelinessWarning }}</small>
                </div>
              </template>
              <div class="qd-editor-form__item">
                <label class="is-required" for="logic-name-code">{{ page.label }}名</label>
                <div class="qd-editor-form__control">
                  <div class="qd-editor-name">
                    <span>{{ prefix }}</span><Input
                      id="logic-name-code"
                      v-model:value="form.code"
                      :disabled="disabled"
                      :status="errors.code ? 'error' : undefined"
                      placeholder="请输入英文表名"
                      :maxlength="64"
                    /><button
                      type="button"
                      :disabled="disabled"
                      :title="
                        form.tableCase === 2 ? '切换为大写' : '切换为小写'
                      "
                      @click="toggleCase"
                    >
                      {{ form.tableCase === 2 ? '小写' : '大写' }}
                    </button>
                  </div>
                  <small v-if="errors.code">{{ errors.code }}</small>
                </div>
              </div>
              <div class="qd-editor-form__item">
                <label class="is-required" for="logic-cn-name">中文名称</label>
                <div class="qd-editor-form__control">
                  <Input
                    id="logic-cn-name"
                    v-model:value="form.name"
                    :disabled="disabled"
                    :status="errors.name ? 'error' : undefined"
                    placeholder="请输入中文名称"
                    :maxlength="100"
                  /><small v-if="errors.name">{{ errors.name }}</small>
                </div>
              </div>
              <div class="qd-editor-form__item">
                <label for="logic-standard-type">标准类型</label><Select
                  id="logic-standard-type"
                  v-model:value="form.documentType"
                  :disabled="disabled"
                  :options="standardTypeOptions"
                  placeholder="请选择标准类型"
                  allow-clear
                  @change="changeStandardType"
                />
              </div>
              <div class="qd-editor-form__item">
                <label for="logic-standard-doc">标准登记</label><Select
                  id="logic-standard-doc"
                  v-model:value="form.documentId"
                  :disabled="disabled || !form.documentType"
                  :options="documentOptions"
                  placeholder="请选择标准登记"
                  allow-clear
                  show-search
                  option-filter-prop="label"
                />
              </div>
              <div class="qd-editor-form__item">
                <label class="is-required" for="logic-owner">责任人</label>
                <div class="qd-editor-form__control">
                  <Select
                    id="logic-owner"
                    v-model:value="form.contactUserId"
                    :disabled="disabled"
                    :options="userOptions"
                    placeholder="请选择责任人"
                    :status="errors.contactUserId ? 'error' : undefined"
                    show-search
                    option-filter-prop="label"
                    @change="changeOwner"
                  /><small v-if="errors.contactUserId">{{
                    errors.contactUserId
                  }}</small>
                </div>
              </div>
              <div class="qd-editor-form__item">
                <label for="logic-contact">联系电话</label><Input
                  id="logic-contact"
                  :value="form.contactNumber"
                  placeholder="请输入联系电话"
                  disabled
                />
              </div>
              <div class="qd-editor-form__item">
                <label>状态</label><RadioGroup v-model:value="form.enabled" :disabled="disabled">
                  <Radio :value="1">启用</Radio><Radio :value="0">禁用</Radio>
                </RadioGroup>
              </div>
              <div class="qd-editor-form__item is-full is-top">
                <label for="logic-description">描述</label><TextArea
                  id="logic-description"
                  v-model:value="form.description"
                  :disabled="disabled"
                  :maxlength="256"
                  :rows="4"
                  show-count
                  :placeholder="
                    kind === 'dimension'
                      ? '请输入业务对象、使用范围等描述'
                      : kind === 'fact'
                        ? '请输入业务活动、事件或流程口径等描述'
                        : '请输入应用场景、使用范围等描述'
                  "
                />
              </div>
            </div>
          </div>
          <div v-show="step === 1" class="qd-editor-fields">
            <h3 class="qd-editor-section">
              {{ isApplication ? '表结构设计' : '属性字段设计' }}
            </h3>
            <FieldGrid
              v-model="fields"
              :kind="kind"
              :disabled="disabled"
              :standards="standards"
              :code-tables="codeTableOptions"
              :classifications="classificationOptions"
              :dimensions="dimensionOptions"
            >
              <template #actions>
                <template v-if="isApplication">
                  <button
                    type="button"
                    class="qd-editor-button is-plain"
                    :disabled="disabled"
                    @click="message.info('功能开发中')"
                  >
                    从派生指标引用
</button><button
                    type="button"
                    class="qd-editor-button is-plain"
                    :disabled="disabled"
                    @click="message.info('功能开发中')"
                  >
                    从代码表引入
                  </button>
</template><button
                  v-else
                  type="button"
                  class="qd-editor-button is-plain"
                  :disabled="disabled"
                  @click="message.info('功能开发中')"
                >
                  从逻辑表引入
</button><button
                  type="button"
                  class="qd-editor-button is-plain"
                  :disabled="disabled"
                  @click="message.info('功能开发中')"
                >
                  SQL解析
                </button>
              </template>
            </FieldGrid>
          </div>
        </Spin>
        <div class="qd-editor-footer">
          <button
            class="qd-editor-button"
            type="button"
            :disabled="saving"
            @click="emit('cancel')"
          >
            返回列表
</button><button
            v-if="step === 1"
            class="qd-editor-button"
            type="button"
            :disabled="saving"
            @click="step = 0"
          >
            上一步
</button><button
            v-if="step === 0"
            class="qd-editor-button is-primary"
            type="button"
            :disabled="disabled || failed || domainFailed || optionsLoading"
            @click="next"
          >
            下一步
</button><button
            v-else
            class="qd-editor-button is-primary"
            type="button"
            :disabled="disabled"
            @click="save"
          >
            {{ saving ? '保存中…' : '保存并返回' }}
          </button>
        </div>
      </div>
    </div>
  </ConfigProvider>
</template>
