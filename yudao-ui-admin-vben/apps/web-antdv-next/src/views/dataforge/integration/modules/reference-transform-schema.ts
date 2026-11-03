type Row = Record<string, any>;

export const referenceCalculations = [
  ['ADD', 'A + B', 2],
  ['SUB', 'A - B', 2],
  ['MUL', 'A * B', 2],
  ['DIV', 'A / B', 2],
  ['ADD3', 'A + B + C', 3],
  ['MIN', 'Min(A, B)', 2],
  ['MAX', 'Max(A, B)', 2],
  ['ABS', 'Abs(A)', 1],
  ['ROUND', 'Round(A)', 1],
  ['CEIL', 'Ceil(A)', 1],
  ['FLOOR', 'Floor(A)', 1],
  ['MOD', 'A MOD B', 2],
  ['POWER', 'A ^ B', 2],
  ['SQRT', 'SQRT(A)', 1],
  ['ADD_DAYS', 'A + B Days', 2],
  ['DIFF_DAYS', 'A - B (in days)', 2],
  ['YEAR', 'Year of date A', 1],
  ['MONTH', 'Month of date A', 1],
  ['DAY', 'Day of date A', 1],
  ['STR_LEN', 'String length(A)', 1],
  ['UPPER', 'Upper case(A)', 1],
  ['LOWER', 'Lower case(A)', 1],
  ['INIT_CAP', 'Init cap(A)', 1],
  ['TRIM', 'Trim(A)', 1],
].map(([value, label, arity]) => ({
  value: value as string,
  label: label as string,
  arity: arity as number,
}));

export function validateReferenceDerive(config: Row): string[] {
  const errors: string[] = [];
  if (!String(config.targetField ?? '').trim())
    errors.push('请填写新增字段名称');
  const mode = config.operateType;
  if (
    ![
      'CONCAT',
      'CONSTANT',
      'EXPRESSION',
      'HASH',
      'REGEX_EXTRACT',
      'REPLACE',
      'SUBSTRING',
    ].includes(mode)
  )
    errors.push('请选择操作类型');
  if (!['CONSTANT', 'EXPRESSION'].includes(mode)) {
    const fields = Array.isArray(config.sourceFields)
      ? config.sourceFields
      : [];
    if (
      fields.length === 0 ||
      fields.some((field) => typeof field !== 'string' || field.trim() === '')
    )
      errors.push('请选择来源字段');
    if (new Set(fields).size !== fields.length) errors.push('来源字段不能重复');
    if (
      ['REGEX_EXTRACT', 'REPLACE', 'SUBSTRING'].includes(mode) &&
      fields.length !== 1
    )
      errors.push('当前操作只能选择一个来源字段');
  }
  if (
    mode === 'SUBSTRING' &&
    (!Number.isSafeInteger(config.beginIndex) ||
      config.beginIndex < 1 ||
      !Number.isSafeInteger(config.length) ||
      config.length < 0)
  )
    errors.push('截取起始位置从1开始，长度须为非负整数');
  if (mode === 'REPLACE' && String(config.search ?? '') === '')
    errors.push('请填写查找内容');
  if (mode === 'EXPRESSION' && !String(config.expression ?? '').trim())
    errors.push('请填写表达式');
  if (mode === 'HASH' && !['MD5', 'SHA1', 'SHA256'].includes(config.algorithm))
    errors.push('请选择哈希算法');
  if (
    mode === 'REGEX_EXTRACT' &&
    (!config.pattern ||
      !Number.isSafeInteger(config.groupIndex) ||
      config.groupIndex < 0)
  )
    errors.push('请填写正则表达式和非负分组序号');
  return errors;
}
const text = (value: unknown) =>
  value === null || value === undefined ? '' : String(value);

/** 使用十进制字符串比较边界，避免大整数和小数经过浮点转换。 */
export function compareReferenceDecimal(left: unknown, right: unknown): number {
  function parse(raw: unknown) {
    const value = text(raw).trim();
    const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/u.exec(value);
    if (!match || !(match[2] || match[3]) || value.length > 1024)
      throw new Error('数值格式无效或过长');
    const exponent = Number(match[4] || 0);
    if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 1000)
      throw new Error('数值指数超出支持范围');
    return {
      coefficient: BigInt(
        `${match[1] === '-' ? '-' : ''}${match[2] || '0'}${match[3] || ''}`,
      ),
      scale: (match[3]?.length ?? 0) - exponent,
    };
  }
  const a = parse(left);
  const b = parse(right);
  const scale = Math.max(a.scale, b.scale);
  const x = a.coefficient * 10n ** BigInt(scale - a.scale);
  const y = b.coefficient * 10n ** BigInt(scale - b.scale);
  if (x < y) return -1;
  if (x > y) return 1;
  return 0;
}

