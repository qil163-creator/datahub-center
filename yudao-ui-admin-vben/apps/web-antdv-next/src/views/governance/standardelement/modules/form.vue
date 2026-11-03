<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { getDoc } from '#/api/governance/doc';
import {
  createStandardElement,
  getStandardElement,
  updateStandardElement,
} from '#/api/governance/standardelement';
import { useObjectRequest } from '#/hooks/use-object-request';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

import '../../standardcommon/standard.css';

const emit = defineEmits(['success']);
const formData = ref<GovernanceStandardElementApi.StandardElement>();
const identity = ref('closed');
const ready = ref(false);
const access = useAccessStore();
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    ready.value = false;
    formData.value = undefined;
  },
);
const extended = ref(false);
const boundDocument = ref<StandardDocApi.Doc>();
const getTitle = computed(() => {
  return formData.value?.id ? '修改数据元' : '新增数据元';
});

const schema = useFormSchema();
const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema,
});

function toSavePayload(values: Record<string, any>) {
  const { stdType: _stdType, __extended: _extended, ...payload } = values;
  return {
    ...payload,
    sort: payload.sort ?? 0,
    personCharge:
      payload.personCharge === undefined ||
      payload.personCharge === null ||
      payload.personCharge === ''
        ? undefined
        : payload.personCharge,
    documentId:
      payload.documentId === undefined ||
      payload.documentId === null ||
      payload.documentId === ''
        ? undefined
        : payload.documentId,
  } as GovernanceStandardElementApi.StandardElement;
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (!ready.value) return;
    const current = request.begin();
    const { valid } = await formApi.validate();
    if (!valid || !current()) {
      return;
    }
    modalApi.lock();
    try {
      const data = toSavePayload(await formApi.getValues());
      if (boundDocument.value) {
        data.documentId = boundDocument.value.id;
        data.standardType = boundDocument.value.stdType;
      }
      if (!current()) return;
      await (formData.value?.id
        ? updateStandardElement(data)
        : createStandardElement(data));
      if (!current()) return;
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    extended.value = false;
    if (!isOpen) {
      boundDocument.value = undefined;
      identity.value = 'closed';
      formData.value = undefined;
      modalApi.unlock();
      await formApi.resetForm();
      return;
    }
    const data =
      modalApi.getData() as GovernanceStandardElementApi.StandardElement;
    identity.value = String(data?.id ?? 'new');
    const current = request.begin();
    ready.value = false;
    modalApi.setState({ confirmDisabled: true });
    if (!data || !data.id) {
      if (data?.documentId) {
        const doc = await getDoc(data.documentId);
        if (!current()) return;
        boundDocument.value = doc;
      } else {
        boundDocument.value = undefined;
      }
      formApi.updateSchema(
        schema
          .filter((field) =>
            ['documentId', 'standardType'].includes(field.fieldName),
          )
          .map((field) =>
            boundDocument.value
              ? {
                  ...field,
                  dependencies: {
                    triggerFields: [],
                    resolve: () => ({ show: false }),
                  },
                }
              : field,
          ),
      );
      await formApi.setValues({
        categoryId: data?.categoryId,
        documentId: boundDocument.value?.id,
        standardType: boundDocument.value?.stdType,
        sort: 0,
        status: boundDocument.value
          ? CommonStatusEnum.DISABLE
          : CommonStatusEnum.ENABLE,
      });
      if (current()) {
        ready.value = true;
        modalApi.setState({ confirmDisabled: false });
      }
      return;
    }
    modalApi.lock();
    try {
      const detail = await getStandardElement(data.id);
      if (!current()) return;
      const values: Record<string, any> = { ...detail };
      if (values.documentId) {
        try {
          const doc = await getDoc(values.documentId);
          if (!current()) return;
          values.standardType = values.standardType || doc?.stdType;
        } catch {
          // 标准类型仅用于筛选标准登记，不影响数据元本身
        }
      }
      if (!current()) return;
      formData.value = detail;
      await formApi.setValues(values);
      if (current()) {
        ready.value = true;
        modalApi.setState({ confirmDisabled: false });
      }
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});

watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes.join(',')],
  () => modalApi.close(),
);
</script>

<template>
  <Modal
    :title="getTitle"
    :fullscreen-button="false"
    class="standard-modal qdata-standard"
    header-class="standard-modal-header"
    content-class="standard-modal-content"
    footer-class="standard-modal-footer"
  >
    <div v-if="boundDocument" class="text-muted-foreground mb-3 px-4 text-sm">
      关联标准：{{ boundDocument.nameCn }}
    </div>
    <Form class="standard-definition-form" />
    <button
      type="button"
      class="standard-link"
      @click="
        extended = !extended;
        formApi.setFieldValue('__extended', extended);
      "
    >
      <IconifyIcon
        :icon="extended ? 'lucide:chevron-up' : 'lucide:chevron-down'"
        class="mr-1 inline-block size-4 align-middle"
      />
      {{ extended ? '收起' : '展开' }}扩展属性
    </button>
  </Modal>
</template>
