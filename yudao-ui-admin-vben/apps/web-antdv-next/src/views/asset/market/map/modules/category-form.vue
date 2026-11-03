<script lang="ts" setup>
import type { VbenFormSchema } from '#/adapter/form';
import type { FoundationCategoryDataAssetApi } from '#/api/asset/categorydataasset';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createCategoryDataAsset,
  getCategoryDataAsset,
  getCategoryDataAssetList,
  updateCategoryDataAsset,
} from '#/api/asset/categorydataasset';
import { $t } from '#/locales';
import { ASSET_MODAL_FORM } from '#/views/asset/form-layout';

defineOptions({ name: 'AssetCategoryForm' });

const emit = defineEmits(['success']);
const formData = ref<FoundationCategoryDataAssetApi.CategoryDataAsset>();

const getTitle = computed(() =>
  formData.value?.id ? '修改资产类目' : '新建资产类目',
);

function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'parentId',
      label: '上级类目',
      rules: 'selectRequired',
      component: 'ApiTreeSelect',
      componentProps: {
        allowClear: false,
        api: async () => {
          const data = await getCategoryDataAssetList({});
          return [
            {
              id: 0,
              categoryName: '顶级节点',
              children: handleTree(data),
            },
          ];
        },
        labelField: 'categoryName',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级类目',
        treeDefaultExpandAll: true,
      },
    },
    {
      fieldName: 'categoryName',
      label: '类目名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入类目名称',
      },
    },
    {
      fieldName: 'categoryCode',
      label: '层级编码',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入层级编码',
      },
    },
    {
      fieldName: 'sort',
      label: '排序',
      component: 'InputNumber',
      defaultValue: 0,
      help: '数字越小越靠前',
      componentProps: {
        class: 'category-sort-stepper !w-40',
        min: 0,
        max: 9999,
        precision: 0,
        step: 1,
        placeholder: '排序值',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: CommonStatusEnum.ENABLE,
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入备注',
        rows: 3,
        maxlength: 500,
        showCount: true,
      },
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
    if (!valid) {
      return;
    }
    modalApi.lock();
    const data =
      (await formApi.getValues()) as FoundationCategoryDataAssetApi.CategoryDataAsset;
    try {
      await (formData.value?.id
        ? updateCategoryDataAsset(data)
        : createCategoryDataAsset(data));
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
    const data =
      modalApi.getData() as FoundationCategoryDataAssetApi.CategoryDataAsset;
    await formApi.resetForm();
    if (!data?.id) {
      await formApi.setValues({ parentId: data?.parentId ?? 0 });
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getCategoryDataAsset(data.id);
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

<style>
.category-sort-stepper .ant-input-number-handler-wrap {
  width: 28px;
  opacity: 1 !important;
}

.category-sort-stepper .ant-input-number-input {
  text-align: center;
}
</style>
