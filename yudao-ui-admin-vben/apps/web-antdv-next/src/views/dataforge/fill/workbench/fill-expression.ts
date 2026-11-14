/**
 * 在线填报表达式语言（与后端 FillExpression v1 同语法、同语义）。
 *
 * 只用于录入时即时显示公式结果与校验模板定义；提交时以服务端重算为准。
 * 数值使用 BigInt 定点小数，不经过 JavaScript 浮点，避免 0.1 + 0.2 之类的精度误差。
 */

// ================================================================ 定点小数

/** 定点小数：value = units / 10^scale */
export class Decimal {
  constructor(
    readonly units: bigint,
    readonly scale: number,
  ) {}

  static of(value: number): Decimal {
    return Decimal.parse(String(value)) ?? new Decimal(0n, 0);
  }

  static parse(text: string): Decimal | null {
    const trimmed = text.trim();
    const match = /^([+-]?)(\d*)(?:\.(\d*))?$/.exec(trimmed);
    if (!match || (!match[2] && !match[3])) return null;
    const fraction = match[3] ?? '';
    const digits = `${match[2] || '0'}${fraction}`;
    const units = BigInt(digits) * (match[1] === '-' ? -1n : 1n);
    return new Decimal(units, fraction.length);
  }

  private static align(a: Decimal, b: Decimal): [bigint, bigint, number] {
    const scale = Math.max(a.scale, b.scale);
    return [
      a.units * Decimal.pow10(scale - a.scale),
      b.units * Decimal.pow10(scale - b.scale),
      scale,
    ];
  }

  /** 四舍五入（HALF_UP，远离零） */
  private static divRound(numerator: bigint, denominator: bigint): bigint {
    const negative = numerator < 0n !== denominator < 0n;
    const n = numerator < 0n ? -numerator : numerator;
    const d = denominator < 0n ? -denominator : denominator;
    const quotient = (n * 2n + d) / (d * 2n);
    return negative ? -quotient : quotient;
  }

  private static pow10(n: number): bigint {
    return 10n ** BigInt(n);
  }

  abs(): Decimal {
    return this.units < 0n ? this.negate() : this;
  }

  add(other: Decimal): Decimal {
    const [a, b, scale] = Decimal.align(this, other);
    return new Decimal(a + b, scale);
  }

  compare(other: Decimal): number {
    const [a, b] = Decimal.align(this, other);
    return a === b ? 0 : a < b ? -1 : 1;
  }

  /** 除法保留 16 位小数（与服务端 DECIMAL64 在常规取值下一致，最终按字段小数位舍入） */
  divide(other: Decimal): Decimal | null {
    if (other.units === 0n) return null;
    const scale = 16;
    const numerator =
      this.units * Decimal.pow10(scale + other.scale);
    const denominator = other.units * Decimal.pow10(this.scale);
    return new Decimal(Decimal.divRound(numerator, denominator), scale).trim();
  }

  isZero(): boolean {
    return this.units === 0n;
  }

  multiply(other: Decimal): Decimal {
    return new Decimal(this.units * other.units, this.scale + other.scale);
  }

  negate(): Decimal {
    return new Decimal(-this.units, this.scale);
  }

  /** 取余：结果符号与被除数一致（同 Java BigDecimal.remainder） */
  remainder(other: Decimal): Decimal | null {
    if (other.units === 0n) return null;
    const [a, b, scale] = Decimal.align(this, other);
    return new Decimal(a % b, scale);
  }

  setScale(scale: number): Decimal {
    if (scale >= this.scale) {
      return new Decimal(this.units * Decimal.pow10(scale - this.scale), scale);
    }
    return new Decimal(
      Decimal.divRound(this.units, Decimal.pow10(this.scale - scale)),
      scale,
    );
  }

  subtract(other: Decimal): Decimal {
    return this.add(other.negate());
  }

  toString(): string {
    const negative = this.units < 0n;
    let digits = (negative ? -this.units : this.units).toString();
    if (this.scale > 0) {
      digits = digits.padStart(this.scale + 1, '0');
      digits = `${digits.slice(0, -this.scale)}.${digits.slice(-this.scale)}`;
    }
    return negative ? `-${digits}` : digits;
  }

