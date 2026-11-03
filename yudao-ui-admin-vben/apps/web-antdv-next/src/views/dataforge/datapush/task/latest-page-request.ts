/** 等待当前最新查询；过期查询的成功或失败都不能覆盖新结果。 */
export async function awaitLatestPageRequest<T>(
  request: Promise<T>,
  getLatest: () => Promise<T> | undefined,
  isCurrent: () => boolean,
): Promise<{ current: false } | { current: true; result: T }> {
  let pending = request;
  while (true) {
    try {
      const result = await pending;
      if (!isCurrent()) return { current: false };
      const latest = getLatest();
      if (latest && latest !== pending) {
        pending = latest;
        continue;
      }
      return { current: true, result };
    } catch (error) {
      if (!isCurrent()) return { current: false };
      const latest = getLatest();
      if (latest && latest !== pending) {
        pending = latest;
        continue;
      }
      throw error;
    }
  }
}
