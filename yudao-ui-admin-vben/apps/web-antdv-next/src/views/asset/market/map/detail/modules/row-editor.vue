<script setup lang="ts">
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Form, FormItem, Input, message } from 'antdv-next';

import { updateImportedAssetRow } from '#/api/asset/dataasset/imported';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { columnDisplayName } from '../../data';

defineOptions({ name: 'AssetMapImportedRowEditor' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canEdit = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:data-asset:update']),
);
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const values = reactive<ImportedAssetApi.Values>({});
const columns = ref<DataAssetApi.MetadataColumn[]>([]);
const target = ref<{
  assetId: DataAssetApi.Id;
  revision: number;
  rowId: string;
}>();
const saving = ref(false);
const guard = createLatestWins();
let opened = false;
function reset() {
  guard.invalidate();
  target.value = undefined;
  columns.value = [];
  for (const key of Object.keys(values)) Reflect.deleteProperty(values, key);
  saving.value = false;
  modalApi.unlock();
}
const [Modal, modalApi] = useVbenModal({
  title: '修改',
  fullscreenButton: false,
  closeOnClickModal: false,
  onOpenChange(isOpen) {
    opened = isOpen;
    reset();
    if (!isOpen || !canEdit.value) return;
    const data = modalApi.getData() as {
      assetId: DataAssetApi.Id;
      columns: DataAssetApi.MetadataColumn[];
      revision: number;
      rowId: string;
      values: ImportedAssetApi.Values;
    };
    if (
      !parseResourceId(data?.assetId) ||
      !data.rowId ||
      !Number.isInteger(data.revision) ||
      data.revision < 0
    )
      return;
    target.value = {
      assetId: data.assetId,
      rowId: data.rowId,
      revision: data.revision,
    };
    columns.value = data.columns.filter(
      (column) =>
        column.pkFlag !== 1 &&
        column.columnName &&
        column.columnName.toUpperCase() !== 'ID' &&
        Object.hasOwn(data.values, column.columnName),
    );
    for (const column of columns.value)
      values[column.columnName!] = data.values[column.columnName!] ?? null;
  },
  async onConfirm() {
    if (
      !opened ||
      saving.value ||
      !canEdit.value ||
      !target.value ||
      columns.value.length === 0
    )
      return;
    const ticket = guard.next();
    const data = {
      id: target.value.assetId,
      rowId: target.value.rowId,
      revision: target.value.revision,
      values: { ...values },
    };
    saving.value = true;
    modalApi.lock();
    try {
      await updateImportedAssetRow(data);
      if (!ticket.isCurrent() || !opened) return;
      message.success('修改成功');
      emit('success');
      await modalApi.close();
    } finally {
      if (ticket.isCurrent()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
watch(
  owner,
  () => {
    opened = false;
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  reset();
});
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    class="!top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    :confirm-disabled="!canEdit || !target || saving"
  >
    <Form
      class="row-editor"
      :colon="false"
      :label-col="{ style: { width: '220px' } }"
      :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
    >
      <FormItem
        v-for="column in columns"
        :key="column.columnName"
        :label="`${column.columnName}（${columnDisplayName(column)}）`"
      >
        <Input
          :value="values[column.columnName!]"
          :placeholder="
            values[column.columnName!] === null ? 'NULL' : '请输入内容'
          "
          @update:value="(value) => (values[column.columnName!] = value)"
        />
      </FormItem>
    </Form>
  </Modal>
</template>

<style scoped>
.row-editor {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0 20px;
  max-height: 68vh;
  padding: 12px 20px;
  overflow-y: auto;
}

.row-editor :deep(.ant-form-item) {
  margin-bottom: 18px;
}

@media (width <= 760px) {
  .row-editor {
    grid-template-columns: minmax(0, 1fr);
    padding: 0;
  }
}
</style>
