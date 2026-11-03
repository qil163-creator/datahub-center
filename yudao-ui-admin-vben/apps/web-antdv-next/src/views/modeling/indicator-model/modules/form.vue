<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, nextTick, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import {
  createIndicator,
  getAtomicIndicatorOptions,
  getIndicator,
  getIndicatorSourceFields,
  updateIndicator,
} from '#/api/modeling/indicator-model';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { getTableModelPage } from '#/api/modeling/tablemodel';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  AGGREGATION_LABELS,
  indicatorSaveData,
  options,
  PERIOD_LABELS,
  RESULT_LABELS,
  TYPE_LABELS,
} from '../data';
import AtomicEditor from './atomic-editor.vue';

const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = () =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]);
const indicatorType = ref<IndicatorModelApi.Type>('ATOMIC');
const original = ref<IndicatorModelApi.Model>();
const sourceDetail = ref<IndicatorModelApi.Model>();
const cloning = ref(false);
const ready = ref(false);
const opened = ref(false);
const error = ref('');
const sourceError = ref('');
const atomicEditor = ref<InstanceType<typeof AtomicEditor>>();
const atomicEditing = ref(false);

const domainId = ref<IndicatorModelApi.Id>();
type Option = { disabled?: boolean; label: string; value: string };
const domains = ref<Option[]>([]);
const subjects = ref<Option[]>([]);
const owners = ref<Option[]>([]);
const models = ref<Option[]>([]);
const fields = ref<IndicatorModelApi.SourceField[]>([]);
const atoms = ref<IndicatorModelApi.Model[]>([]);
const modelLoading = ref(false);
const sourceLoading = ref(false);
const loadGuard = useObjectRequest(scope, () => {
  if (opened.value) void modalApi.close();
});
const modelGuard = useObjectRequest(scope, () => {
  models.value = [];
});
const sourceGuard = useObjectRequest(scope, () => {
  fields.value = [];
});
const title = computed(() => {
  let action = '新增';
  if (cloning.value) action = '克隆';
  else if (original.value || atomicEditing.value) action = '修改';
  return `${action}${TYPE_LABELS[indicatorType.value]}`;
});
const fieldOptions = computed(() =>
  fields.value.map((item) => ({
    value: item.fieldUid,
    label: `${item.cnName || item.fieldName} · ${item.fieldName} (${item.dataType})`,
  })),
);
const timeOptions = computed(() =>
  fieldOptions.value.filter((item) =>
    fields.value.some(
      (field) =>
        field.fieldUid === item.value &&
        /^(DATE|DATETIME|TIMESTAMP)/i.test(field.dataType),
    ),
  ),
);