  /** 去掉多余的末尾零（只用于除法中间结果） */
  private trim(): Decimal {
    let { units, scale } = this;
    while (scale > 0 && units % 10n === 0n) {
      units /= 10n;
      scale -= 1;
    }
    return new Decimal(units, scale);
  }
}

// ================================================================ 语法树与解析

export type ExpressionScope = 'FORMULA' | 'PERIOD' | 'ROW' | 'TABLE';
type Value = boolean | Decimal | null | string;

type Node =
  | { args: Node[]; kind: 'call'; name: string }
  | { code: string; kind: 'field' }
  | { kind: 'binary'; left: Node; op: string; right: Node }
  | { kind: 'literal'; value: Value }
  | { kind: 'unary'; op: string; operand: Node };

export class ExpressionError extends Error {}

const ROW_FUNCTIONS = new Set([
  'ABS',
  'COALESCE',
  'IF',
  'LEN',
  'MAX',
  'MIN',
  'ROUND',
]);
const AGGREGATES = new Set(['AVG', 'COUNT', 'SUM']);
const PREVIOUS = new Set(['PREV_AVG', 'PREV_COUNT', 'PREV_SUM']);
const MAX_LENGTH = 1000;
const MAX_NODES = 300;

class Parser {
  private nodes = 0;
  private pos = 0;

  constructor(private readonly text: string) {}

  parseAll(): Node {
    const node = this.parseOr();
    this.skipSpaces();
    if (this.pos < this.text.length) throw this.error('无法识别的内容');
    return node;
  }

  private count<T extends Node>(node: T): T {
    this.nodes += 1;
    if (this.nodes > MAX_NODES) throw new ExpressionError('表达式过于复杂');
    return node;
  }

  private error(message: string) {
    return new ExpressionError(`${message}（第 ${this.pos + 1} 个字符）`);
  }

  private expect(token: string) {
    if (!this.match(token)) throw this.error(`缺少 '${token}'`);
  }

  private match(token: string): boolean {
    this.skipSpaces();
    if (this.text.startsWith(token, this.pos)) {
      this.pos += token.length;
      return true;
    }
    return false;
  }

  private matchWord(word: string): boolean {
    this.skipSpaces();
    const end = this.pos + word.length;
    const candidate = this.text.slice(this.pos, end);
    const next = this.text[end];
    if (
      candidate.toUpperCase() === word &&
      (next === undefined || !/[\w]/.test(next))
    ) {
      this.pos = end;
      return true;
    }
    return false;
  }

  private parseAdditive(): Node {
    let left = this.parseMultiplicative();
    for (;;) {
      if (this.match('+')) {
        left = this.count({ kind: 'binary', left, op: '+', right: this.parseMultiplicative() });
      } else if (this.match('-')) {
        left = this.count({ kind: 'binary', left, op: '-', right: this.parseMultiplicative() });
      } else {
        return left;
      }
    }
  }

  private parseAnd(): Node {
    let left = this.parseNot();
    while (this.matchWord('AND') || this.match('&&')) {
      left = this.count({ kind: 'binary', left, op: 'AND', right: this.parseNot() });
    }
    return left;
  }

  private parseComparison(): Node {
    const left = this.parseAdditive();
    for (const candidate of ['==', '!=', '<>', '<=', '>=', '=', '<', '>']) {
      if (this.match(candidate)) {
        const op = candidate === '==' ? '=' : candidate === '<>' ? '!=' : candidate;
        return this.count({ kind: 'binary', left, op, right: this.parseAdditive() });
      }
    }
    return left;
  }

  private parseMultiplicative(): Node {
    let left = this.parseUnary();
    for (;;) {
      const op = ['*', '/', '%'].find((candidate) => this.match(candidate));
      if (!op) return left;
      left = this.count({ kind: 'binary', left, op, right: this.parseUnary() });
    }
  }

  private parseNot(): Node {
    this.skipSpaces();
    if (
      this.matchWord('NOT') ||
      (this.text[this.pos] === '!' && this.text[this.pos + 1] !== '=' && this.match('!'))
    ) {
      return this.count({ kind: 'unary', op: 'NOT', operand: this.parseNot() });
    }
    return this.parseComparison();
  }

