<script lang="ts" setup>
import type { SafetyWhitelistApi } from '#/api/governance/whitelist';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { fromTimestampPickerValue, toTimestampPickerValue } from '@vben/utils';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createWhitelist,
  getWhitelist,
  getWhitelistSubjectListByWhitelistId,
  updateWhitelist,
} from '#/api/governance/whitelist';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<SafetyWhitelistApi.Whitelist>();
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['脱敏白名单'])
    : $t('ui.actionTitle.create', ['脱敏白名单']);
});

const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const values = await formApi.getValues();
    // 组装提交数据：生效时段拆成开始/结束，生效主体按类型取对应的多选值
    const data = { ...values } as any;
    // 数据分类树选中类目节点（catalog-xx）时豁免范围为 CATALOG（含其下所有分类），选中分类叶子时为 CATEGORY
    if (
      typeof values.objectId === 'string' &&
      values.objectId.startsWith('catalog-')
    ) {
      data.objectType = 'CATALOG';
      data.objectId = Number(values.objectId.slice('catalog-'.length));
    } else {
      data.objectType = 'CATEGORY';
    }
    const [effectiveTime, expireTime] = values.effectiveTimeRange ?? [];
    data.effectiveTime = fromTimestampPickerValue(effectiveTime);
    data.expireTime = fromTimestampPickerValue(expireTime);
    const subjectIds: number[] =
      (values.subjectType === 'USER' && values.userIds) ||
      (values.subjectType === 'ROLE' && values.roleIds) ||
      (values.subjectType === 'DEPT' && values.deptIds) ||
      [];
    data.whitelistSubjects = subjectIds.map((subjectId) => ({ subjectId }));
    delete data.effectiveTimeRange;
    delete data.userIds;
    delete data.roleIds;
    delete data.deptIds;
    try {
      await (formData.value?.id
        ? updateWhitelist(data)
        : createWhitelist(data));
      // 关闭并提示
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      await formApi.resetForm();
      return;
    }
    // 加载数据
    const data = modalApi.getData() as SafetyWhitelistApi.Whitelist;
    if (!data || !data.id) {
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getWhitelist(data.id);
      const subjects = await getWhitelistSubjectListByWhitelistId(data.id);
      const subjectIds = subjects.map((item) => item.subjectId);
      // 回显：开始/结束时间合成时段，主体明细按类型填入对应多选框
      await formApi.setValues({
        ...formData.value,
        // 豁免范围为类目时还原成树节点值（catalog-xx）
        objectId:
          formData.value.objectType === 'CATALOG'
            ? `catalog-${formData.value.objectId}`
            : formData.value.objectId,
        effectiveTimeRange: (() => {
          const start = toTimestampPickerValue(
            formData.value.effectiveTime as null | number | string,
          );
          const end = toTimestampPickerValue(
            formData.value.expireTime as null | number | string,
          );
          return start && end ? [start, end] : undefined;
        })(),
        userIds: formData.value.subjectType === 'USER' ? subjectIds : [],
        roleIds: formData.value.subjectType === 'ROLE' ? subjectIds : [],
        deptIds: formData.value.subjectType === 'DEPT' ? subjectIds : [],
      });
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
