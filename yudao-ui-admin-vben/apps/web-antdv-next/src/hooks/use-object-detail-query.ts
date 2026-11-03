import { nextTick, onBeforeUnmount, watch } from 'vue';
import { useRoute } from 'vue-router';

/** 在列表页或复用页签中消费对象详情链接，编号保持十进制字符串。 */
export function useObjectDetailQuery(open: (id: string) => unknown) {
  const route = useRoute();
  const ownerPath = route.path;
  let sequence = 0;
  watch(
    () =>
      [route.path, route.query.detailId, route.query.referenceVersion] as const,
    async ([path, id]) => {
      const current = ++sequence;
      if (
        path !== ownerPath ||
        typeof id !== 'string' ||
        !/^[1-9]\d*$/.test(id)
      )
        return;
      await nextTick();
      if (current === sequence) await open(id);
    },
    { immediate: true },
  );
  onBeforeUnmount(() => {
    sequence++;
  });
}