  private parseOr(): Node {
    let left = this.parseAnd();
    while (this.matchWord('OR') || this.match('||')) {
      left = this.count({ kind: 'binary', left, op: 'OR', right: this.parseAnd() });
    }
    return left;
  }

  private parsePrimary(): Node {
    this.skipSpaces();
    if (this.pos >= this.text.length) throw this.error('表达式不完整');
    const c = this.text[this.pos]!;
    if (c === '(') {
      this.pos += 1;
      const inner = this.parseOr();
      this.expect(')');
      return inner;
    }
    if (c === "'" || c === '"') {
      return this.count({ kind: 'literal', value: this.readString(c) });
    }
    if (/\d/.test(c) || (c === '.' && /\d/.test(this.text[this.pos + 1] ?? ''))) {
      const start = this.pos;
      while (this.pos < this.text.length && /[\d.]/.test(this.text[this.pos]!)) this.pos += 1;
      const number = Decimal.parse(this.text.slice(start, this.pos));
      if (!number) {
        this.pos = start;
        throw this.error('数字格式不正确');
      }
      return this.count({ kind: 'literal', value: number });
    }
    if (/[A-Za-z_一-鿿]/.test(c)) {
      const start = this.pos;
      while (this.pos < this.text.length && /[\w一-鿿]/.test(this.text[this.pos]!)) {
        this.pos += 1;
      }
      const word = this.text.slice(start, this.pos);
      const upper = word.toUpperCase();
      if (upper === 'TRUE') return this.count({ kind: 'literal', value: true });
      if (upper === 'FALSE') return this.count({ kind: 'literal', value: false });
      if (upper === 'NULL') return this.count({ kind: 'literal', value: null });
      if (this.match('(')) {
        const args: Node[] = [];
        if (!this.match(')')) {
          do {
            args.push(this.parseOr());
          } while (this.match(','));
          this.expect(')');
        }
        return this.count({ args, kind: 'call', name: upper });
      }
      return this.count({ code: word, kind: 'field' });
    }
    throw this.error(`无法识别的字符 '${c}'`);
  }

  private parseUnary(): Node {
    if (this.match('-')) {
      return this.count({ kind: 'unary', op: '-', operand: this.parseUnary() });
    }
    if (this.match('+')) return this.parseUnary();
    return this.parsePrimary();
  }

  private readString(quote: string): string {
    const start = this.pos;
    this.pos += 1;
    let result = '';
    while (this.pos < this.text.length) {
      const c = this.text[this.pos]!;
      if (c === quote) {
        if (this.text[this.pos + 1] === quote) {
          result += quote;
          this.pos += 2;
          continue;
        }
        this.pos += 1;
        return result;
      }
      result += c;
      this.pos += 1;
    }
    this.pos = start;
    throw this.error('字符串缺少结束引号');
  }

  private skipSpaces() {
    while (this.pos < this.text.length && /\s/.test(this.text[this.pos]!)) this.pos += 1;
  }
}

function check(node: Node, scope: ExpressionScope, insideAggregate: boolean, refs: Set<string>) {
  switch (node.kind) {
    case 'binary': {
      check(node.left, scope, insideAggregate, refs);
      check(node.right, scope, insideAggregate, refs);
      return;
    }
    case 'call': {
      const { args, name } = node;
      const aggregate = AGGREGATES.has(name);
      const previous = PREVIOUS.has(name);
      if (aggregate || previous) {
        if (scope === 'FORMULA' || scope === 'ROW') {
          throw new ExpressionError(`${name} 只能用于表级或跨期规则`);
        }
        if (previous && scope !== 'PERIOD') {
          throw new ExpressionError(`${name} 只能用于跨期规则`);
        }
        if (insideAggregate) throw new ExpressionError(`${name} 不能嵌套在其他汇总函数内`);
        const [only] = args;
        if (args.length !== 1 || only?.kind !== 'field') {
          throw new ExpressionError(`${name} 的参数必须是一个字段`);
        }
        refs.add(only.code);
        return;
      }
      if (!ROW_FUNCTIONS.has(name)) throw new ExpressionError(`不支持的函数 ${name}`);
      const argc = args.length;
      const arityOk =
        name === 'ROUND'
          ? argc === 1 || argc === 2
          : name === 'ABS' || name === 'LEN'
            ? argc === 1
            : name === 'IF'
              ? argc === 3
              : argc >= 1;
      if (!arityOk) throw new ExpressionError(`${name} 的参数个数不正确`);
      args.forEach((arg) => check(arg, scope, insideAggregate, refs));
      return;
    }
    case 'field': {
      if ((scope === 'TABLE' || scope === 'PERIOD') && !insideAggregate) {
        throw new ExpressionError(
          `表级规则中字段【${node.code}】必须放在 SUM/COUNT/AVG 等汇总函数内`,
        );
      }
      refs.add(node.code);
      return;
    }
    case 'literal': {
      return;
    }
    case 'unary': {
      check(node.operand, scope, insideAggregate, refs);
    }
  }
}

