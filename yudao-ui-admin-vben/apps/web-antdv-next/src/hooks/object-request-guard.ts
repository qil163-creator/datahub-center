/** 响应仅属于最后一次请求、同一对象和仍有效的组件生命周期。 */
export function createObjectRequestGuard(identity: () => string) {
  let sequence = 0;
  let disposed = false;
  return {
    begin() {
      const current = ++sequence;
      const owner = identity();
      return () => !disposed && current === sequence && owner === identity();
    },
    dispose() {
      disposed = true;
      sequence += 1;
    },
  };
}
