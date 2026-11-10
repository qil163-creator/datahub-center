<script lang="ts" setup>
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createMarketCategory,
  getMarketCategory,
  getMarketCategoryList,
  updateMarketCategory,
} from '#/api/asset/marketcategory';
import { sameResourceId } from '#/utils/resource-id';

import { marketCategoryParentOptions } from '../category-tree';
import { useCategoryFormSchema } from '../data';

defineOptions({ name: 'AssetMarketCategoryForm' });
const emit = defineEmits<{ success: [] }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const editing = ref<AssetMarketCategoryApi.MarketCategory>();
const parents = ref<AssetMarketCategoryApi.MarketCategory[]>([]);
const ready = ref(false);
const saving = ref(false);
const loadError = ref('');
let revision = 0;

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-1',
    labelWidth: 130,
  },
  wrapperClass: 'grid-cols-1 gap-x-6 sm:grid-cols-2',
  layout: 'horizontal',
  schema: useCategoryFormSchema(() =>
    marketCategoryParentOptions(parents.value, editing.value?.id),
  ),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  contentClass: 'px-6 pb-0 pt-5 max-sm:px-4',
  closeOnClickModal: false,
  onBeforeClose: () => !saving.value,
  async onConfirm() {
    if (
      !ready.value ||
      saving.value ||
      !hasAccessByCodes([
        editing.value
          ? 'asset:market-category:update'
          : 'asset:market-category:create',
      ])
    )
      return;
    const current = revision;
    saving.value = true;
    modalApi.lock();
    try {
      const { valid } = await formApi.validate();
      if (!valid || current !== revision) return;
      const values =
        (await formApi.getValues()) as AssetMarketCategoryApi.CategorySave;
      if (current !== revision) return;
      const data: AssetMarketCategoryApi.CategorySave = {
        id: editing.value?.id,
        parentId: values.parentId ?? 0,
        name: values.name.trim(),
        description: values.description ?? '',
        status: values.status,
        sort: values.sort,
      };
      await (editing.value
        ? updateMarketCategory(data)
        : createMarketCategory(data));
      if (current !== revision) return;
      saving.value = false;
      modalApi.unlock();
      await modalApi.close();
      emit('success');
      message.success('保存成功');
    } catch {
      // 请求层已提示，保留本次修改并解除提交锁供重试。
    } finally {
      if (current === revision) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
  async onOpenChange(open: boolean) {
    const current = ++revision;
    ready.value = false;
    editing.value = undefined;
    parents.value = [];
    loadError.value = '';
    saving.value = false;
    modalApi.unlock();
    await formApi.resetForm();
    if (!open || current !== revision) return;
    const data = modalApi.getData() as
      | undefined
      | {
          id?: AssetMarketCategoryApi.Id;
          parentId?: AssetMarketCategoryApi.Id;
        };
    modalApi.lock();
    try {
      const [rows, detail] = await Promise.all([
        getMarketCategoryList(),
        data?.id === undefined ? undefined : getMarketCategory(data.id),
      ]);
      if (current !== revision) return;
      if (data?.id !== undefined && !sameResourceId(detail?.id, data.id))
        throw new Error('CATEGORY_IDENTITY');
      parents.value = rows;
      editing.value = detail;
      await formApi.setValues(
        detail ?? {
          parentId: data?.parentId ?? 0,
          name: '',
          status: 0,
          sort: 0,
          description: '',
        },
      );
      if (current === revision) ready.value = true;
    } catch {
      if (current === revision)
        loadError.value = '类目信息加载失败，请关闭后重试。';
    } finally {
      if (current === revision) modalApi.unlock();
    }
  },
});

function invalidate() {
  ++revision;
  ready.value = false;
  parents.value = [];
  editing.value = undefined;
  saving.value = false;
  modalApi.unlock();
  void modalApi.close();
}
watch(scope, invalidate, { flush: 'sync' });
onDeactivated(invalidate);
onBeforeUnmount(() => {
  ++revision;
  parents.value = [];
  ready.value = false;
});
</script>

<template>
  <Modal
    data-testid="market-category-form-modal"
    :title="editing ? '修改集市类目' : '新增集市类目'"
    class="w-[800px] max-w-[calc(100vw-32px)]"
    :confirm-disabled="!ready || !!loadError"
  >
<Form />
  </Modal>
</template>