// ================================================================ 求值

export interface ExpressionContext {
  column?: (fieldCode: string) => unknown[];
  field?: (fieldCode: string) => unknown;
  previousColumn?: (fieldCode: string) => null | unknown[];
}

function normalize(value: unknown): Value {
  if (value === null || value === undefined) return null;
  if (value instanceof Decimal || typeof value === 'boolean') return value;
  if (typeof value === 'number') return Number.isFinite(value) ? Decimal.of(value) : null;
  const text = String(value).trim();
  return text === '' ? null : text;
}

function toNumber(value: Value): Decimal | null {
  if (value instanceof Decimal) return value;
  if (typeof value === 'string') return Decimal.parse(value);
  return null;
}

function toBoolean(value: Value): boolean | null {
  if (typeof value === 'boolean') return value;
  if (value instanceof Decimal) return !value.isZero();
  return null;
}

function stringOf(value: Value): string {
  return value instanceof Decimal ? value.toString() : String(value);
}

function compare(left: Value, right: Value): number {
  const a = toNumber(left);
  const b = toNumber(right);
  if (a && b) return a.compare(b);
  const x = stringOf(left);
  const y = stringOf(right);
  return x === y ? 0 : x < y ? -1 : 1;
}

function aggregate(name: string, values: unknown[]): Value {
  let sum = new Decimal(0n, 0);
  let count = 0;
  for (const raw of values) {
    const value = normalize(raw);
    if (value === null) continue;
    count += 1;
    const number = toNumber(value);
    if (name !== 'COUNT' && number) sum = sum.add(number);
  }
  if (name === 'COUNT') return Decimal.of(count);
  if (name === 'SUM') return sum;
  return count === 0 ? null : sum.divide(Decimal.of(count));
}

function evaluate(node: Node, context: ExpressionContext): Value {
  switch (node.kind) {
    case 'binary': {
      return evaluateBinary(node, context);
    }
    case 'call': {
      return evaluateCall(node, context);
    }
    case 'field': {
      return normalize(context.field?.(node.code));
    }
    case 'literal': {
      return node.value;
    }
    case 'unary': {
      const value = evaluate(node.operand, context);
      if (node.op === 'NOT') {
        const bool = toBoolean(value);
        return bool === null ? null : !bool;
      }
      return toNumber(value)?.negate() ?? null;
    }
  }
}

function evaluateBinary(
  node: Extract<Node, { kind: 'binary' }>,
  context: ExpressionContext,
): Value {
  const { op } = node;
  if (op === 'AND' || op === 'OR') {
    const left = toBoolean(evaluate(node.left, context));
    if (op === 'AND' && left === false) return false;
    if (op === 'OR' && left === true) return true;
    const right = toBoolean(evaluate(node.right, context));
    if (op === 'AND') return right === false ? false : left === null || right === null ? null : true;
    return right === true ? true : left === null || right === null ? null : false;
  }
  const left = evaluate(node.left, context);
  const right = evaluate(node.right, context);
  if (left === null || right === null) return null;
  if (['%', '*', '+', '-', '/'].includes(op)) {
    const a = toNumber(left);
    const b = toNumber(right);
    if (!a || !b) return null;
    switch (op) {
      case '*': {
        return a.multiply(b);
      }
      case '+': {
        return a.add(b);
      }
      case '-': {
        return a.subtract(b);
      }
      case '/': {
        return a.divide(b);
      }
      default: {
        return a.remainder(b);
      }
    }
  }
  const cmp = compare(left, right);
  switch (op) {
    case '!=': {
      return cmp !== 0;
    }
    case '<': {
      return cmp < 0;
    }
    case '<=': {
      return cmp <= 0;
    }
    case '=': {
      return cmp === 0;
    }
    case '>': {
      return cmp > 0;
    }
    default: {
      return cmp >= 0;
    }
  }
}

