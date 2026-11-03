import type { ConfigFormValues } from '../config/model';
import type { TaskFormValues } from './data';

import type { VbenFormSchema } from '#/adapter/form';
import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';
import type { DataforgeWizardStep } from '#/views/dataforge/common/form-wizard';

import {
  createHiddenIdField,
  createSectionDivider,
} from '../../common/form-section';
import { useFormSchema as useConfigFormSchema } from '../config/data';
import {
  getDefaultFormValues as getDefaultConfigValues,
  toFormValues as toConfigFormValues,
  toSaveReq as toConfigSaveReq,
} from '../config/model';
import {
  getDefaultFormValues as getDefaultTaskValues,
  toFormValues as toTaskFormValues,
  toSaveReq as toTaskSaveReq,
  useFormSchema as useTaskFormSchema,
} from './data';

export interface UnifiedTaskFormValues
  extends
    Omit<ConfigFormValues, 'description' | 'id' | 'name' | 'orgId'>,
    TaskFormValues {}

const CONFIG_BASIC_FIELDS = new Set([
  'basicDivider',
  'description',
  'id',
  'name',
  'orgId',
]);

function useReceiverFormSchema(): VbenFormSchema[] {
  return [
    createSectionDivider('channelDivider', '接收端配置'),
    ...useConfigFormSchema()
      .filter((field) => !CONFIG_BASIC_FIELDS.has(field.fieldName))
      .map(
        (field): VbenFormSchema =>
          field.fieldName === 'channelType'
            ? {
                ...field,
                dependencies: {
                  triggerFields: ['pushConfigId'],
                  resolve: ({ values }) => ({
                    disabled: !!values.pushConfigId,
                  }),
                },
              }
            : field,
      ),
  ];
}

export const RECEIVER_FIELDS = useReceiverFormSchema().map(
  (field) => field.fieldName,
);

export const UNIFIED_TASK_STEPS: DataforgeWizardStep[] = [
  {
    title: '基本信息',
    description: '任务名称与归属',
    hint: '填写任务名称、所属单位和用途说明',
    fields: ['basicDivider', 'name', 'categoryId', 'orgId', 'description'],
  },
  {
    title: '接收端配置',
    description: '数据推送到哪里',
    hint: '为当前任务填写 HTTP、数据库或消息队列的连接与认证参数',
    fields: RECEIVER_FIELDS,
  },
  {
    title: '数据来源',
    description: '选择要推送的数据',
    hint: '选择源数据源，通过数据表或自定义 SQL 确定推送范围',
    fields: [
      'sourceDivider',
      'sourceDatabaseId',
      'pushType',
      'objectName',
      'tableName',
      'sqlText',
    ],
  },
  {
    title: '推送与调度',
    description: '如何推送、何时运行',
    hint: '选择全量或增量推送，配置批次大小和任务运行周期',
    fields: [
      'modeDivider',
      'pushMode',
      'batchSize',
      'incrementColumn',
      'initStartVal',
      'taskType',
      'cron',
    ],
  },
  {
    title: '确认提交',
    description: '核对整条推送链路',
    hint: '核对接收端、数据来源、推送方式和调度配置，确认后保存任务',
    fields: [],
  },
];

export function getDefaultUnifiedFormValues(): UnifiedTaskFormValues {
  return { ...getDefaultConfigValues(), ...getDefaultTaskValues() };
}

export function toUnifiedFormValues(
  task: DataforgeDataPushTaskApi.Task,
  config: DataforgeDataPushConfigApi.Config,
): UnifiedTaskFormValues {
  return { ...toConfigFormValues(config), ...toTaskFormValues(task) };
}

/** 任务与接收端一次提交，沿用现有转换的通道裁剪和密钥掩码规则。 */
export function toUnifiedSaveReq(
  values: UnifiedTaskFormValues,
): DataforgeDataPushTaskApi.TaskSaveReqVO {
  const { pushConfigId: _pushConfigId, ...task } = toTaskSaveReq(values);
  return {
    ...task,
    pushConfig: toConfigSaveReq({
      ...values,
      id: values.id === undefined ? undefined : values.pushConfigId,
    }),
  };
}

/** 组合已有领域 Schema，接收端不再重复填写名称、单位和说明。 */
export function useUnifiedFormSchema(): VbenFormSchema[] {
  const taskSchema = useTaskFormSchema().filter(
    (field) => field.fieldName !== 'pushConfigId',
  );
  const orderedFields = UNIFIED_TASK_STEPS.flatMap((step) => step.fields);
  return [
    ...taskSchema,
    createHiddenIdField('pushConfigId'),
    ...useReceiverFormSchema(),
  ].toSorted(
    (left, right) =>
      orderedFields.indexOf(left.fieldName) -
      orderedFields.indexOf(right.fieldName),
  );
}