export function validateReferenceRanges(rows: readonly Row[]): string[] {
  const errors: string[] = [];
  const bounds: { max?: string; min?: string }[] = [];
  for (const [index, row] of rows.entries()) {
    const min = text(row.min).trim() || undefined;
    const max = text(row.max).trim() || undefined;
    try {
      if (min !== undefined) compareReferenceDecimal(min, min);
      if (max !== undefined) compareReferenceDecimal(max, max);
      if (
        min !== undefined &&
        max !== undefined &&
        compareReferenceDecimal(min, max) >= 0
      )
        errors.push(`第${index + 1}行下限必须小于上界`);
      bounds.push({ min, max });
    } catch {
      errors.push(`第${index + 1}行范围边界不是有效数值`);
    }
    if (text(row.value) === '') errors.push(`第${index + 1}行范围值不能为空`);
  }
  if (errors.length > 0) return errors;
  for (let i = 0; i < bounds.length; i++)
    for (let j = i + 1; j < bounds.length; j++) {
      const a = bounds[i]!;
      const b = bounds[j]!;
      if (
        (a.min === undefined ||
          b.max === undefined ||
          compareReferenceDecimal(a.min, b.max) < 0) &&
        (b.min === undefined ||
          a.max === undefined ||
          compareReferenceDecimal(b.min, a.max) < 0)
      )
        errors.push(`第${i + 1}行与第${j + 1}行范围重叠`);
    }
  return errors;
}

export function validateReferenceSequence(config: Row): string[] {
  if (!text(config.targetField).trim()) return ['请填写序列字段名称'];
  if (config.mode === 'UUID') return [];
  const values: bigint[] = [];
  for (const key of ['start', 'step', 'max']) {
    const value = config[key];
    if (
      (typeof value === 'number' && !Number.isSafeInteger(value)) ||
      !/^[+-]?\d+$/u.test(text(value))
    )
      return ['起始值、递增幅度、最大值必须为无损整数'];
    const integer = BigInt(text(value));
    if (
      integer < -9_223_372_036_854_775_808n ||
      integer > 9_223_372_036_854_775_807n
    )
      return ['序列参数超出Long范围'];
    values.push(integer);
  }
  const [start, step, max] = values as [bigint, bigint, bigint];
  if (step <= 0n) return ['递增幅度必须大于0'];
  if (max < 0n || (max > 0n && max < start))
    return ['最大值应为0（不限）或不小于起始值'];
  return [];
}

export function validateReferenceTransformRows(
  code: string,
  rows: readonly Row[],
): string[] {
  const errors: string[] = [];
  if (rows.length === 0) return ['请至少配置一行规则'];
  if (code === 'number-range') return validateReferenceRanges(rows);
  const targets = new Set<string>();
  for (const [index, row] of rows.entries()) {
    let key = 'name';
    if (code === 'calculator') key = 'targetField';
    else if (code === 'sort-records') key = 'field';
    else if (code === 'value-map') key = 'from';
    else if (code === 'set-field-value') key = 'targetField';
    else if (['string-operation', 'string-replace'].includes(code))
      key = 'inputField';
    const value = code === 'value-map' ? text(row[key]) : text(row[key]).trim();
    if (!value) errors.push(`第${index + 1}行字段或原值不能为空`);
    const target = ['string-operation', 'string-replace'].includes(code)
      ? text(row.outputField).trim() || value
      : value;
    if (targets.has(target)) errors.push(`存在重复字段或原值：${target}`);
    targets.add(target);
    if (code === 'calculator') {
      const operation = referenceCalculations.find(
        (item) => item.value === row.calcType,
      );
      if (operation) {
        for (const field of ['fieldA', 'fieldB', 'fieldC'].slice(
          0,
          operation.arity,
        ))
          if (!text(row[field]).trim())
            errors.push(`第${index + 1}行请填写${field}`);
      } else errors.push(`第${index + 1}行请选择计算类型`);
      if (!['DOUBLE', 'INTEGER', 'STRING'].includes(row.valueType))
        errors.push(`第${index + 1}行请选择值类型`);
    }
    if (code === 'set-field-value' && !text(row.sourceField).trim())
      errors.push(`第${index + 1}行替换字段不能为空`);
    if (code === 'string-replace' && text(row.search) === '')
      errors.push(`第${index + 1}行搜索内容不能为空`);
    if (
      code === 'string-operation' &&
      row.padDirection &&
      row.padDirection !== 'NONE' &&
      (!text(row.padCharacter) ||
        !Number.isSafeInteger(row.padLength) ||
        row.padLength < 0)
    )
      errors.push(`第${index + 1}行请填写填充字符和有效长度`);
    if (code === 'add-constant' && !row.fieldType)
      errors.push(`第${index + 1}行请选择字段类型`);
    if (code === 'sort-records' && !['ASC', 'DESC'].includes(row.order))
      errors.push(`第${index + 1}行请选择排序规则`);
  }
  return errors;
}