function evaluateCall(
  node: Extract<Node, { kind: 'call' }>,
  context: ExpressionContext,
): Value {
  const { args, name } = node;
  if (AGGREGATES.has(name)) {
    const code = (args[0] as Extract<Node, { kind: 'field' }>).code;
    return aggregate(name, context.column?.(code) ?? []);
  }
  if (PREVIOUS.has(name)) {
    const code = (args[0] as Extract<Node, { kind: 'field' }>).code;
    const previous = context.previousColumn?.(code);
    return previous ? aggregate(name.slice('PREV_'.length), previous) : null;
  }
  switch (name) {
    case 'ABS': {
      return toNumber(evaluate(args[0]!, context))?.abs() ?? null;
    }
    case 'COALESCE': {
      for (const arg of args) {
        const value = evaluate(arg, context);
        if (value !== null) return value;
      }
      return null;
    }
    case 'IF': {
      return toBoolean(evaluate(args[0]!, context)) === true
        ? evaluate(args[1]!, context)
        : evaluate(args[2]!, context);
    }
    case 'LEN': {
      const value = evaluate(args[0]!, context);
      return value === null ? null : Decimal.of([...stringOf(value)].length);
    }
    case 'ROUND': {
      const value = toNumber(evaluate(args[0]!, context));
      const scale = args[1] ? toNumber(evaluate(args[1], context)) : new Decimal(0n, 0);
      if (!value || !scale) return null;
      const digits = Math.max(0, Math.min(10, Number(scale.setScale(0).units)));
      return value.setScale(digits);
    }
    default: {
      let best: Decimal | null = null;
      for (const arg of args) {
        const value = toNumber(evaluate(arg, context));
        if (value && (!best || (name === 'MIN' ? value.compare(best) < 0 : value.compare(best) > 0))) {
          best = value;
        }
      }
      return best;
    }
  }
}

// ================================================================ 对外接口

export interface CompiledExpression {
  evaluate: (context: ExpressionContext) => Value;
  fieldRefs: string[];
  scope: ExpressionScope;
}

/** 编译表达式；语法错误或使用了范围外的函数时抛出 ExpressionError */
export function compileExpression(text: string, scope: ExpressionScope): CompiledExpression {
  if (!text?.trim()) throw new ExpressionError('表达式不能为空');
  if (text.length > MAX_LENGTH) throw new ExpressionError(`表达式长度不能超过 ${MAX_LENGTH}`);
  const root = new Parser(text).parseAll();
  const refs = new Set<string>();
  check(root, scope, false, refs);
  return {
    evaluate: (context) => evaluate(root, context),
    fieldRefs: [...refs],
    scope,
  };
}

/** 设计期校验：返回错误说明，合法时返回 undefined */
export function validateExpression(
  text: string,
  scope: ExpressionScope,
  fieldCodes?: string[],
): string | undefined {
  try {
    const compiled = compileExpression(text, scope);
    if (fieldCodes) {
      const unknown = compiled.fieldRefs.filter((ref) => !fieldCodes.includes(ref));
      if (unknown.length > 0) return `引用了不存在的字段 ${unknown.join('、')}`;
    }
    return undefined;
  } catch (error) {
    return error instanceof ExpressionError ? error.message : String(error);
  }
}

/**
 * 计算结果 → 单元格显示值：数值字段按小数位四舍五入（整数字段 0 位），保持十进制字符串。
 * fieldType：1 小数 5 整数 6 长整型，其余按文本。
 */
export function toFieldValue(value: Value, fieldType?: number, scale?: number): string {
  if (value === null) return '';
  if (fieldType === 1 || fieldType === 5 || fieldType === 6) {
    const number = toNumber(value);
    if (!number) return '';
    return number.setScale(fieldType === 1 ? (scale ?? 2) : 0).toString();
  }
  if (typeof value === 'boolean') return value ? '是' : '否';
  return stringOf(value);
}
