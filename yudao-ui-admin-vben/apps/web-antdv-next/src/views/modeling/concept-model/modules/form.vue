<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';
import type { ConceptModelApi } from '#/api/modeling/concept-model';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, message } from 'antdv-next';

import { useVbenForm, z } from '#/adapter/form';
import {
  createConceptModel,
  getConceptModel,
  updateConceptModel,
} from '#/api/modeling/concept-model';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import { emptyDesign, validateDesign } from '../design';
import DesignEditor from './design-editor.vue';

const emit = defineEmits(['success']);
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const model = ref<ConceptModelApi.Model>();
const design = ref(emptyDesign());
const opening = ref(0);
const readonly = ref(false);
const ready = ref(false);
const loadFailed = ref(false);
const optionsFailed = ref(false);
const saving = ref(false);
const subjectChoices = ref<
  { dataDomainId: string; label: string; value: string }[]
>([]);
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const title = computed(() => {
  if (readonly.value) return '概念模型详情';
  return model.value ? '编辑概念模型' : '新增概念模型';
});
const canReadDomains = computed(() =>
  hasAccessByCodes(['modeling:data-domain:query']),
);
const request = useModelingRequest(
  () => `${scope.value}/${opening.value}`,
  () => {
    ready.value = false;
    model.value = undefined;
    design.value = emptyDesign();
    saving.value = false;
    subjectChoices.value = [];
  },
);

function subjectDependencies(): VbenFormSchema['dependencies'] {
  return {
    triggerFields: ['dataDomainId'],
    resolve: ({ values }) => ({
      componentProps: {
        options: subjectChoices.value.filter(
          (item) => item.dataDomainId === String(values.dataDomainId),
        ),
      },
    }),
  };
}

const [Form, formApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 100 },
  wrapperClass: 'grid-cols-1 md:grid-cols-2',
  showDefaultActions: false,
  schema: [
    {
      fieldName: 'name',
      label: '模型名称',
      component: 'Input',
      rules: z.string().trim().min(1, '请输入模型名称').max(100),
      componentProps: { maxlength: 100, placeholder: '例如：客户交易概念模型' },
    },
    {
      fieldName: 'code',
      label: '模型编码',
      component: 'Input',
      rules: z
        .string()
        .trim()
        .regex(
          /^[A-Z][\w]{0,63}$/i,
          '以字母开头，仅含字母、数字或下划线，最多 64 位',
        ),
      componentProps: { maxlength: 64, placeholder: '例如：customer_trade' },
    },
    {
      fieldName: 'dataDomainId',
      label: '数据板块',
      component: 'Select',
      rules: 'selectRequired',
      componentProps: {
        options: [],
        placeholder: '请选择所属数据分域',
        showSearch: true,
        optionFilterProp: 'label',
        onChange: () => formApi.setFieldValue('subjectDomainId', undefined),
      },
    },
    {
      fieldName: 'subjectDomainId',
      label: '主题域',
      component: 'Select',
      dependencies: subjectDependencies(),
      componentProps: {
        options: [],
        allowClear: true,
        placeholder: '可选',
        showSearch: true,
        optionFilterProp: 'label',
      },
    },
    {
      fieldName: 'description',
      label: '业务说明',
      component: 'TextArea',
      formItemClass: 'md:col-span-2',
      componentProps: {
        maxlength: 2000,
        rows: 2,
        placeholder: '描述模型的业务边界和用途',
      },
    },
  ],
});

async function loadOptions(current: () => boolean) {
  const outcomes = await Promise.allSettled([
    canReadDomains.value ? getDataDomainSimpleList() : Promise.resolve([]),
    hasAccessByCodes(['modeling:subject-domain:query'])
      ? getSubjectDomainList({ status: 0 })
      : Promise.resolve([]),
  ]);
  if (!current()) return;
  const domainResult = outcomes[0];
  const subjectResult = outcomes[1];
  optionsFailed.value = outcomes.some((result) => result.status === 'rejected');
  const domains =
    domainResult.status === 'fulfilled'
      ? domainResult.value
          .filter(
            (item) =>
              item.id !== undefined && item.id !== null && item.status !== 1,
          )
          .map((item) => ({
            label: item.name ?? String(item.id),
            value: String(item.id),
          }))
      : [];
  const subjects =
    subjectResult.status === 'fulfilled'
      ? subjectResult.value
          .filter(
            (item) =>
              item.id !== undefined && item.id !== null && item.status !== 1,
          )
          .map((item) => ({
            dataDomainId: String(item.dataDomainId),
            label: item.name ?? String(item.id),
            value: String(item.id),
          }))
      : [];
  if (
    model.value &&
    !domains.some((item) => item.value === String(model.value?.dataDomainId))
  )
    domains.push({
      label: model.value.dataDomainName ?? String(model.value.dataDomainId),
      value: String(model.value.dataDomainId),
    });
  if (
    model.value?.subjectDomainId !== undefined &&
    model.value?.subjectDomainId !== null &&
    !subjects.some(
      (item) => item.value === String(model.value?.subjectDomainId),
    )
  )
    subjects.push({
      dataDomainId: String(model.value.dataDomainId),
      label:
        model.value.subjectDomainName ?? String(model.value.subjectDomainId),
      value: String(model.value.subjectDomainId),
    });
  subjectChoices.value = subjects;
  const values = await formApi.getValues();
  if (!current()) return;
  await formApi.updateSchema([
    {
      fieldName: 'dataDomainId',
      componentProps: {
        options: domains,
        disabled: readonly.value || !canReadDomains.value,
      },
    },
    {
      fieldName: 'subjectDomainId',
      dependencies: subjectDependencies(),
      componentProps: {
        options: subjects.filter(
          (item) => item.dataDomainId === String(values.dataDomainId),
        ),
        disabled:
          readonly.value ||
          !hasAccessByCodes(['modeling:subject-domain:query']),
      },
    },
  ]);
}

