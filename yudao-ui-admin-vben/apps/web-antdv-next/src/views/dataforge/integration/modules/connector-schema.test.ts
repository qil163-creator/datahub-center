import { describe, expect, it } from 'vitest';

import {
  connectorDefaults,
  connectorFieldVisible,
  connectorSchemas,
  getConnectorSchema,
  tdengineLegacyConfigKeys,
  validateConnectorConfig,
} from './connector-schema';

describe('qData连接器配置边界', () => {
  it.each(Object.keys(connectorSchemas))(
    '%s 有可编辑字段、默认值和明确的执行状态',
    (code) => {
      const schema = connectorSchemas[code]!;
      const config = {
        ...connectorDefaults(code),
        databaseId: '9007199254740993',
        ...(['read-rocketmq', 'read-tdengine', 'write-rocketmq'].includes(code) ? { groupId: 'events-group' } : { tableName: 'source_table' }),
        path: 'data/rows.csv',
        resourceBindingId: 'task-fixture',
        topic: 'events',
        keyPattern: 'sensor:*',
        // Redis inputs have no default data type; it must be chosen.
        ...(code === 'read-redis' ? { dataType: 'string' } : {}),
        // Hive output follows the reference: no write mode is preselected, 追加 must be chosen.
        ...(code === 'write-hive' ? { writeModeType: 2 } : {}),
        // MongoDB output likewise starts without a write mode; 追加 is the execution-protocol value 0.
        ...(code === 'write-mongodb' ? { writeModeType: 0 } : {}),
        apiUrl: 'https://example.test/service?wsdl',
        operationName: 'query',
        columns: [{ name: 'id', type: 'STRING', index: 0 }],
        ...(['read-ftp', 'write-ftp'].includes(code) ? { credentialRef: 'ftp.fixture' } : {}),
      };
      expect(schema.fields.length).toBeGreaterThan(0);
      expect(['PENDING_ADAPTER', 'MANAGED_DATAX', 'CREDENTIAL_ADAPTER']).toContain(schema.execution);
      expect(validateConnectorConfig(code, config)).toEqual([]);
      expect(config.databaseId).toBe('9007199254740993');
    },
  );

  it('离线TDengine离线表快照与实时TMQ使用独立字段契约', () => {
    const config = { ...connectorDefaults('read-tdengine', 'OFFLINE'), databaseId: '9007199254740993', resourceBindingId: 'snapshot', tableName: 'meters', columns: [{ name: 'id', type: 'LONG' }] };
    expect(config).toMatchObject({ readMode: 'TABLE' });
    expect(config).not.toHaveProperty('topic');
    expect(getConnectorSchema('read-tdengine', 'OFFLINE')?.fields.map((field) => field.key)).toEqual(['resourceBindingId', 'tableName', 'readMode']);
    expect(validateConnectorConfig('read-tdengine', config, 'OFFLINE')).toEqual([]);
    expect(tdengineLegacyConfigKeys({ ...config, topic: 'events' }, 'OFFLINE')).toEqual(['topic']);
    expect(validateConnectorConfig('read-tdengine', { ...config, topic: 'events' }, 'OFFLINE').some((issue) => issue.field === 'legacyReadOptions')).toBe(true);
    expect(connectorDefaults('read-tdengine', 'REALTIME')).toMatchObject({ offsetReset: 'earliest' });
  });

  it('消息组件 RocketMQ 的消费组、生产组及逐条确认默认值与执行契约一致', () => {
    // Inputs start with an empty 配置参数 (reference placeholder); outputs keep the "{}" default.
    expect(connectorDefaults('read-rocketmq')).toMatchObject({ offsetReset: 'earliest' });
    expect(connectorDefaults('read-rocketmq')).not.toHaveProperty('config');
    expect(connectorDefaults('write-rocketmq')).toMatchObject({ config: '{}', batchSize: 1 });
    expect(connectorDefaults('write-rocketmq')).not.toHaveProperty('offsetReset');
    for (const code of ['read-rocketmq', 'write-rocketmq']) {
      const config = { ...connectorDefaults(code), databaseId: '1', topic: 'events', columns: [{ name: 'id', type: 'LONG' }] };
      expect(validateConnectorConfig(code, config)).toContainEqual({ field: 'groupId', message: code.startsWith('read-') ? '请填写消费组名称' : '请填写生产组名称' });
      expect(validateConnectorConfig(code, { ...config, groupId: 'events-group' })).toEqual([]);
    }
  });

  it('安全编译状态与资源绑定路径校验一致', () => {
    for (const code of ['read-csv', 'write-csv', 'read-hdfs', 'write-hdfs', 'read-mongodb', 'write-mongodb'])
      expect(connectorSchemas[code]?.execution).toBe('MANAGED_DATAX');
    const config = { ...connectorDefaults('read-csv'), resourceBindingId: 'fixture', path: '../secret', columns: [{ name: 'id', type: 'LONG', index: 0 }] };
    expect(validateConnectorConfig('read-csv', config).map((item) => item.field)).toContain('path');
    config.path = 'incoming/source.csv';
    expect(validateConnectorConfig('read-csv', config)).toEqual([]);
  });

  it('新增 Hive 配置不预选写入模式（必须明确选择追加），读取默认全量，暂不支持的参数填写后提示清空', () => {
    expect(connectorDefaults('write-hive')).not.toHaveProperty('writeModeType');
    expect(connectorDefaults('write-hive')).not.toHaveProperty('batchSize');
    const base = { databaseId: '1', tableName: 'target', columns: [{ name: 'id', type: 'LONG' }] };
    expect(validateConnectorConfig('write-hive', base)).toContainEqual({ field: 'writeModeType', message: '请填写写入模式' });
    expect(validateConnectorConfig('write-hive', { ...base, writeModeType: 2 })).toEqual([]);
    expect(validateConnectorConfig('write-hive', { ...base, writeModeType: 2, preSql: 'delete from t', whereCondition: 'id > 1', batchSize: 10 }).map((issue) => issue.message))
      .toEqual(expect.arrayContaining(['where条件暂不支持，请清空', '前置SQL暂不支持，请清空', '单次写入数据暂不支持，请清空']));
    for (const code of ['read-hive', 'read-mongodb', 'read-odps']) {
      expect(connectorDefaults(code)).toMatchObject({ readMode: 'FULL' });
      expect(connectorDefaults(code)).not.toHaveProperty('dateFormat');
    }
    const write = connectorSchemas['write-hive']!.fields.find((field) => field.key === 'writeModeType')!;
    expect(write.options).toContainEqual({ label: '追加', value: 2, disabled: false });
    expect(write.options?.find((option) => option.value === 1)?.disabled).toBe(true);
    const read = connectorSchemas['read-hive']!.fields.find((field) => field.key === 'readMode')!;
    expect(read.options?.filter((option) => !option.disabled).map((option) => option.value)).toEqual(['FULL']);
    expect(connectorSchemas['write-hive']!.fields.some((field) => field.key === 'batchSize')).toBe(true);
  });

  it('读取模式切换只校验当前模式，增量起点保持十进制字符串', () => {
    const config = {
      ...connectorDefaults('read-hive'),
      databaseId: '9007199254740993',
      tableName: 't',
      readMode: 'ID_INCREMENT',
      incrementStart: '9007199254740995',
      columns: [{ name: 'id', type: 'LONG' }],
    };
    expect(validateConnectorConfig('read-hive', config)).toContainEqual({
      field: 'incrementColumn',
      message: '请填写id字段',
    });
    const field = connectorSchemas['read-hive']!.fields.find(
      (item) => item.key === 'cursorTime',
    )!;
    expect(connectorFieldVisible(field, config)).toBe(false);
    expect(config.incrementStart).toBe('9007199254740995');
    Object.assign(config, { incrementColumn: 'id' });
    expect(validateConnectorConfig('read-hive', config)).toEqual([]);
  });

  it('配置JSON错误和嵌套凭据均不能作为普通配置保存', () => {
    const config = {
      ...connectorDefaults('read-rabbitmq'),
      databaseId: '1',
      topic: 'events',
      columns: [{ name: 'id', type: 'STRING' }],
      config: '{',
    };
    expect(validateConnectorConfig('read-rabbitmq', config)).toContainEqual({
      field: 'config',
      message: '配置参数必须为有效的JSON对象',
    });
    config.config = '{"security":{"password":"example-placeholder"}}';
    expect(validateConnectorConfig('read-rabbitmq', config)).toContainEqual({
      field: 'config',
      message: '配置参数不可保存凭据，请在平台数据连接中配置',
    });
    Object.assign(config, {
      config: '{}',
      credential: { secret: 'example-placeholder' },
    });
    expect(validateConnectorConfig('read-rabbitmq', config)).toContainEqual({
      field: 'credentials',
      message: '节点配置不可保存凭据，请使用平台数据连接',
    });
  });

  it('受管服务仅放行顶层请求头凭据引用，仍拒绝内联凭据和超额读取预算', () => {
    const config = {
      ...connectorDefaults('read-webservice'), resourceBindingId: 'soap-fixture', apiUrl: 'https://example.test/wsdl',
      operationName: 'query', columns: [{ name: 'id', type: 'LONG' }], headerSecretRefs: '{"Authorization":"service.auth"}',
    };
    expect(validateConnectorConfig('read-webservice', config)).toEqual([]);
    const rejected = (patch: Record<string, unknown>) => validateConnectorConfig('read-webservice', { ...config, ...patch });
    expect(rejected({ headerSecretRefs: '{"Authorization":"Bearer plaintext"}' }).some((issue) => issue.field === 'headerSecretRefs')).toBe(true);
    expect(rejected({ headerParams: '{"Authorization":"plaintext"}' }).some((issue) => issue.field === 'headerParams')).toBe(true);
    expect(rejected({ requestParams: '{"nested":{"headerSecretRefs":{"Authorization":"service.auth"}}}' }).some((issue) => issue.field === 'requestParams')).toBe(true);
    expect(rejected({ requestParams: '{"nested":{"password":"plaintext"}}' }).some((issue) => issue.field === 'requestParams')).toBe(true);
    expect(rejected({ maxRows: 1_000_001, maxResponseBytes: 16_777_217 }).map((issue) => issue.field)).toEqual(['maxRows', 'maxResponseBytes']);
    expect(validateConnectorConfig('read-rabbitmq', { databaseId: '1', topic: 't', columns: [{ name: 'id', type: 'LONG' }], config: '{}', headerSecretRefs: '{}' }).some((issue) => issue.field === 'credentials')).toBe(true);
  });

  it('资源编号与后端一致，允许点号但不允许符号开头', () => {
    for (const code of ['read-ftp', 'write-ftp', 'read-webservice']) {
      const config = { ...connectorDefaults(code), resourceBindingId: 'files.v1', ...(code === 'read-webservice' ? {} : { credentialRef: 'ftp.fixture' }), path: 'input.csv', apiUrl: 'https://fixture.test/service?wsdl', operationName: 'query', columns: [{ name: 'id', type: 'LONG', index: 0 }] };
      for (const id of ['files.v1', '9.worker-x']) expect(validateConnectorConfig(code, { ...config, resourceBindingId: id })).toEqual([]);
      for (const id of ['_files', '-files', '.files', 'files/path']) expect(validateConnectorConfig(code, { ...config, resourceBindingId: id }).some((issue) => issue.field === 'resourceBindingId')).toBe(true);
    }
  });

  it('集合Mongo追加使用执行协议INSERT编号0，不能误映射为增量更新；写入模式与单次条数不预设', () => {
    expect(connectorDefaults('write-mongodb')).not.toHaveProperty('writeModeType');
    expect(connectorDefaults('write-mongodb')).not.toHaveProperty('batchSize');
    expect(connectorSchemas['write-mongodb']?.fields.find((field) => field.key === 'writeModeType')?.options).toContainEqual({ label: '追加', value: 0 });
    const base = { databaseId: '1', tableName: 't', columns: [{ name: 'id', type: 'LONG' }] };
    expect(validateConnectorConfig('write-mongodb', base)).toContainEqual({ field: 'writeModeType', message: '请填写写入模式' });
    expect(validateConnectorConfig('write-mongodb', { ...base, writeModeType: 0 })).toEqual([]);
  });

  it('受管ftp仅允许顶层凭据编号，预算和新建模式与运行契约一致', () => {
    const config = { ...connectorDefaults('write-ftp'), resourceBindingId: 'ftp-files', credentialRef: 'ftp.fixture', path: 'out', columns: [{ name: 'id', type: 'LONG' }] };
    expect(validateConnectorConfig('write-ftp', config)).toEqual([]);
    expect(config).toMatchObject({ maxBytes: 8_388_608, maxRows: 100_000, writeModeType: 2 });
    expect(connectorSchemas['write-ftp']!.connection).toBe(false);
    expect(connectorDefaults('write-ftp')).not.toHaveProperty('databaseId');
    expect(connectorSchemas['write-ftp']!.fields.find((field) => field.key === 'writeModeType')?.options).toContainEqual({ label: '全量', value: 1, disabled: true });
    expect(validateConnectorConfig('write-ftp', { ...config, credentialRef: 'Bearer plaintext' }).some((issue) => issue.field === 'credentialRef')).toBe(true);
    expect(validateConnectorConfig('write-ftp', { ...config, ftpConfig: '{"nested":{"credentialRef":"ftp.fixture"}}' }).some((issue) => issue.field === 'ftpConfig')).toBe(true);
    expect(validateConnectorConfig('write-ftp', { ...config, ftpConfig: '{"password":"plaintext"}' }).some((issue) => issue.field === 'ftpConfig')).toBe(true);
    expect(validateConnectorConfig('write-mongodb', { ...config, tableName: 't' }).some((issue) => issue.field === 'credentials')).toBe(true);
    expect(validateConnectorConfig('write-ftp', { ...config, maxBytes: 67_108_865 }).some((issue) => issue.field === 'maxBytes')).toBe(true);
  });
  it('持续订阅仅声明主题消费组参数，不自动丢弃历史表查询配置', () => {
    const config = { ...connectorDefaults('read-tdengine'), databaseId: '1', topic: 'events', groupId: 'group', columns: [{ name: 'id', type: 'LONG' }] };
    expect(validateConnectorConfig('read-tdengine', config)).toEqual([]);
    expect(connectorSchemas['read-tdengine']!.fields.some((field) => field.key === 'tableName')).toBe(false);
    expect(connectorDefaults('read-tdengine')).not.toHaveProperty('ifSql');
    const historical = { ...config, tableName: 'old', whereCondition: 'id > 1', unknown: 42 };
    expect(tdengineLegacyConfigKeys(historical)).toEqual(['tableName', 'whereCondition']);
    expect(validateConnectorConfig('read-tdengine', historical).some((issue) => issue.field === 'legacyReadOptions')).toBe(true);
    expect(historical).toMatchObject({ tableName: 'old', whereCondition: 'id > 1', unknown: 42 });
  });

  it('拒绝带凭据的URL、空字段、重名字段和非法文件字段索引', () => {
    const service = {
      ...connectorDefaults('read-webservice'),
      resourceBindingId: 'soap-fixture',
      apiUrl: 'https://user:example@example.test/wsdl',
      operationName: 'query',
      columns: [],
    };
    expect(
      validateConnectorConfig('read-webservice', service).map(
        (issue) => issue.field,
      ),
    ).toEqual(['apiUrl', 'columns']);
    const file = {
      ...connectorDefaults('read-ftp'),
      databaseId: '1',
      path: '/data/input.csv',
      columns: [
        { name: 'id', type: 'STRING', index: -1 },
        { name: 'id', type: 'LONG', index: 1 },
      ],
    };
    const messages = validateConnectorConfig('read-ftp', file).map(
      (issue) => issue.message,
    );
    expect(messages).toContain('第1行索引必须为非负整数');
    expect(messages).toContain('字段id重复');
  });
  it('缓存Redis解析值按数据结构校验，保留旧redisField协议并拒绝不一致别名', () => {
    const config = { ...connectorDefaults('read-redis'), databaseId: '1', keyPattern: 'cache:*', dataType: 'hash', columns: [{ name: 'value', type: 'STRING', redisField: '@value' }] };
    expect(validateConnectorConfig('read-redis', config).map((issue) => issue.message)).toContain('第1行解析值与Redis数据类型不一致');
    expect(validateConnectorConfig('read-redis', { ...config, dataType: 'list' })).toEqual([]);
    expect(validateConnectorConfig('read-redis', { ...config, columns: [{ name: 'name', type: 'STRING', key: '$.person.name' }] }).some((issue) => issue.field === 'columns')).toBe(true);
    expect(validateConnectorConfig('read-redis', { ...config, dataType: 'string', columns: [{ name: 'name', type: 'STRING', key: '$.person.name' }] })).toEqual([]);
    expect(validateConnectorConfig('read-redis', { ...config, columns: [{ name: 'name', type: 'STRING', redisField: 'old', key: 'new' }] }).map((issue) => issue.message)).toContain('第1行解析值配置不一致，请重新修改该字段');
  });
  it('消息主题和文件路径与右侧控件组成同一行，输出原布局保持', () => {
    expect(connectorSchemas['read-rabbitmq']?.fields.find((field) => field.key === 'topic')?.full).toBe(false);
    expect(connectorSchemas['read-hdfs']?.fields.find((field) => field.key === 'path')?.full).toBe(false);
    expect(connectorSchemas['read-ftp']?.fields.find((field) => field.key === 'path')?.full).toBe(false);
    // HDFS output follows the reference: the path takes its own row at half width.
    const hdfsPath = connectorSchemas['write-hdfs']?.fields.find((field) => field.key === 'path');
    expect(hdfsPath?.full).toBe(false);
    expect(hdfsPath?.halfRow).toBe(true);
    expect(connectorSchemas['write-ftp']?.fields.find((field) => field.key === 'path')?.full).toBe(true);
  });

});
