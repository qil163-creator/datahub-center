export function createLatestGuard() {
  let seq = 0;
  return {
    next() {
      const id = ++seq;
      return () => id !== seq;
    },
    invalidate() {
      seq += 1;
    },
  };
}