function schema(): VbenFormSchema[] {
  const common: VbenFormSchema[] = [
    {
      fieldName: 'code',
      label: '指标编码',
      component: 'Input',
      rules: 'required',
      componentProps: {
        maxlength: 100,
        placeholder: '字母开头，仅字母、数字和下划线',
      },
    },
    {
      fieldName: 'name',
      label: '指标名称',
      component: 'Input',
      rules: 'required',
      componentProps: { maxlength: 100 },
    },
    {
      fieldName: 'dataDomainId',
      label: '数据分域',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: () => ({
        options: domains.value,
        onChange: async (id: IndicatorModelApi.Id) => {
          domainId.value = id;
          await formApi.setFieldValue('baseIndicatorId', undefined);
        },
        showSearch: true,
        optionFilterProp: 'label',
      }),
    },
    {
      fieldName: 'subjectDomainId',
      label: '主题域',
      component: 'Select',
      componentProps: () => ({
        options: subjects.value,
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
        disabled: !hasAccessByCodes(['modeling:subject-domain:query']),
        placeholder: hasAccessByCodes(['modeling:subject-domain:query'])
          ? '可选'
          : '需要主题域查询权限',
      }),
    },
    {
      fieldName: 'ownerUserId',
      label: '负责人',
      component: 'Select',
      componentProps: () => ({
        options: owners.value,
        allowClear: true,
        showSearch: true,
        optionFilterProp: 'label',
      }),
    },
    {
      fieldName: 'dataFreshness',
      label: '数据时效',
      component: 'Input',
      componentProps: {
        maxlength: 100,
        placeholder: '例如：次日 08:00 前更新',
      },
    },
  ];
  const source: VbenFormSchema[] =
    indicatorType.value === 'ATOMIC'
      ? [
          {
            fieldName: 'sourceModelId',
            label: '来源逻辑模型',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: () => ({
              options: models.value,
              onChange: loadFields,
              showSearch: true,
              filterOption: false,
              loading: modelLoading.value,
              onSearch: searchModels,
              placeholder: '输入名称搜索逻辑模型',
            }),
          },
          {
            fieldName: 'valueFieldUid',
            label: '计算字段',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: () => ({
              options: fieldOptions.value,
              showSearch: true,
              optionFilterProp: 'label',
            }),
          },
          {
            fieldName: 'aggregation',
            label: '聚合方式',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: { options: options(AGGREGATION_LABELS) },
          },
          {
            fieldName: 'timeFieldUid',
            label: '统计时间字段',
            component: 'Select',
            componentProps: () => ({
              options: timeOptions.value,
              allowClear: true,
              placeholder: '派生周期指标需要此字段',
            }),
          },
          {
            fieldName: 'resultDataType',
            label: '结果类型',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: { options: options(RESULT_LABELS) },
          },
          {
            fieldName: 'unit',
            label: '计量单位',
            component: 'Input',
            componentProps: { maxlength: 50, placeholder: '例如：元、次、件' },
          },
        ]
      : [
          {
            fieldName: 'baseIndicatorId',
            label: '原子指标',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: () => ({
              options: atoms.value
                .filter(
                  (item) =>
                    String(item.dataDomainId) === String(domainId.value) &&
                    !!item.timeFieldUid,
                )
                .map((item) => ({
                  label: `${item.name} (${item.code})`,
                  value: String(item.id),
                })),
              showSearch: true,
              optionFilterProp: 'label',
              placeholder: '同一数据分域、已启用且有时间字段的原子指标',
            }),
          },
          {
            fieldName: 'timePeriod',
            label: '统计周期',
            component: 'Select',
            rules: 'selectRequired',
            componentProps: { options: options(PERIOD_LABELS) },
          },
          {
            fieldName: 'businessCondition',
            label: '业务限定',
            component: 'TextArea',
            rules: 'required',
            componentProps: {
              maxlength: 2000,
              rows: 3,
              placeholder: '例如：仅统计已支付订单；作为业务定义保存',
            },
          },
        ];
  return [
    ...common,
    ...source,
    {
      fieldName: 'definition',
      label: '指标口径',
      component: 'TextArea',
      componentProps: { maxlength: 2000, rows: 3 },
    },
    {
      fieldName: 'description',
      label: '说明',
      component: 'TextArea',
      componentProps: { maxlength: 2000, rows: 3 },
    },
  ];
}

const [Form, formApi] = useVbenForm({
  commonConfig: { labelWidth: 115, componentProps: { class: 'w-full' } },
  wrapperClass: 'grid-cols-1 md:grid-cols-2',
  schema: [],
  showDefaultActions: false,
});

async function searchModels(name = '') {
  const current = modelGuard.begin();
  modelLoading.value = true;
  sourceError.value = '';
  try {
    const result = await getTableModelPage({
      pageNo: 1,
      pageSize: 30,
      name: name || undefined,
    });
    if (!current()) return;
    models.value = result.list
      .filter((item) => item.status === 'PUBLISHED' && item.enabled === 1)
      .map((item) => ({
        value: String(item.id),
        label: `${item.name} (${item.code})`,
      }));
    if (
      sourceDetail.value?.sourceModelId &&
      !models.value.some(
        (item) => item.value === String(sourceDetail.value?.sourceModelId),
      )
    ) {
      models.value.unshift({
        value: String(sourceDetail.value.sourceModelId),
        disabled: true,
        label:
          sourceDetail.value.sourceModelName ||
          String(sourceDetail.value.sourceModelId),
      });
    }
  } catch {
    if (current()) sourceError.value = '逻辑模型加载失败，请重新搜索。';
  } finally {
    if (current()) modelLoading.value = false;
  }
}