const [Modal, modalApi] = useVbenModal({
  async onOpenChange(isOpen) {
    opening.value += 1;
    const current = request.begin();
    ready.value = false;
    model.value = undefined;
    design.value = emptyDesign();
    loadFailed.value = false;
    optionsFailed.value = false;
    await formApi.resetForm();
    if (!isOpen || !current()) return;
    const data = (modalApi.getData() ?? {}) as {
      dataDomainId?: ConceptModelApi.Id;
      id?: ConceptModelApi.Id;
      readonly?: boolean;
      subjectDomainId?: ConceptModelApi.Id;
    };
    readonly.value = data.readonly === true;
    modalApi.lock();
    try {
      if (data.id === undefined || data.id === null) {
        readonly.value ||= !hasAccessByCodes(['modeling:concept-model:create']);
        await formApi.setValues({
          dataDomainId:
            data.dataDomainId === undefined
              ? undefined
              : String(data.dataDomainId),
          subjectDomainId:
            data.subjectDomainId === undefined
              ? undefined
              : String(data.subjectDomainId),
        });
      } else {
        const detail = await getConceptModel(data.id);
        if (!current()) return;
        model.value = detail;
        readonly.value ||=
          detail.status === 'ACTIVE' ||
          !hasAccessByCodes(['modeling:concept-model:update']);
        design.value = detail.design ?? emptyDesign();
        await formApi.setValues({
          ...detail,
          dataDomainId: String(detail.dataDomainId),
          subjectDomainId:
            detail.subjectDomainId === null ||
            detail.subjectDomainId === undefined
              ? undefined
              : String(detail.subjectDomainId),
        });
      }
      if (!current()) return;
      formApi.setState({ commonConfig: { disabled: readonly.value } });
      await loadOptions(current);
      if (current()) ready.value = true;
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onConfirm() {
    if (readonly.value || !ready.value || saving.value) return;
    const current = request.begin();
    const { valid } = await formApi.validate();
    if (!valid || !current()) return;
    const error = validateDesign(design.value);
    if (error) {
      message.warning(error);
      return;
    }
    const values = await formApi.getValues();
    if (!current()) return;
    const data: ConceptModelApi.SaveReq = {
      name: values.name,
      code: values.code,
      dataDomainId: values.dataDomainId,
      subjectDomainId: values.subjectDomainId ?? null,
      description: values.description ?? null,
      design: JSON.parse(JSON.stringify(design.value)),
    };
    modalApi.lock();
    saving.value = true;
    try {
      await (model.value
        ? updateConceptModel({
            ...data,
            id: model.value.id,
            expectedRevision: model.value.revision,
          })
        : createConceptModel(data));
      if (!current()) return;
      message.success('概念模型已保存');
      emit('success');
      await modalApi.close();
    } finally {
      if (current()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
</script>

<template>
  <Modal
    :title="title"
    class="w-[1120px] max-w-full"
    :show-confirm-button="!readonly && ready"
    :confirm-disabled="!ready"
    confirm-text="保存草稿"
    cancel-text="关闭"
  >
    <div class="space-y-4 px-2">
      <template v-if="loadFailed"></template>
      <Alert
        v-else-if="model?.status === 'ACTIVE'"
        type="info"
        show-icon
        message="模型已启用，设计为只读。需要修改时，请先在列表中停用。"
      />
      <Alert
        v-else-if="!readonly"
        type="info"
        show-icon
        message="概念模型用于定义业务实体及关系。"
      />
      <Alert
        v-if="!readonly && !canReadDomains"
        type="warning"
        show-icon
        message="当前账号没有数据分域查询权限，无法选择或变更数据板块。"
      />
      <Form />
      <DesignEditor v-if="ready" v-model="design" :readonly="readonly" />
    </div>
  </Modal>
</template>
