import type { StandardCategory } from './data';

import type { VbenFormSchema } from '#/adapter/form';

import { markRaw } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';
import { getDoc, getDocPage } from '#/api/governance/doc';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import { createDocumentBindingValidator } from '../standardelement/modules/document-binding';
import { standardCategoryTree } from './data';
import StandardNumberInput from './standard-number-input.vue';

export function createStandardDefinitionSchema(options: {
  categoryApi: () => Promise<StandardCategory[]>;
  englishField: 'engName' | 'type';
}): VbenFormSchema[] {
  const validateDocumentBinding = createDocumentBindingValidator(getDoc);
  const contactRequests = useObjectRequest();
  const documentRequests = useObjectRequest();
  return [
    {
      fieldName: 'categoryId',
      label: '标准数据元类目',
      rules: 'required',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () =>
          standardCategoryTree(await options.categoryApi())[0]?.children ?? [],
        labelField: 'title',
        valueField: 'id',
        childrenField: 'children',
        placeholder: '请选择标准数据元类目',
        treeDefaultExpandAll: true,
        showSearch: true,
        treeNodeFilterProp: 'label',
      },
    },
    {
      fieldName: 'columnType',
      label: '字段类型',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: [
          'TINYINT',
          'INTEGER',
          'BIGINT',
          'DECIMAL',
          'NUMERIC',
          'FLOAT',
          'DOUBLE',
          'CHAR',
          'VARCHAR',
          'TEXT',
          'DATE',
          'TIMESTAMP',
          'DATETIME',
          'BOOLEAN',
        ].map((value) => ({ label: value, value })),
        placeholder: '请选择字段类型',
        showSearch: true,
      },
    },
    {
      fieldName: 'name',
      label: '中文名称',
      component: 'Input',
      rules: z
        .string()
        .min(1, '请输入中文名称')
        .max(50, '最多50个字符')
        .regex(
          /^[\p{Script=Han}a-zA-Z0-9_]+$/u,
          '仅支持中文、英文、数字和下划线',
        ),
      componentProps: { maxlength: 50, placeholder: '请输入中文名称' },
    },
    {
      fieldName: options.englishField,
      label: '英文名称',
      component: 'Input',
      rules: z
        .string()
        .min(1, '请输入英文名称')
        .max(50, '最多50个字符')
        .regex(/^[A-Za-z_]+$/, '仅支持英文字母和下划线'),
      componentProps: { maxlength: 50, placeholder: '请输入英文名称' },
    },
    {
      fieldName: 'dataLength',
      label: '数据长度',
      component: markRaw(StandardNumberInput),
      modelPropName: 'modelValue',
      componentProps: {
        min: 1,
        max: 4000,
        precision: 0,
        placeholder: '请输入数据长度',
      },
    },
    {
      fieldName: 'dataScale',
      label: '小数位数',
      component: markRaw(StandardNumberInput),
      modelPropName: 'modelValue',
      componentProps: {
        min: 0,
        max: 18,
        precision: 0,
        placeholder: '请输入小数位数',
      },
    },
    {
      fieldName: 'standardType',
      label: '标准类型',
      component: 'Select',
      rules: 'required',
      componentProps: () => ({
        options: getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
        placeholder: '请选择类型',
      }),
    },
    {
      fieldName: 'documentId',
      label: '标准登记',
      component: 'ApiSelect',
      rules: 'required',
      dependencies: {
        triggerFields: ['standardType', 'documentId'],
        resolve: async ({ values, actions }) => {
          const current = documentRequests.begin();
          await validateDocumentBinding(
            { stdType: values.standardType, documentId: values.documentId },
            () => current() && actions.setFieldValue('documentId', undefined),
          );
          return {
            disabled: !values.standardType,
            componentProps: {
              api: async () => {
                if (!values.standardType) return [];
                const result = await getDocPage({
                  pageNo: 1,
                  pageSize: 200,
                  stdType: values.standardType,
                });
                return result.list ?? [];
              },
              alwaysLoad: true,
              labelField: 'nameCn',
              valueField: 'id',
              params: { stdType: values.standardType || '' },
              placeholder: '请选择标准登记',
              showSearch: true,
              optionFilterProp: 'label',
            },
          };
        },
      },
    },
    {
      fieldName: 'personCharge',
      label: '负责人',
      component: 'ApiSelect',
      rules: 'required',
      componentProps: {
        api: getSimpleUserList,
        labelField: 'nickname',
        valueField: 'id',
        placeholder: '请选择负责人',
        showSearch: true,
      },
    },
    {
      fieldName: 'contactNumber',
      label: '联系电话',
      component: 'Input',
      componentProps: { disabled: true, placeholder: '请输入联系电话' },
      dependencies: {
        triggerFields: ['personCharge'],
        resolve: async ({ values, actions }) => {
          const current = contactRequests.begin();
          if (!values.personCharge) return { disabled: true };
          const users = await getSimpleUserList();
          const user = users.find(
            (item) => String(item.id) === String(values.personCharge),
          );
          if (current() && user)
            await actions.setFieldValue('contactNumber', user.mobile || '');
          return { disabled: true };
        },
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'RadioGroup',
      defaultValue: 0,
      componentProps: {
        options: [
          { label: '启用', value: 0 },
          { label: '禁用', value: 1 },
        ],
      },
      rules: z.number().optional(),
    },
    {
      fieldName: 'description',
      label: '描述',
      component: 'TextArea',
      formItemClass: 'col-span-2 standard-description-field',
      componentProps: {
        maxlength: 256,
        showCount: true,
        rows: 3,
        placeholder: '请输入描述',
      },
    },
  ];
}
