<script setup lang="ts">
import type { StandardDocApi } from '#/api/governance/doc';
import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { getDoc } from '#/api/governance/doc';
import {
  createStandardCodeSet,
  getStandardCodeSet,
  getStandardCodeSetCategories,
  updateStandardCodeSet,
} from '#/api/governance/standardcodeset';
import { useObjectRequest } from '#/hooks/use-object-request';

import { GOVERNANCE_MODAL_FORM } from '../../common/form-layout';
import { createStandardDefinitionSchema } from '../../standardcommon/definition-schema';

import '../../standardcommon/standard.css';
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const identity = ref('closed');
const ready = ref(false);
const detail = ref<GovernanceStandardCodeSetApi.StandardCodeSet>();
const boundDocument = ref<StandardDocApi.Doc>();
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    ready.value = false;
    detail.value = undefined;
  },
);
const title = computed(() => (detail.value?.id ? '修改代码表' : '新增代码表'));
const schema = createStandardDefinitionSchema({
  categoryApi: getStandardCodeSetCategories,
  englishField: 'type',
});
const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema,
});
const [Modal, modalApi] = useVbenModal({
  async onOpenChange(open) {
    identity.value = open
      ? String(
          (
            modalApi.getData() as Partial<GovernanceStandardCodeSetApi.StandardCodeSet>
          )?.id ?? 'new',
        )
      : 'closed';
    const current = request.begin();
    ready.value = false;
    detail.value = undefined;
    boundDocument.value = undefined;
    modalApi.setState({ confirmDisabled: true });
    await formApi.resetForm();
    if (!open || !current()) {
      modalApi.unlock();
      return;
    }
    modalApi.lock();
    try {
      const context =
        modalApi.getData() as Partial<GovernanceStandardCodeSetApi.StandardCodeSet>;
      if (!context?.id && context?.documentId) {
        const doc = await getDoc(context.documentId);
        if (!current()) return;
        boundDocument.value = doc;
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
      const values = context?.id
        ? await getStandardCodeSet(context.id)
        : {
            categoryId: context?.categoryId,
            status: boundDocument.value ? 1 : 0,
            documentId: boundDocument.value?.id,
            standardType: boundDocument.value?.stdType,
          };
      if (!current()) return;
      if (context?.id)
        detail.value = values as GovernanceStandardCodeSetApi.StandardCodeSet;
      await formApi.setValues(values);
      if (current()) {
        ready.value = true;
        modalApi.setState({ confirmDisabled: false });
      }
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onConfirm() {
    if (!ready.value) return;
    const current = request.begin();
    const result = await formApi.validate();
    if (!current() || !result.valid) return;
    const values = await formApi.getValues();
    if (!current()) return;
    modalApi.lock();
    try {
      const payload = {
        ...detail.value,
        ...values,
        id: detail.value?.id,
        revision: detail.value?.revision,
      } as GovernanceStandardCodeSetApi.StandardCodeSet;
      if (boundDocument.value) {
        payload.documentId = boundDocument.value.id;
        payload.standardType = boundDocument.value.stdType;
      }
      await (detail.value?.id
        ? updateStandardCodeSet(payload)
        : createStandardCodeSet(payload));
      if (!current()) return;
      emit('success');
      message.success('保存成功');
      await modalApi.close();
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
    :title="title"
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
  </Modal>
</template>
