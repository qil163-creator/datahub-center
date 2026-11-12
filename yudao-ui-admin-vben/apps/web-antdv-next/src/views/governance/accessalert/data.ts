import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { z } from '#/adapter/form';
import { getSimpleUserList } from '#/api/system/user';

export const ACCESS_ALERT_HANDLE = 'governance:access-alert:handle';

/** 异常访问规则与状态文案（与后端 SafetyAccessAlertDO 一致）。 */
export const ALERT_RULE_OPTIONS = [
  { label: '敏感访问过多', value: 'VOLUME', unit: '次' },
  { label: '豁免明文访问过多', value: 'PLAINTEXT', unit: '次' },
  { label: '非工作时段访问', value: 'OFF_HOURS', unit: '次' },
  { label: '访问表过多', value: 'TABLE_SPREAD', unit: '张表' },
] as const;

export const ALERT_STATUS_OPTIONS = [
  { label: '待处理', value: 'OPEN', color: 'red' },
  { label: '已确认风险', value: 'CONFIRMED', color: 'orange' },
  { label: '已忽略', value: 'IGNORED', color: 'default' },
] as const;

export const HANDLE_DECISION_OPTIONS = [
  { label: '确认风险', value: 'CONFIRMED' },
  { label: '忽略（误报）', value: 'IGNORED' },
];

export function ruleLabel(code: string): string {
  return ALERT_RULE_OPTIONS.find((item) => item.value === code)?.label ?? code;
}

export function ruleUnit(code: string): string {
  return ALERT_RULE_OPTIONS.find((item) => item.value === code)?.unit ?? '';
}

export function statusView(status: string): { color: string; label: string } {
  const found = ALERT_STATUS_OPTIONS.find((item) => item.value === status);
  return found
    ? { color: found.color, label: found.label }
    : { color: 'default', label: status };
}

/** 后端时间为毫秒时间戳或字符串；只用于展示。 */
export function formatTime(value?: number | string): string {
  if (value === undefined || value === null || value === '') return '-';
  const date = new Date(typeof value === 'number' ? value : String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'ruleCode',
      label: '告警规则',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ALERT_RULE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择告警规则',
      },
    },
    {
      fieldName: 'userId',
      label: '访问用户',
      component: 'ApiSelect',
      componentProps: {
        allowClear: true,
        api: getSimpleUserList,
        labelField: 'nickname',
        valueField: 'id',
        optionFilterProp: 'label',
        placeholder: '请选择访问用户',
        showSearch: true,
      },
    },
    {
      fieldName: 'status',
      label: '处理状态',
      component: 'Select',
      defaultValue: 'OPEN',
      componentProps: {
        allowClear: true,
        options: ALERT_STATUS_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择处理状态',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(): VxeTableGridOptions<SafetySecurityApi.AccessAlert>['columns'] {
  return [
    {
      field: 'ruleCode',
      title: '告警规则',
      minWidth: 150,
      formatter: ({ cellValue }) => ruleLabel(cellValue),
    },
    {
      field: 'userName',
      title: '访问用户',
      minWidth: 150,
      formatter: ({ row }) => `${row.userName || '-'}（${row.userId}）`,
    },
    {
      field: 'windowStart',
      title: '统计窗口',
      minWidth: 280,
      formatter: ({ row }) =>
        `${formatTime(row.windowStart)} ~ ${formatTime(row.windowEnd)}`,
    },
    {
      field: 'metricValue',
      title: '统计值 / 阈值',
      minWidth: 140,
      slots: { default: 'metric' },
    },
    {
      field: 'status',
      title: '处理状态',
      minWidth: 110,
      slots: { default: 'status' },
    },
    {
      field: 'handleRemark',
      title: '处理说明',
      minWidth: 200,
      align: 'left',
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'handleTime',
      title: '处理时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      field: 'createTime',
      title: '告警时间',
      minWidth: 170,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 100,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

/** 处理告警的表单 */
export function useHandleFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'status',
      label: '处理结论',
      component: 'RadioGroup',
      defaultValue: 'CONFIRMED',
      componentProps: {
        buttonStyle: 'solid',
        optionType: 'button',
        options: HANDLE_DECISION_OPTIONS,
      },
      rules: 'required',
    },
    {
      fieldName: 'remark',
      label: '处理说明',
      component: 'TextArea',
      componentProps: {
        maxlength: 500,
        placeholder: '请填写处理说明',
        rows: 4,
        showCount: true,
      },
      rules: z.string().trim().min(1, '请填写处理说明').max(500),
    },
  ];
}
