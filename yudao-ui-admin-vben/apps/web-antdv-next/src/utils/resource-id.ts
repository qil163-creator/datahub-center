/** 路由ID保留十进制字符串；拒绝数组、负值及已丢失精度的数字。 */
export function parseResourceId(value: unknown): string | undefined {
  if (typeof value === 'number' && !Number.isSafeInteger(value))
    return undefined;
  return (typeof value === 'string' || typeof value === 'number') &&
    /^[1-9]\d*$/.test(String(value))
    ? String(value)
    : undefined;
}

export function resourceIdKey(value: unknown): string {
  return String(value ?? '');
}

export function sameResourceId(left: unknown, right: unknown): boolean {
  const a = parseResourceId(left);
  const b = parseResourceId(right);
  return a != null && a === b;
}

/** 详情/预览/评论等并发请求：只有最新一次可以写入状态。 */
export function createLatestWins() {
  let seq = 0;
  return {
    next() {
      seq += 1;
      const current = seq;
      return {
        isCurrent() {
          return current === seq;
        },
      };
    },
    invalidate() {
      seq += 1;
    },
  };
}