async function loadFields(id?: IndicatorModelApi.Id) {
  const current = sourceGuard.begin();
  fields.value = [];
  sourceError.value = '';
  await formApi.setValues({
    valueFieldUid: undefined,
    timeFieldUid: undefined,
  });
  if (!id || !current()) {
    sourceLoading.value = false;
    return;
  }
  sourceLoading.value = true;
  try {
    const result = await getIndicatorSourceFields(id);
    if (current()) fields.value = result;
  } catch {
    if (current()) sourceError.value = '来源字段加载失败，请重新选择来源模型。';
  } finally {
    if (current()) sourceLoading.value = false;
  }
}

async function load() {
  const current = loadGuard.begin();
  ready.value = false;
  error.value = '';
  sourceError.value = '';
  modalApi.setState({ loading: true });
  try {
    const data = modalApi.getData();
    if (!data) throw new Error('未指定指标类型');
    indicatorType.value = data.indicatorType;
    if (data.indicatorType === 'ATOMIC') {
      cloning.value = data.cloneId !== undefined;
      atomicEditing.value = data.id !== undefined;
      await nextTick();
      if (!current()) return;
      await atomicEditor.value?.open(data);
      return;
    }
    const detailId = data.id ?? data.cloneId;
    const detail = detailId ? await getIndicator(detailId) : undefined;
    if (!current()) return;
    cloning.value = !!data.cloneId;
    sourceDetail.value = detail;
    original.value = cloning.value ? undefined : detail;
    indicatorType.value = detail?.indicatorType ?? data.indicatorType;
    if (detail && !cloning.value && detail.status !== 'DRAFT') {
      error.value = '启用的指标需先停用为草稿后编辑。';
      return;
    }
    if (
      indicatorType.value === 'ATOMIC' &&
      !hasAccessByCodes(['modeling:model:query'])
    ) {
      error.value = '维护来源模型需要逻辑模型查询权限。';
      return;
    }
    const [domainList, subjectList, ownerList, atomicList] = await Promise.all([
      getDataDomainSimpleList(),
      hasAccessByCodes(['modeling:subject-domain:query'])
        ? getSubjectDomainList({ status: 0 })
        : Promise.resolve([]),
      getSimpleUserList(),
      indicatorType.value === 'DERIVED'
        ? getAtomicIndicatorOptions()
        : Promise.resolve([]),
    ]);
    if (!current()) return;
    domains.value = domainList
      .filter(
        (item) =>
          item.id !== undefined &&
          (item.status === 0 ||
            String(item.id) === String(detail?.dataDomainId)),
      )
      .map((item) => ({
        value: String(item.id),
        label: item.name || String(item.id),
      }));
    subjects.value = subjectList.map((item) => ({
      value: String(item.id),
      label: item.name || String(item.id),
    }));
    owners.value = ownerList.map((item) => ({
      value: String(item.id),
      label: item.nickname || item.username,
    }));
    atoms.value = atomicList;
    domainId.value = detail?.dataDomainId;
    if (
      detail?.subjectDomainId &&
      !subjects.value.some(
        (item) => item.value === String(detail.subjectDomainId),
      )
    )
      subjects.value.push({
        value: String(detail.subjectDomainId),
        label: `主题域 ${detail.subjectDomainId}`,
      });
    if (
      detail?.ownerUserId &&
      !owners.value.some((item) => item.value === String(detail.ownerUserId))
    )
      owners.value.push({
        value: String(detail.ownerUserId),
        label: `用户 ${detail.ownerUserId}`,
      });
    if (indicatorType.value === 'ATOMIC') {
      await searchModels();
      if (!current()) return;
      if (detail?.sourceModelId) {
        const fieldList = await getIndicatorSourceFields(detail.sourceModelId);
        if (!current()) return;
        fields.value = fieldList;
      }
    }
    await formApi.setState({ schema: schema() });
    await formApi.resetForm();
    if (!current()) return;
    const values: Partial<IndicatorModelApi.Save> = detail
      ? {
          ...detail,
          ...(cloning.value
            ? {
                code: '',
                id: undefined,
                expectedRevision: undefined,
                status: 'DRAFT' as const,
              }
            : {}),
        }
      : { aggregation: 'SUM', resultDataType: 'DECIMAL', timePeriod: 'DAY' };
    for (const key of [
      'dataDomainId',
      'subjectDomainId',
      'ownerUserId',
      'sourceModelId',
      'baseIndicatorId',
    ] as const) {
      if (key in values && values[key] !== undefined && values[key] !== null)
        Object.assign(values, { [key]: String(values[key]) });
    }
    await formApi.setValues(values);
    if (current()) ready.value = true;
  } catch {
    if (current()) error.value = '指标或关联选项加载失败，请重试。';
  } finally {
    if (current()) {
      modalApi.setState({ loading: false });
    }
  }
}

