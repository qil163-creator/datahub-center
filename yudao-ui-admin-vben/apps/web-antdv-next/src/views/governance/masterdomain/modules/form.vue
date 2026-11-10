<script setup lang="ts">
import type { LeaderOption } from '../data';

import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createMasterDomain,
  getMasterDomain,
  updateMasterDomain,
} from '#/api/governance/masterdomain';
import { getSimpleUserList } from '#/api/system/user';

import { GOVERNANCE_MODAL_FORM } from '../../common/form-layout';
import { useMasterScope } from '../../mastercommon/use-master-scope';
import { useFormSchema } from '../data';

const emit = defineEmits<{ success: [] }>();
const editId = ref<GovernanceMasterDomainApi.Id>();
const leaders = ref<LeaderOption[]>([]);
const loadingLeaders = ref(false);
const failed = ref(false);
const shown = ref(false);
const scope = useMasterScope(() => {
  void modalApi.close();
});
const title = computed(() =>
  editId.value === undefined ? '新增主数据域' : '修改主数据域',
);
const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema(
    () => leaders.value,
    () => loadingLeaders.value,
  ),
  async handleValuesChange(values, changedFields) {
    if (!shown.value || !changedFields.includes('leaderUserId')) return;
    await formApi.setFieldValue(
      'leaderPhone',
      leaderPhone(values.leaderUserId),
    );
  },
});
function leaderPhone(id?: GovernanceMasterDomainApi.Id) {
  return (
    leaders.value.find((leader) => String(leader.value) === String(id))
      ?.mobile ?? ''
  );
}
const [Modal, modalApi] = useVbenModal({
  async onOpenChange(open) {
    scope.invalidate();
    const current = scope.begin('detail');
    shown.value = false;
    failed.value = false;
    leaders.value = [];
    loadingLeaders.value = false;
    modalApi.unlock();
    await formApi.reset();
    if (!current()) return;
    editId.value = undefined;
    if (!open) return;
    const row = modalApi.getData() as { id?: GovernanceMasterDomainApi.Id };
    editId.value = row?.id;
    loadingLeaders.value = true;
    modalApi.lock();
    try {
      const [users, record] = await Promise.all([
        getSimpleUserList(),
        row?.id === undefined
          ? Promise.resolve(undefined)
          : getMasterDomain(row.id),
      ]);
      if (!current()) return;
      leaders.value = users
        .filter((user) => user.id !== undefined)
        .map((user) => ({
          label: user.nickname || String(user.id),
          value: String(user.id),
          mobile: user.mobile,
        }));
      const leaderId = record?.leaderUserId;
      if (
        leaderId !== null &&
        leaderId !== undefined &&
        !leaders.value.some(
          (leader) => String(leader.value) === String(leaderId),
        )
      ) {
        leaders.value.push({
          label: record?.leaderUserName || String(leaderId),
          value: String(leaderId),
          mobile: record?.leaderPhone,
        });
      }
      if (record) {
        await formApi.setValues({
          ...record,
          leaderUserId:
            leaderId === null || leaderId === undefined
              ? undefined
              : String(leaderId),
          leaderPhone:
            leaderId === null || leaderId === undefined
              ? ''
              : leaderPhone(leaderId),
        });
      }
      if (current()) shown.value = true;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) {
        loadingLeaders.value = false;
        modalApi.unlock();
      }
    }
  },
  async onConfirm() {
    if (!shown.value || failed.value || loadingLeaders.value) return;
    const current = scope.begin('save');
    const validation = await formApi.validate();
    if (!validation.valid || !current()) return;
    modalApi.lock();
    try {
      const values = await formApi.getValues();
      if (!current()) return;
      const data: GovernanceMasterDomainApi.SaveReq = {
        id: editId.value,
        name: values.name,
        code: values.code,
        leaderUserId: values.leaderUserId,
        leaderPhone: leaderPhone(values.leaderUserId),
        status: values.status,
        description: values.description,
        remark: values.remark,
      };
      await (editId.value === undefined
        ? createMasterDomain(data)
        : updateMasterDomain(data));
      if (!current()) return;
      message.success('主数据域已保存');
      emit('success');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="title" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
