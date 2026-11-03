<script lang="ts" setup>
import type { FoundationSystemSourceApi } from '#/api/dataforge/systemsource';
import type { SystemDeptApi } from '#/api/system/dept';
import type { SystemUserApi } from '#/api/system/user';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createSystemSource,
  getSystemSource,
  updateSystemSource,
} from '#/api/dataforge/systemsource';
import { getDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';
import { $t } from '#/locales';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<FoundationSystemSourceApi.SystemSource>();
const deptList = ref<SystemDeptApi.Dept[]>([]);
const userList = ref<SystemUserApi.User[]>([]);

const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['来源系统'])
    : $t('ui.actionTitle.create', ['来源系统']);
});

function getDeptName(deptId?: number) {
  if (!deptId) {
    return '';
  }
  return deptList.value.find((item) => item.id === Number(deptId))?.name || '';
}

function getOwnerOptions(deptId?: number) {
  if (!deptId) {
    return [];
  }
  return userList.value
    .filter((user) => user.deptId === Number(deptId))
    .map((user) => ({
      label: user.nickname,
      value: user.id!,
    }));
}

function getOwnerInfo(ownerUserId?: number) {
  if (!ownerUserId) {
    return { ownerUserName: '', ownerMobile: '' };
  }
  const user = userList.value.find((item) => item.id === Number(ownerUserId));
  return {
    ownerUserName: user?.nickname || '',
    ownerMobile: user?.mobile || '',
  };
}

function isOwnerInDept(ownerUserId?: number, deptId?: number) {
  if (!ownerUserId || !deptId) {
    return false;
  }
  return userList.value.some(
    (user) => user.id === Number(ownerUserId) && user.deptId === Number(deptId),
  );
}

async function syncDeptName(deptId?: number) {
  await formApi.setFieldValue('deptName', getDeptName(deptId));
}

async function syncOwnerInfo(ownerUserId?: number) {
  const { ownerUserName, ownerMobile } = getOwnerInfo(ownerUserId);
  await formApi.setFieldValue('ownerUserName', ownerUserName);
  await formApi.setFieldValue('ownerMobile', ownerMobile);
}

async function clearOwnerFields() {
  await formApi.setFieldValue('ownerUserId', undefined);
  await formApi.setFieldValue('ownerUserName', '');
  await formApi.setFieldValue('ownerMobile', '');
}

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-1',
    labelWidth: 130,
  },
  wrapperClass: 'grid-cols-2',
  layout: 'horizontal',
  schema: useFormSchema(getOwnerOptions),
  showDefaultActions: false,
  handleValuesChange: async (values, changedFields) => {
    if (changedFields.includes('deptId')) {
      await syncDeptName(values.deptId);
      if (
        values.ownerUserId &&
        !isOwnerInDept(values.ownerUserId, values.deptId)
      ) {
        await clearOwnerFields();
      }
    }
    if (changedFields.includes('ownerUserId')) {
      await syncOwnerInfo(values.ownerUserId);
    }
  },
});

async function buildSubmitData() {
  const values =
    (await formApi.getValues()) as FoundationSystemSourceApi.SystemSource;
  return {
    id: values.id,
    name: values.name,
    icon: values.icon,
    url: values.url,
    type: values.type,
    ownerUserId: values.ownerUserId,
    description: values.description,
    sortOrder: Number(values.sortOrder ?? 0),
    deptId: values.deptId,
    status: values.status,
    remark: values.remark,
  } as FoundationSystemSourceApi.SystemSource;
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    try {
      const data = await buildSubmitData();
      await (formData.value?.id
        ? updateSystemSource(data)
        : createSystemSource(data));
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
    try {
      [deptList.value, userList.value] = await Promise.all([
        getDeptList(),
        getSimpleUserList(),
      ]);

      const data = modalApi.getData() as FoundationSystemSourceApi.SystemSource;
      await formApi.resetForm();
      if (!data?.id) {
        formData.value = undefined;
        await formApi.setValues({
          status: CommonStatusEnum.ENABLE,
          sortOrder: 0,
          ...(data?.deptId ? { deptId: data.deptId } : {}),
        });
        if (data?.deptId) {
          await syncDeptName(data.deptId);
        }
        return;
      }
      modalApi.lock();
      try {
        formData.value = await getSystemSource(data.id);
        await formApi.setValues({
          ...formData.value,
          status: Number(formData.value.status ?? CommonStatusEnum.ENABLE),
          sortOrder: Number(formData.value.sortOrder ?? 0),
          deptId: formData.value.deptId
            ? Number(formData.value.deptId)
            : undefined,
        });
        await syncDeptName(formData.value.deptId);
        await syncOwnerInfo(formData.value.ownerUserId);
      } finally {
        modalApi.unlock();
      }
    } catch (error) {
      console.error(error);
      message.error('加载表单数据失败，请稍后重试');
      await modalApi.close();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