const [Modal, modalApi] = useVbenModal<{
  cloneId?: IndicatorModelApi.Id;
  id?: IndicatorModelApi.Id;
  indicatorType: IndicatorModelApi.Type;
}>({
  async onConfirm() {
    if (indicatorType.value === 'ATOMIC') {
      if (!atomicEditor.value?.ready) return;
      const current = loadGuard.begin();
      modalApi.lock();
      try {
        const saved = await atomicEditor.value.submit();
        if (saved && current()) {
          emit('success');
          message.success('指标定义已保存');
          await modalApi.close();
        }
      } finally {
        if (current()) modalApi.unlock();
      }
      return;
    }
    if (!ready.value || sourceLoading.value || sourceError.value) return;
    const current = loadGuard.begin();
    const { valid } = await formApi.validate();
    if (!valid || !current()) return;
    modalApi.lock();
    try {
      const values = await formApi.getValues<IndicatorModelApi.Save>();
      if (!current()) return;
      const data = indicatorSaveData(
        { ...values, indicatorType: indicatorType.value },
        original.value,
      );
      await (original.value ? updateIndicator(data) : createIndicator(data));
      if (!current()) return;
      emit('success');
      message.success('指标定义已保存');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(open) {
    opened.value = open;
    if (open) {
      await load();
      return;
    }
    loadGuard.begin();
    modelGuard.begin();
    sourceGuard.begin();
    ready.value = false;
    original.value = undefined;
    sourceDetail.value = undefined;
    cloning.value = false;
    atomicEditing.value = false;
    atomicEditor.value?.reset();
    domains.value = [];
    subjects.value = [];
    owners.value = [];
    atoms.value = [];
    models.value = [];
    fields.value = [];
    modelLoading.value = false;
    sourceLoading.value = false;
    error.value = '';
    sourceError.value = '';
    modalApi.setState({ loading: false });
    modalApi.unlock();
    await formApi.resetForm();
  },
});
</script>

<template>
  <Modal
    :title="title"
    :class="
      indicatorType === 'ATOMIC'
        ? 'atomic-modal'
        : 'metric-derived-modal w-[940px]'
    "
    :header-class="
      indicatorType === 'ATOMIC'
        ? 'atomic-modal-header'
        : 'metric-derived-modal-header'
    "
    :content-class="
      indicatorType === 'ATOMIC'
        ? 'atomic-modal-content'
        : 'metric-derived-modal-content'
    "
    footer-class="atomic-modal-footer"
    :fullscreen-button="indicatorType !== 'ATOMIC'"
    :confirm-text="indicatorType === 'ATOMIC' ? '确认' : undefined"
    :confirm-disabled="
      indicatorType === 'ATOMIC'
        ? !atomicEditor?.ready
        : !ready || sourceLoading || !!sourceError
    "
  >
    <AtomicEditor
      v-if="opened && indicatorType === 'ATOMIC'"
      ref="atomicEditor"
    />
    <template v-else>
      <Alert
        class="mb-5"
        type="info"
        show-icon
        message="维护指标定义与业务口径；来源固定到已发布逻辑模型版本。保存后为草稿，启用后可用于派生指标。"
      />
      <Alert v-if="error" class="mb-4" type="error" :message="error">
        <template #action>
          <Button size="small" @click="load">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
        </template>
      </Alert>
      <Alert
        v-if="sourceError"
        class="mb-4"
        type="error"
        :message="sourceError"
      />
      <Form v-show="ready" />
    </template>
  </Modal>
</template>
