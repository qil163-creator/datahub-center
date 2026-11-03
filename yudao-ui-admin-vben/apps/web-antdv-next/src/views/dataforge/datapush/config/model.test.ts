import { describe, expect, it } from 'vitest';

import {
  CHANNEL_TYPE,
  getDefaultFormValues,
  MQ_TYPE,
  PASSWORD_MASK,
  toFormValues,
  toSaveReq,
} from './model';

describe('推送配置表单模型映射', () => {
  it.each([
    [undefined, 'all'],
    ['', 'all'],
    ['-1', 'all'],
    ['1', '1'],
    ['0', '0'],
  ])('确认模式 %s 回显与提交保持一致', (acks, expected) => {
    const values = toFormValues({
      channelType: CHANNEL_TYPE.MQ,
      config: { mq: { mqType: MQ_TYPE.KAFKA, kafka: { acks } } },
    });
    expect(values.kafkaAcks).toBe(expected);
    expect(toSaveReq(values).config?.mq?.kafka?.acks).toBe(expected);
    expect(getDefaultFormValues().kafkaAcks).toBe('all');
  });
  it('后端嵌套配置展平为表单值，缺省项回落默认值', () => {
    const values = toFormValues({
      id: 7,
      name: '公安接口',
      channelType: CHANNEL_TYPE.MQ,
      config: {
        mq: {
          mqType: MQ_TYPE.KAFKA,
          kafka: {
            bootstrapServers: 'kafka:9092',
            topic: 'person',
            saslPassword: PASSWORD_MASK,
          },
        },
      },
    });
    expect(values.id).toBe(7);
    expect(values.mqType).toBe(MQ_TYPE.KAFKA);
    expect(values.kafkaTopic).toBe('person');
    expect(values.kafkaSaslPassword).toBe(PASSWORD_MASK);
    expect(values.kafkaSecurityProtocol).toBe('PLAINTEXT');
    expect(values.kafkaAcks).toBe('all');
    expect(values.dbSyncExist).toBe(true);
  });

  it('切换通道类型后不提交其他通道的残留字段', () => {
    const req = toSaveReq({
      ...getDefaultFormValues(),
      name: ' 人口 HTTP ',
      channelType: CHANNEL_TYPE.HTTP,
      httpUrl: 'https://consumer.example.com/push',
      httpHeaders: [{ key: 'Authorization', value: PASSWORD_MASK }],
      httpParams: [
        { key: '  ', value: 'drop' },
        { key: 'biz', value: '1' },
      ],
      dbDatabaseId: 9,
      kafkaTopic: 'should-be-dropped',
      rabbitHost: 'mq',
    });
    expect(req.name).toBe('人口 HTTP');
    expect(req.config?.http?.url).toBe('https://consumer.example.com/push');
    expect(req.config?.http?.headers).toEqual([
      { key: 'Authorization', value: PASSWORD_MASK },
    ]);
    expect(req.config?.http?.params).toEqual([{ key: 'biz', value: '1' }]);
    expect(req.config?.db).toBeUndefined();
    expect(req.config?.mq).toBeUndefined();
  });

  it('数据库通道只提交 db，Kafka SASL 密码原样回传掩码', () => {
    const db = toSaveReq({
      ...getDefaultFormValues(),
      channelType: CHANNEL_TYPE.DATABASE,
      name: '库通道',
      dbDatabaseId: 2,
      dbTargetTableName: ' ods_person ',
      httpUrl: 'https://should-drop',
    });
    expect(db.config).toEqual({
      db: {
        databaseId: 2,
        targetTableName: 'ods_person',
        targetDrop: false,
        syncExist: true,
        changeDataSync: true,
      },
    });

    const kafka = toSaveReq({
      ...getDefaultFormValues(),
      channelType: CHANNEL_TYPE.MQ,
      mqType: MQ_TYPE.KAFKA,
      name: 'kafka',
      kafkaBootstrapServers: 'kafka:9092',
      kafkaTopic: 'data-push',
      kafkaSecurityProtocol: 'SASL_PLAINTEXT',
      kafkaSaslUsername: 'u',
      kafkaSaslPassword: PASSWORD_MASK,
      rabbitHost: 'should-drop',
    });
    expect(kafka.config?.mq?.mqType).toBe(MQ_TYPE.KAFKA);
    expect(kafka.config?.mq?.kafka?.saslPassword).toBe(PASSWORD_MASK);
    expect(kafka.config?.mq?.rabbit).toBeUndefined();
  });

  it('RabbitMQ 只提交 rabbit 节点，空密码不提交', () => {
    const rabbit = toSaveReq({
      ...getDefaultFormValues(),
      channelType: CHANNEL_TYPE.MQ,
      mqType: MQ_TYPE.RABBITMQ,
      name: 'rabbit',
      rabbitHost: '127.0.0.1',
      rabbitPort: 5672,
      rabbitExchange: 'data.push',
      rabbitRoutingKey: 'person',
      rabbitPassword: '',
      kafkaTopic: 'should-drop',
    });
    expect(rabbit.config?.mq?.kafka).toBeUndefined();
    expect(rabbit.config?.mq?.rabbit).toEqual({
      host: '127.0.0.1',
      port: 5672,
      username: undefined,
      password: undefined,
      virtualHost: '/',
      exchange: 'data.push',
      routingKey: 'person',
      useSsl: false,
      truststorePath: undefined,
      truststorePassword: undefined,
      keystorePath: undefined,
      keystorePassword: undefined,
    });
  });
});
