<script lang="ts" setup>
import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Input, message, Select } from 'antdv-next';

import { batchAssignMetaOwner } from '#/api/governance/metaobject';
import { getSimpleUserList } from '#/api/system/user';

defineOptions({ name: 'GovernanceMetaObjectOwnerForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const ids = ref<number[]>([]);
const model = reactive({
  techLeaderUserId: undefined as number | undefined,
  techLeaderPhone: undefined as string | undefined,
  businessLeaderUserId: undefined as number | undefined,
  businessLeaderPhone: undefined as string | undefined,
});
const users = ref<{ label: string; mobile?: string; value: number }[]>([]);

async function loadUsers() {
  const userList = await getSimpleUserList().catch(() => []);
  users.value = (userList ?? []).map((item: any) => ({
    label: item.nickname,
    mobile: item.mobile,
    value: item.id,
  }));
}

function handleLeaderChange(field: 'business' | 'tech', value: any) {
  const user = users.value.find((item) => item.value === value);
  if (field === 'tech') {
    model.techLeaderPhone = user?.mobile;
  } else {
    model.businessLeaderPhone = user?.mobile;
  }
}

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!model.techLeaderUserId && !model.businessLeaderUserId) {
      message.warning('请至少指定一位技术或业务负责人');
      return;
    }
    modalApi.lock();
    try {
      await batchAssignMetaOwner({
        ids: ids.value,
        ...model,
      });
      message.success(`已为 ${ids.value.length} 张表更新认责（已留痕）`);
      emit('success');
      await modalApi.close();
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      ids.value = [];
      model.techLeaderUserId = undefined;
      model.techLeaderPhone = undefined;
      model.businessLeaderUserId = undefined;
      model.businessLeaderPhone = undefined;
      return;
    }
    const data = modalApi.getData() as { ids?: number[] };
    ids.value = data?.ids ?? [];
    await loadUsers();
  },
});
</script>

<template>
  <ModalComponent class="w-[800px]" title="批量认责">
    <div
      class="owner-form governance-modal-form governance-form-item-horizontal"
    >
      <Alert show-icon type="info">
        <template #message>
          将覆盖所选
          {{ ids.length }}
          张表的负责人字段，并写入手工变更明细；未选择的一侧保持原值。
        </template>
      </Alert>
      <div class="form-item">
        <label class="form-item__label">技术负责人</label>
        <div class="flex gap-2">
          <Select
            v-model:value="model.techLeaderUserId"
            :options="users"
            allow-clear
            class="flex-1"
            option-filter-prop="label"
            placeholder="请选择"
            show-search
            @change="(value: any) => handleLeaderChange('tech', value)"
          />
          <Input
            v-model:value="model.techLeaderPhone"
            class="w-36"
            placeholder="电话"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">业务负责人</label>
        <div class="flex gap-2">
          <Select
            v-model:value="model.businessLeaderUserId"
            :options="users"
            allow-clear
            class="flex-1"
            option-filter-prop="label"
            placeholder="请选择"
            show-search
            @change="(value: any) => handleLeaderChange('business', value)"
          />
          <Input
            v-model:value="model.businessLeaderPhone"
            class="w-36"
            placeholder="电话"
          />
        </div>
      </div>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';
</style>
