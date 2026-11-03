<script lang="ts" setup>
import type { VbenFormSchema } from '#/adapter/form';
import type { AssetCategoryTagApi } from '#/api/asset/categorytag';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createCategoryTag,
  getCategoryTag,
  getCategoryTagList,
  updateCategoryTag,
} from '#/api/asset/categorytag';

import { ASSET_MODAL_FORM } from '../../form-layout';

defineOptions({ name: 'AssetTagCategoryForm' });

const emit = defineEmits<{ (e: 'success'): void }>();
const formData = ref<AssetCategoryTagApi.CategoryTag>();
const getTitle = computed(() =>
  formData.value?.id ? '修改标签类目' : '新增标签类目',
);

function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: { triggerFields: [''], show: () => false },
    },
    {
      fieldName: 'parentId',
      label: '上级类目',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: true,
        api: async () => {
          const data = await getCategoryTagList();
          (data as any[]).unshift({ id: 0, categoryName: '顶级标签类目' });
          return handleTree(data);
        },
        labelField: 'categoryName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级类目',
        treeDefaultExpandAll: true,
      },
      rules: 'selectRequired',
    },
    {
      fieldName: 'categoryName',
      label: '类目名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '如：水利行业类目 / 基础监测',
        maxlength: 128,
      },
    },
    {
      fieldName: 'categoryCode',
      label: '类目编码',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '平台内唯一，如 WATER_BASE',
        maxlength: 128,
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      rules: 'required',
      component: 'InputNumber',
      componentProps: { min: 0, class: 'w-full' },
      defaultValue: 0,
    },
    {
      fieldName: 'status',
      label: '状态',
      rules: 'required',
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
      defaultValue: 0,
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      componentProps: { placeholder: '请输入备注', rows: 3 },
      formItemClass: 'col-span-2',
    },
  ];
}

const [Form, formApi] = useVbenForm({
  ...ASSET_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;
    modalApi.lock();
    const data = (await formApi.getValues()) as AssetCategoryTagApi.CategoryTag;
    try {
      await (formData.value?.id
        ? updateCategoryTag(data)
        : createCategoryTag(data));
      await modalApi.close();
      emit('success');
      message.success('保存成功');
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
    const data = modalApi.getData() as
      | AssetCategoryTagApi.CategoryTag
      | undefined;
    await formApi.resetForm();
    if (!data?.id) {
      await formApi.setValues({ parentId: data?.parentId ?? 0 });
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getCategoryTag(data.id);
      await formApi.setValues(formData.value);
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
