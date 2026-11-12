import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetyRuleApi } from '#/api/governance/rule';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';

import { z } from '#/adapter/form';
import { getEnabledKeyRefList } from '#/api/governance/security';

import MaskRange from './components/mask-range.vue';
import ReplaceItemTable from './components/replace-item-table.vue';

/** 使用替换明细码表（多行匹配值-替换值）的脱敏方式 */
export const REPLACE_ITEM_METHODS = ['REPLACE'];

/** 哈希/摘要算法选项 */
const DIGEST_ALGORITHM_OPTIONS = [
  'MD5',
  'SHA',
  'SHA256',
  'SHA384',
  'SHA512',
].map((value) => ({ label: value, value }));
const HASH_ALGORITHM_OPTIONS = [
  ...DIGEST_ALGORITHM_OPTIONS,
  { label: 'SM3（国密）', value: 'SM3' },
];

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    // 规则来源固定为自定义，内置规则仅由初始化脚本产生
    {
      fieldName: 'sourceType',
      component: 'Input',
      defaultValue: 'CUSTOM',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'ruleName',
      label: '规则名称',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入规则名称',
      },
    },
    {
      fieldName: 'methodCode',
      label: '脱敏方式',
      rules: 'required',
      component: 'Select',
      componentProps: {
        options: getDictOptions(DICT_TYPE.METHOD_CODE, 'string'),
        placeholder: '请选择脱敏方式',
      },
      // 算法类型不允许跨版本变更，编辑时禁止修改
      dependencies: {
        triggerFields: ['id'],
        disabled: (values) => !!values.id,
      },
    },
    // ==================== 自定义掩码 MASK ====================
    // 将起始~截止范围内的字符替换成掩码字符，下标从1开始，截止必须大于起始
    {
      fieldName: 'maskRange',
      label: '掩码范围',
      component: MaskRange as any,
      defaultValue: {},
      formItemClass: 'col-span-2',
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'MASK',
      },
      description: '从起始位到截止位的字符替换为掩码字符，下标从 1 开始',
    },
    {
      fieldName: 'maskChar',
      label: '掩码字符',
      component: 'Input',
      componentProps: {
        maxlength: 1,
        placeholder: '留空默认 *',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'MASK',
      },
    },
    // ==================== 随机偏移 RANDOM_OFFSET ====================
    // 数字按偏移数加减；文本在末尾追加正偏移数。最大偏移位数为3时，偏移数在1~999
    {
      fieldName: 'maxOffsetDigits',
      label: '最大偏移位数',
      rules: z
        .number({ message: '请输入最大偏移位数' })
        .min(1, '最大偏移位数只能是不为 0 的正整数'),
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 1,
        precision: 0,
        placeholder: '随机偏移的最大偏移量，不能为 0',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'RANDOM_OFFSET',
      },
    },
    {
      fieldName: 'allowNegative',
      label: '负偏移',
      component: 'Switch',
      defaultValue: false,
      componentProps: {
        // 覆盖公共的 w-full，避免开关被拉满整行
        class: 'w-auto',
        checkedChildren: '是',
        unCheckedChildren: '否',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'RANDOM_OFFSET',
      },
      description: '开启后偏移数可为负数（不含 0）',
    },
    // ==================== 内容替换 REPLACE ====================
    {
      fieldName: 'matchMode',
      label: '匹配方式',
      rules: 'required',
      component: 'RadioGroup',
      defaultValue: 'EXACT',
      componentProps: {
        options: [
          { label: '完全匹配', value: 'EXACT' },
          { label: '模糊匹配', value: 'FUZZY' },
        ],
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => REPLACE_ITEM_METHODS.includes(values.methodCode),
      },
      description:
        '完全匹配：字段值与匹配值完全相等时替换；模糊匹配：字段值包含匹配值时替换',
    },
    {
      fieldName: 'replaceItems',
      label: '匹配内容',
      component: ReplaceItemTable as any,
      defaultValue: [],
      formItemClass: 'col-span-2',
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => REPLACE_ITEM_METHODS.includes(values.methodCode),
      },
    },
    // ==================== 随机替换 RANDOM_REPLACE ====================
    // 将字符串内容随机替换为指定内容，替换位置随机
    {
      fieldName: 'replaceChar',
      label: '替换字符',
      component: 'Input',
      componentProps: {
        placeholder: '指定替换的内容，留空默认 *',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'RANDOM_REPLACE',
      },
    },
    {
      fieldName: 'replaceCount',
      label: '替换次数',
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 1,
        precision: 0,
        placeholder: '默认 1 次，不允许为 0',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'RANDOM_REPLACE',
      },
    },
    // ==================== 降低精度 REDUCE_PRECISION ====================
    {
      fieldName: 'precision',
      label: '降低精度',
      rules: z
        .number({ message: '请输入降低精度的位数' })
        .min(1, '降低精度的位数最少 1 位'),
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 1,
        precision: 0,
        placeholder: '降低精度的位数，最少 1 位',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'REDUCE_PRECISION',
      },
      description: '数字截断小数位；日期时间将秒、分等低位精度置零',
    },
    // ==================== 内容截断 TRUNCATE ====================
    {
      fieldName: 'truncateLength',
      label: '截断长度',
      rules: z
        .number({ message: '请输入截断长度' })
        .min(1, '截断长度最少 1 位'),
      component: 'InputNumber',
      componentProps: {
        class: 'w-full',
        min: 1,
        precision: 0,
        placeholder: '超出指定长度的部分截断，下标从 1 开始',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'TRUNCATE',
      },
    },
    // ==================== 正则替换 REGEX ====================
    {
      fieldName: 'pattern',
      label: '正则表达式',
      rules: 'required',
      component: 'Input',
      componentProps: {
        placeholder: '请输入正则表达式',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'REGEX',
      },
    },
    {
      fieldName: 'replacement',
      label: '替换内容',
      component: 'Input',
      componentProps: {
        placeholder: '匹配部分替换为该内容，留空即删除匹配部分',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'REGEX',
      },
    },
    // ==================== 哈希 HASH ====================
    {
      fieldName: 'hashAlgorithm',
      label: '摘要算法',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: {
        options: HASH_ALGORITHM_OPTIONS,
        placeholder: '请选择摘要算法',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'HASH',
      },
    },
    // ==================== 加解密 ENCRYPT ====================
    {
      fieldName: 'encryptType',
      label: '加解密规则',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: {
        options: [
          { label: '字段加密（密钥引用，推荐）', value: 'KEY_REF' },
          { label: 'Base64', value: 'BASE64' },
          { label: 'Digest', value: 'DIGEST' },
          { label: '国密', value: 'SM' },
        ],
        placeholder: '请选择加密规则',
      },
      dependencies: {
        triggerFields: ['methodCode'],
        show: (values) => values.methodCode === 'ENCRYPT',
      },
      description:
        '字段加密使用密钥中心派生的 SM4 密钥，规则中不保存密钥；Base64 可逆；Digest 与 SM3 为摘要；SM2 只需公钥。对称密钥不再允许写在规则里',
    },
    {
      fieldName: 'digestAlgorithm',
      label: '摘要算法',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: {
        options: DIGEST_ALGORITHM_OPTIONS,
        placeholder: '请选择摘要算法',
      },
      dependencies: {
        triggerFields: ['methodCode', 'encryptType'],
        show: (values) =>
          values.methodCode === 'ENCRYPT' && values.encryptType === 'DIGEST',
      },
    },
    {
      fieldName: 'smAlgorithm',
      label: '国密算法',
      rules: 'selectRequired',
      component: 'Select',
      componentProps: {
        options: [
          { label: 'SM2（非对称，公钥加密）', value: 'SM2' },
          { label: 'SM3（摘要，仅加密）', value: 'SM3' },
        ],
        placeholder: '请选择国密算法',
      },
      dependencies: {
        triggerFields: ['methodCode', 'encryptType'],
        show: (values) =>
          values.methodCode === 'ENCRYPT' && values.encryptType === 'SM',
      },
    },
    {
      fieldName: 'publicKey',
      label: '公钥',
      rules: 'required',
      component: 'TextArea',
      componentProps: {
        placeholder: '请输入 SM2 公钥（加密使用）',
        rows: 2,
      },
      dependencies: {
        triggerFields: ['methodCode', 'encryptType', 'smAlgorithm'],
        show: (values) =>
          values.methodCode === 'ENCRYPT' &&
          values.encryptType === 'SM' &&
          values.smAlgorithm === 'SM2',
      },
    },
    {
      fieldName: 'keyCode',
      label: '密钥引用',
      rules: 'selectRequired',
      component: 'ApiSelect',
      componentProps: {
        api: getEnabledKeyRefList,
        labelField: 'keyName',
        valueField: 'keyCode',
        placeholder: '请选择密钥引用（在“密钥管理”中创建）',
      },
      dependencies: {
        triggerFields: ['methodCode', 'encryptType'],
        show: (values) =>
          values.methodCode === 'ENCRYPT' && values.encryptType === 'KEY_REF',
      },
    },
    // ==================== 通用字段 ====================
    {
      fieldName: 'status',
      label: '状态',
      rules: z.number().default(CommonStatusEnum.ENABLE),
      component: 'RadioGroup',
      componentProps: {
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        buttonStyle: 'solid',
        optionType: 'button',
      },
    },
    {
      fieldName: 'sort',
      label: '显示顺序',
      rules: z.number({ message: '请输入显示顺序' }).min(0),
      component: 'InputNumber',
      defaultValue: 0,
      componentProps: {
        class: 'w-full',
        min: 0,
        precision: 0,
        placeholder: '请输入显示顺序',
      },
    },
    {
      fieldName: 'description',
      label: '规则描述',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: {
        placeholder: '请输入规则描述',
        maxlength: 500,
        showCount: true,
        autoSize: { minRows: 4, maxRows: 8 },
      },
    },
  ];
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'ruleName',
      label: '规则名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入规则名称',
      },
    },
    {
      fieldName: 'sourceType',
      label: '规则来源',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.SOURCE_TYPE, 'string'),
        placeholder: '请选择规则来源',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SafetyRuleApi.Rule>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 100,
    },
    {
      field: 'ruleName',
      title: '规则名称',
      minWidth: 120,
    },
    {
      field: 'methodCode',
      title: '脱敏方式',
      minWidth: 120,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.METHOD_CODE },
      },
    },
    {
      field: 'sourceType',
      title: '规则来源',
      minWidth: 100,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.SOURCE_TYPE },
      },
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      cellRender: {
        name: 'CellDict',
        props: { type: DICT_TYPE.COMMON_STATUS },
      },
    },
    {
      field: 'sort',
      title: '显示顺序',
      minWidth: 100,
    },
    {
      field: 'description',
      title: '规则描述',
      minWidth: 160,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 180,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
