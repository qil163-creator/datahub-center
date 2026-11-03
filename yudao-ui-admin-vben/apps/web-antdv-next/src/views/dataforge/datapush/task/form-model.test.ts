import { describe, expect, it, vi } from 'vitest';

import { CHANNEL_TYPE, MQ_TYPE, PASSWORD_MASK } from '../config/model';
import { PUSH_MODE, PUSH_TYPE, TASK_TYPE } from './data';
import {
  getDefaultUnifiedFormValues,
  RECEIVER_FIELDS,
  toUnifiedFormValues,
  toUnifiedSaveReq,
  UNIFIED_TASK_STEPS,
  useUnifiedFormSchema,
} from './form-model';

vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/components/cron-tab', () => ({ CronTab: {} }));
vi.mock('./modules/increment-column-select.vue', () => ({ default: {} }));
vi.mock('./modules/source-table-select.vue', () => ({ default: {} }));
vi.mock('../../common/key-value-list.vue', () => ({ default: {} }));

describe('统一推送任务表单模型', () => {
  it('新建任务合并默认值，不沿用已有接收端', () => {
    const values = getDefaultUnifiedFormValues();
    expect(values).toMatchObject({
      channelType: CHANNEL_TYPE.HTTP,
      pushType: PUSH_TYPE.TABLE,
      pushMode: PUSH_MODE.FULL,
      taskType: TASK_TYPE.ONCE,
      batchSize: 100,
      kafkaAcks: 'all',
    });
    expect(values.id).toBeUndefined();
    expect(values.pushConfigId).toBeUndefined();
    expect(toUnifiedSaveReq(values)).not.toHaveProperty('pushConfigId');
  });

  it('编辑回填保留任务基础信息、无损 ID 和原密钥掩码', () => {
    const taskId = '9007199254740993';
    const configId = '9007199254740995';
    const values = toUnifiedFormValues(
      {
        id: taskId,
        categoryId: '9007199254740991',
        pushConfigId: configId,
        name: '人口推送任务',
        description: '任务说明',
        orgId: '9007199254740997',
        sourceDatabaseId: '9007199254740999',
      },
      {
        id: configId,
        name: '旧接收端名称',
        description: '旧接收端说明',
        orgId: 1,
        channelType: CHANNEL_TYPE.HTTP,
        config: {
          http: {
            url: 'https://consumer.example.com/push',
            headers: [{ key: 'Authorization', value: PASSWORD_MASK }],
          },
        },
      },
    );
    const request = toUnifiedSaveReq(values);
    expect(request).toMatchObject({
      id: taskId,
      categoryId: '9007199254740991',
      name: '人口推送任务',
      sourceDatabaseId: '9007199254740999',
      pushConfig: {
        id: configId,
        name: '人口推送任务',
        description: '任务说明',
        orgId: '9007199254740997',
        config: {
          http: {
            headers: [{ key: 'Authorization', value: PASSWORD_MASK }],
          },
        },
      },
    });
    expect(values.id).toBe(taskId);
    expect(values.categoryId).toBe('9007199254740991');
    expect(toUnifiedSaveReq({ ...values, categoryId: undefined }).categoryId).toBeNull();
    expect(values.pushConfigId).toBe(configId);
    expect(request).not.toHaveProperty('pushConfigId');
  });

  it('新建忽略残留接收端 ID，按通道与来源裁剪无关配置', () => {
    const request = toUnifiedSaveReq({
      ...getDefaultUnifiedFormValues(),
      pushConfigId: '9007199254740995',
      name: ' 新建推送 ',
      channelType: CHANNEL_TYPE.DATABASE,
      dbDatabaseId: '9007199254740997',
      dbTargetTableName: ' ods_person ',
      httpUrl: 'https://should-drop.example.com',
      kafkaTopic: 'should-drop',
      pushType: PUSH_TYPE.SQL,
      sqlText: ' SELECT id FROM person ',
      tableName: 'should_drop',
      incrementColumn: 'should_drop',
      initStartVal: '99',
      cron: '0 0 2 * * ?',
    });
    expect(request.name).toBe('新建推送');
    expect(request.sqlText).toBe('SELECT id FROM person');
    expect(request.tableName).toBeUndefined();
    expect(request.incrementColumn).toBeUndefined();
    expect(request.initStartVal).toBeUndefined();
    expect(request.cron).toBeUndefined();
    expect(request.pushConfig?.id).toBeUndefined();
    expect(request.pushConfig?.config).toEqual({
      db: {
        databaseId: '9007199254740997',
        targetTableName: 'ods_person',
        targetDrop: false,
        syncExist: true,
        changeDataSync: true,
      },
    });
    expect(request).not.toHaveProperty('pushConfigId');
  });

  it('消息队列复用 RabbitMQ 通道转换，增量水位与调度原样保留', () => {
    const request = toUnifiedSaveReq({
      ...getDefaultUnifiedFormValues(),
      channelType: CHANNEL_TYPE.MQ,
      mqType: MQ_TYPE.RABBITMQ,
      rabbitHost: 'mq.example.com',
      rabbitPassword: PASSWORD_MASK,
      rabbitExchange: 'data.push',
      rabbitRoutingKey: 'person',
      kafkaTopic: 'should-drop',
      pushMode: PUSH_MODE.INCREMENT,
      incrementColumn: 'id',
      initStartVal: '9007199254740993',
      taskType: TASK_TYPE.CRON,
      cron: ' 0 0 2 * * ? ',
    });
    expect(request).toMatchObject({
      incrementColumn: 'id',
      initStartVal: '9007199254740993',
      cron: '0 0 2 * * ?',
      pushConfig: {
        config: { mq: { rabbit: { password: PASSWORD_MASK } } },
      },
    });
    expect(request.pushConfig?.config?.mq?.kafka).toBeUndefined();
  });

  it('五步向导完整承载接收端字段，基础信息仅出现一次', () => {
    const schema = useUnifiedFormSchema();
    const fields = schema.map((field) => field.fieldName);
    expect(UNIFIED_TASK_STEPS).toHaveLength(5);
    expect(
      schema.find((field) => field.fieldName === 'pushConfigId')?.component,
    ).toBe('Input');
    expect(new Set(fields).size).toBe(fields.length);
    expect(UNIFIED_TASK_STEPS[1]?.fields).toEqual(RECEIVER_FIELDS);
    const wizardFields = UNIFIED_TASK_STEPS.flatMap((step) => step.fields);
    expect(
      fields.filter((field) => field !== 'id' && field !== 'pushConfigId'),
    ).toEqual(wizardFields);
    expect(fields.indexOf('channelType')).toBeLessThan(
      fields.indexOf('sourceDatabaseId'),
    );
    expect(RECEIVER_FIELDS).toEqual(
      expect.arrayContaining([
        'httpUrl',
        'dbDatabaseId',
        'mqType',
        'rabbitPassword',
      ]),
    );
  });

  it('通道编辑禁改依赖接收端 ID，任务 ID 不会锁定新接收端', () => {
    const field = useUnifiedFormSchema().find(
      (item) => item.fieldName === 'channelType',
    );
    const resolver = field?.dependencies?.resolve;
    expect(field?.dependencies?.triggerFields).toEqual(['pushConfigId']);
    expect(resolver?.({ values: { id: '9007199254740993' } } as never)).toEqual(
      {
        disabled: false,
      },
    );
    expect(
      resolver?.({ values: { pushConfigId: '9007199254740995' } } as never),
    ).toEqual({ disabled: true });
  });
});
