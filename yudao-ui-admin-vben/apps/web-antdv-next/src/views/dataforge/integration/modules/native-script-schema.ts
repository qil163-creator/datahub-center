export interface NativeScriptContext {
  engine?: string;
  mode?: string;
  toolCode: string;
}

export function validateNativeScript(config: Record<string, any>, context: NativeScriptContext): string[] {
  const errors: string[] = [];
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/u.test(config.resourceBindingId ?? '')) errors.push('请填写管理员授权的脚本资源编号');
  if (typeof config.code !== 'string' || !config.code.trim()) errors.push('请填写脚本代码');
  if (config.workerState !== 'BATCH') errors.push('请明确选择每批独立运行状态');
  const token = context.toolCode === 'custom-token';
  const realtime = context.mode === 'REALTIME' && !token;
  if (!['FLINK', 'SPARK'].includes(context.engine ?? '')) errors.push('当前任务引擎尚未确定');
  if (!['OFFLINE', 'REALTIME'].includes(context.mode ?? '')) errors.push('当前任务模式尚未确定');
  if (config.batchPolicy !== (realtime ? 'COUNT_OR_TIME' : 'PARTITION')) errors.push(token ? '认证脚本请选择作业启动初始化' : realtime ? '实时脚本请选择条数或时间窗口' : '离线脚本请选择完整分区批次');
  if (!Number.isInteger(config.maximumRows) || config.maximumRows < 1 || config.maximumRows > 10_000) errors.push('最大批次行数须为 1 至 10000 的整数');
  if (realtime && (!Number.isInteger(config.flushMillis) || config.flushMillis < 10 || config.flushMillis > 300_000)) errors.push('最长等待时间须为 10 至 300000 毫秒的整数');
  if (!realtime && config.flushMillis !== undefined && config.flushMillis !== null && config.flushMillis !== 0) errors.push(token ? '请移除初始化认证脚本不适用的等待时间' : '请移除离线脚本不适用的等待时间');
  if (config.batchSize !== undefined && config.batchSize !== null) errors.push('请清除历史批次行数，再明确设置最大批次行数');
  if (context.toolCode === 'custom-token') {
    if (config.outputFields !== undefined && config.outputFields !== null && (!Array.isArray(config.outputFields) || config.outputFields.length > 0)) errors.push('请清除初始化认证脚本不适用的输出字段');
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(config.tokenKey ?? '')) errors.push('请填写认证变量编号');
    return errors;
  }
  if (config.outputFields !== undefined && !Array.isArray(config.outputFields)) {
    errors.push('输出字段配置格式无效'); return errors;
  }
  const names = new Set<string>();
  if (config.outputFields?.length > 256) errors.push('输出字段最多为 256 个');
  for (const [index, field] of (config.outputFields ?? []).entries()) {
    const name: unknown = field?.columnName ?? field?.name;
    if (typeof name !== 'string' || !name.trim() || name.length > 128 || [...name].some((char) => /\p{Cc}/u.test(char))) errors.push(`第${index + 1}行输出字段名称无效`);
    else if (names.has(name)) errors.push(`输出字段${name}重复`);
    else names.add(name);
    const type = String(field?.columnType ?? field?.type ?? '').toUpperCase().replaceAll(/\s+/gu, '');
    const decimal = /^DECIMAL\((\d+),(\d+)\)$/u.exec(type);
    const timestamp = /^TIMESTAMP\((\d)\)$/u.exec(type);
    const valid = ['BIGINT', 'BOOL', 'BOOLEAN', 'CHAR', 'DATE', 'DATETIME', 'DOUBLE', 'FLOAT', 'INT', 'INTEGER', 'LONG', 'SMALLINT', 'STRING', 'TEXT', 'TIMESTAMP', 'TINYINT', 'VARCHAR'].includes(type)
      || !!decimal && Number(decimal[1]) >= 1 && Number(decimal[1]) <= 38 && Number(decimal[2]) <= Number(decimal[1])
      || !!timestamp && Number(timestamp[1]) <= (context.engine === 'SPARK' ? 6 : 9);
    if (!valid) errors.push(`第${index + 1}行输出字段类型无效或超出当前引擎精度`);
    if (field?.nullable !== undefined && field.nullable !== null && typeof field.nullable !== 'boolean') errors.push(`第${index + 1}行空值设置无效`);
  }
  return errors;
}
