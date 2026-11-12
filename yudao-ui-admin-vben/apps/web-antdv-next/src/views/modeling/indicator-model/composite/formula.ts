import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

/** 与后端 IndicatorFormula 一致的长度和引用上限；最终以后端校验为准。 */
export const FORMULA_MAX_LENGTH = 2000;
export const FORMULA_MAX_REFERENCES = 20;

const CODE = /^[a-z][\w]{0,99}$/i;

export interface FormulaCheck {
  /** 本地语法是否通过（不代表引用有效） */
  ok: boolean;
  message?: string;
  /** 按首次出现顺序、忽略大小写去重的引用编码 */
  references: string[];
}

type Token =
  | { code: string; kind: 'ref'; offset: number }
  | { kind: 'number'; offset: number }
  | { kind: 'op'; offset: number; text: string }
  | { kind: 'paren'; offset: number; text: '(' | ')' };

function tokenize(formula: string): string | Token[] {
  const tokens: Token[] = [];
  let index = 0;
  while (index < formula.length) {
    const char = formula[index]!;
    if (/\s/.test(char)) {
      index++;
      continue;
    }
    if ('+-*/'.includes(char)) {
      tokens.push({ kind: 'op', offset: index, text: char });
      index++;
    } else if (char === '(' || char === ')') {
      tokens.push({ kind: 'paren', offset: index, text: char });
      index++;
    } else if (char === '{') {
      const close = formula.indexOf('}', index + 1);
      if (close === -1) return `第 ${index + 1} 个字符处的指标引用缺少 }`;
      const code = formula.slice(index + 1, close);
      if (!CODE.test(code))
        return `指标引用 {${code}} 必须是字母开头的英文编码`;
      tokens.push({ kind: 'ref', code, offset: index });
      index = close + 1;
    } else if (char >= '0' && char <= '9') {
      const match = /^\d+(?:\.\d+)?/.exec(formula.slice(index))!;
      const next = formula[index + match[0].length];
      if (next === '.') return `第 ${index + 1} 个字符处的数字格式不正确`;
      if (match[0].length > 38) return '数字常量不能超过 38 位';
      tokens.push({ kind: 'number', offset: index });
      index += match[0].length;
    } else {
      return `第 ${index + 1} 个字符“${char}”不允许出现在公式中，只能使用数字、+ - * /、括号和 {指标编码}`;
    }
  }
  return tokens;
}

/** 递归下降语法检查，只判断结构，不求值。 */
function parse(tokens: Token[]): string | undefined {
  let position = 0;
  let depth = 0;
  const peekOp = (ops: string) => {
    const token = tokens[position];
    return token?.kind === 'op' && ops.includes(token.text);
  };
  function expression(): string | undefined {
    const left = term();
    if (left) return left;
    while (peekOp('+-')) {
      position++;
      const right = term();
      if (right) return right;
    }
    return undefined;
  }
  function term(): string | undefined {
    const left = unary();
    if (left) return left;
    while (peekOp('*/')) {
      position++;
      const right = unary();
      if (right) return right;
    }
    return undefined;
  }
  function unary(): string | undefined {
    if (depth > 32) return '公式嵌套层级不能超过 32';
    if (peekOp('+-')) {
      position++;
      depth++;
      const result = unary();
      depth--;
      return result;
    }
    return primary();
  }
  function primary(): string | undefined {
    const token = tokens[position++];
    if (!token) return '公式不完整，结尾缺少数字、指标引用或右括号';
    if (token.kind === 'number' || token.kind === 'ref') return undefined;
    if (token.kind === 'paren' && token.text === '(') {
      depth++;
      const inner = expression();
      depth--;
      if (inner) return inner;
      const close = tokens[position];
      if (close?.kind !== 'paren' || close.text !== ')')
        return `第 ${token.offset + 1} 个字符处的左括号未闭合`;
      position++;
      return undefined;
    }
    return `第 ${token.offset + 1} 个字符处缺少数字、指标引用或左括号`;
  }
  const error = expression();
  if (error) return error;
  if (position !== tokens.length)
    return `第 ${tokens[position]!.offset + 1} 个字符附近存在多余内容`;
  return undefined;
}

