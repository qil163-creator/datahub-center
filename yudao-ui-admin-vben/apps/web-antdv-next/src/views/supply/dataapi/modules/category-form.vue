<script lang="ts" setup>
import type { VbenFormSchema } from '#/adapter/form';
import type { SupplyApiCategoryApi } from '#/api/supply/apicategory';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createApiCategory,
  getApiCategory,
  getApiCategoryList,
  updateApiCategory,
} from '#/api/supply/apicategory';
import { $t } from '#/locales';

import { SUPPLY_MODAL_FORM } from '../../form-layout';

defineOptions({ name: 'SupplyApiCategoryForm' });

const emit = defineEmits(['success']);
const formData = ref<SupplyApiCategoryApi.ApiCategory>();

const getTitle = computed(() =>
  formData.value?.id ? '修改服务类目' : '新建服务类目',
);

/** 新增/修改的表单 */
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
          const data = await getApiCategoryList({});
          return [{ id: 0, name: '顶级节点', children: handleTree(data) }];
        },
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择上级类目',
        treeDefaultExpandAll: true,
      },
    },
    {
      fieldName: 'name',
      label: '类目名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入数据服务类目名称',
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
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入描述',
        rows: 3,
        maxlength: 500,
        showCount: true,
      },
      formItemClass: 'col-span-2',
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
  ...SUPPLY_MODAL_FORM,
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
      (await formApi.getValues()) as SupplyApiCategoryApi.ApiCategory;
    try {
      await (formData.value?.id
        ? updateApiCategory(data)
        : createApiCategory(data));
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
    const data = modalApi.getData() as SupplyApiCategoryApi.ApiCategory;
    await formApi.resetForm();
    if (!data?.id) {
      // 新建：支持从「新增子类目」带入上级类目
      await formApi.setValues({ parentId: data?.parentId ?? 0 });
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getApiCategory(data.id);
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
/* 排序专用步进器：加减按钮常显、数字居中 */
.category-sort-stepper .ant-input-number-handler-wrap {
  width: 28px;
  opacity: 1 !important;
}

.category-sort-stepper
  .ant-input-number-handler
  .ant-input-number-handler-up-inner,
.category-sort-stepper
  .ant-input-number-handler
  .ant-input-number-handler-down-inner {
  font-size: 10px;
}

.category-sort-stepper .ant-input-number-input {
  padding-right: 32px;
  text-align: center;
}
</style>
