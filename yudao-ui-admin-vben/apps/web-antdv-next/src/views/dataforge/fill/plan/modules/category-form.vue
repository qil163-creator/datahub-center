<script lang="ts" setup>
import type { VbenFormSchema } from '#/adapter/form';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import { message } from 'antdv-next';

import { useVbenForm, z } from '#/adapter/form';
import {
  createPlanCategory,
  getPlanCategory,
  getPlanCategoryList,
  updatePlanCategory,
} from '#/api/dataforge/fill';
import { $t } from '#/locales';

interface ParentNode {
  children?: ParentNode[];
  id: string;
  name: string;
  parentId: string;
}

defineOptions({ name: 'DataforgeFillPlanCategoryForm' });

const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const editingId = ref<number | string>();
const categories = ref<DataforgeFillApi.PlanCategory[]>([]);
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
const title = computed(() =>
  editingId.value === undefined ? '新建计划分类' : '编辑计划分类',
);

const parentTree = computed<ParentNode[]>(() => {
  const excluded = new Set<string>();
  if (editingId.value !== undefined) {
    excluded.add(String(editingId.value));
    let changed = true;
    while (changed) {
      changed = false;
      for (const category of categories.value) {
        const key = String(category.id);
        if (excluded.has(String(category.parentId)) && !excluded.has(key)) {
          excluded.add(key);
          changed = true;
        }
      }
    }
  }
  return [
    {
      id: '0',
      name: '顶级分类',
      parentId: '',
      children: handleTree<ParentNode>(
        categories.value
          .filter((category) => !excluded.has(String(category.id)))
          .toSorted((left, right) => left.sort - right.sort)
          .map((category) => ({
            id: String(category.id),
            parentId: String(category.parentId),
            name: category.name,
          })),
      ),
    },
  ];
});

const schema: VbenFormSchema<DataforgeFillApi.PlanCategorySaveReq>[] = [
  {
    fieldName: 'parentId',
    label: '上级分类',
    component: 'TreeSelect',
    defaultValue: '0',
    rules: 'selectRequired',
    componentProps: () => ({
      allowClear: false,
      fieldNames: { label: 'name', value: 'id' },
      placeholder: '请选择上级分类',
      showSearch: true,
      treeData: parentTree.value,
      treeDefaultExpandAll: true,
      treeNodeFilterProp: 'name',
    }),
  },
  {
    fieldName: 'name',
    label: '分类名称',
    component: 'Input',
    rules: z
      .string()
      .trim()
      .min(1, '请输入分类名称')
      .max(100, '最多输入100个字符'),
    componentProps: {
      maxlength: 100,
      placeholder: '请输入分类名称',
    },
  },
  {
    fieldName: 'sort',
    label: '排序',
    component: 'InputNumber',
    defaultValue: 0,
    rules: z.number().int().min(0).max(9999),
    help: '数字越小越靠前',
    componentProps: { min: 0, max: 9999, precision: 0, step: 1 },
  },
];
const [Form, formApi] = useVbenForm<DataforgeFillApi.PlanCategorySaveReq>({
  layout: 'horizontal',
  showDefaultActions: false,
  commonConfig: {
    componentProps: { class: 'w-full' },
    labelWidth: 100,
  },
  schema,
});

let generation = 0;
let active = false;
let sessionIdentity = '';
const busy = ref(false);

function isCurrent(requestGeneration: number) {
  return (
    active &&
    generation === requestGeneration &&
    identity.value === sessionIdentity
  );
}

function invalidate() {
  active = false;
  ++generation;
  editingId.value = undefined;
  categories.value = [];
  busy.value = false;
}

const [Modal, modalApi] = useVbenModal<{
  id?: number | string;
  parentId?: number | string;
}>({
  async onConfirm() {
    if (busy.value || !isCurrent(generation)) return;
    const requestGeneration = generation;
    busy.value = true;
    modalApi.lock();
    try {
      const { valid } = await formApi.validate();
      if (!valid || !isCurrent(requestGeneration)) return;
      const values = await formApi.getValues();
      if (!isCurrent(requestGeneration)) return;
      const data: DataforgeFillApi.PlanCategorySaveReq = {
        id: editingId.value,
        parentId: values.parentId,
        name: values.name.trim(),
        sort: values.sort,
      };
      await (editingId.value === undefined
        ? createPlanCategory(data)
        : updatePlanCategory(data));
      if (!isCurrent(requestGeneration)) return;
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
      await modalApi.close();
    } finally {
      if (requestGeneration === generation) {
        busy.value = false;
        modalApi.unlock();
      }
    }
  },
  async onOpenChange(isOpen) {
    if (!isOpen) {
      invalidate();
      modalApi.unlock();
      return;
    }
    const requestGeneration = ++generation;
    active = true;
    sessionIdentity = identity.value;
    busy.value = true;
    editingId.value = undefined;
    categories.value = [];
    const initialData = modalApi.getData();
    modalApi.lock();
    try {
      await formApi.reset();
      if (!isCurrent(requestGeneration)) return;
      const [items, category] = await Promise.all([
        getPlanCategoryList(),
        initialData?.id === undefined
          ? Promise.resolve(undefined)
          : getPlanCategory(initialData.id),
      ]);
      if (!isCurrent(requestGeneration)) return;
      categories.value = items;
      editingId.value = category?.id;
      await formApi.setValues({
        parentId: String(category?.parentId ?? initialData?.parentId ?? '0'),
        name: category?.name ?? '',
        sort: category?.sort ?? 0,
      });
    } catch {
      if (isCurrent(requestGeneration)) await modalApi.close();
    } finally {
      if (requestGeneration === generation) {
        busy.value = false;
        modalApi.unlock();
      }
    }
  },
});

watch(identity, () => {
  if (!active) return;
  invalidate();
  modalApi.unlock();
  void modalApi.close();
});

onBeforeUnmount(invalidate);
</script>

<template>
  <Modal :title="title" class="w-[560px]">
    <Form class="mx-4" />
  </Modal>
</template>