/** 本地即时语法检查，避免每次按键都请求后端。 */
export function checkFormula(formula?: null | string): FormulaCheck {
  // 表单值可能来自后端的 null；非字符串一律按空公式处理。
  const text = typeof formula === 'string' ? formula : '';
  if (text.trim() === '')
    return { ok: false, message: '公式不能为空', references: [] };
  if (text.length > FORMULA_MAX_LENGTH)
    return {
      ok: false,
      message: `公式长度不能超过 ${FORMULA_MAX_LENGTH} 个字符`,
      references: [],
    };
  const tokens = tokenize(text);
  if (typeof tokens === 'string')
    return { ok: false, message: tokens, references: [] };
  const seen = new Map<string, string>();
  for (const token of tokens) {
    if (token.kind === 'ref' && !seen.has(token.code.toLowerCase()))
      seen.set(token.code.toLowerCase(), token.code);
  }
  const references = [...seen.values()];
  const error = parse(tokens);
  if (error) return { ok: false, message: error, references };
  if (references.length === 0)
    return { ok: false, message: '公式至少需要引用一个指标', references };
  if (references.length > FORMULA_MAX_REFERENCES)
    return {
      ok: false,
      message: `公式引用的指标不能超过 ${FORMULA_MAX_REFERENCES} 个`,
      references,
    };
  return { ok: true, references };
}

/** 在光标处插入 {编码}，返回新文本与新光标位置。 */
export function insertReference(
  formula: string,
  code: string,
  selectionStart?: number,
  selectionEnd?: number,
) {
  const text = formula ?? '';
  const start = Math.max(
    0,
    Math.min(selectionStart ?? text.length, text.length),
  );
  const end = Math.max(start, Math.min(selectionEnd ?? start, text.length));
  const before = text.slice(0, start);
  const after = text.slice(end);
  const token = `{${code}}`;
  const left = before === '' || /[\s(]$/.test(before) ? '' : ' ';
  const right = after === '' || /^[\s)]/.test(after) ? '' : ' ';
  const value = `${before}${left}${token}${right}${after}`;
  return { value, cursor: before.length + left.length + token.length };
}

export interface CompositeFormValues {
  code: string;
  dataDomainId: IndicatorModelApi.Id;
  definition?: string;
  description?: string;
  formula: string;
  name: string;
  ownerUserId?: IndicatorModelApi.Id;
  status?: IndicatorModelApi.Status;
  subjectDomainId?: IndicatorModelApi.Id;
  unit?: string;
}

/** 只提交复合指标可写字段；结果类型由后端固定为 DECIMAL。 */
export function compositeSaveData(
  values: CompositeFormValues,
  current?: IndicatorModelApi.Model,
): IndicatorModelApi.Save {
  const optional = (value?: string) => {
    const trimmed = value?.trim();
    return trimmed || undefined;
  };
  return {
    id: current?.id,
    expectedRevision: current?.revision,
    indicatorType: 'COMPOSITE',
    code: values.code.trim(),
    name: values.name.trim(),
    dataDomainId: values.dataDomainId,
    subjectDomainId: values.subjectDomainId,
    ownerUserId: values.ownerUserId,
    status: current ? undefined : (values.status ?? 'DRAFT'),
    unit: optional(values.unit),
    definition: optional(values.definition),
    description: optional(values.description),
    formula: values.formula.trim(),
  };
}

export interface DependencyTreeNode {
  key: string;
  title: string;
  children: DependencyTreeNode[];
}

const TYPE_SHORT: Record<IndicatorModelApi.Type, string> = {
  ATOMIC: '原子',
  COMPOSITE: '复合',
  DERIVED: '派生',
};

/** 依赖树转 Tree 组件数据；key 带路径，共享节点在不同分支下也唯一。 */
export function dependencyTree(
  nodes: IndicatorModelApi.DependencyNode[],
  parent = 'root',
): DependencyTreeNode[] {
  return nodes.map((node, index) => {
    const key = `${parent}/${index}:${String(node.id)}`;
    let detail = '';
    if (node.indicatorType === 'COMPOSITE' && node.formula) {
      detail = ` = ${node.formula}`;
    } else if (node.indicatorType === 'DERIVED' && node.timePeriod) {
      detail = ` · ${node.timePeriod}`;
    }
    return {
      key,
      title: `[${TYPE_SHORT[node.indicatorType] ?? node.indicatorType}] ${node.name} (${node.code})${detail}`,
      children: dependencyTree(node.children ?? [], key),
    };
  });
}
