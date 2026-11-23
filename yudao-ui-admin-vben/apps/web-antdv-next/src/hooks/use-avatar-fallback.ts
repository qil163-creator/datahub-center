import type { MaybeRefOrGetter, Ref } from 'vue';

import { onScopeDispose, ref, toValue, watch } from 'vue';

import { createLatestWins } from '#/utils/resource-id';

/**
 * 头像地址不可访问（如种子数据里失效的外部演示图床）时回退到默认头像。
 * 先直接展示用户头像，预加载失败后再切换；地址变化时只采纳最新一次的加载结果。
 */
export function useAvatarFallback(
  src: MaybeRefOrGetter<null | string | undefined>,
  fallback: MaybeRefOrGetter<string>,
): Ref<string> {
  const avatar = ref(toValue(src) || toValue(fallback));
  const latest = createLatestWins();

  watch(
    () => [toValue(src), toValue(fallback)] as const,
    ([url, defaultAvatar]) => {
      const request = latest.next();
      if (!url) {
        avatar.value = defaultAvatar;
        return;
      }
      avatar.value = url;
      const probe = new Image();
      probe.addEventListener('error', () => {
        if (request.isCurrent()) {
          avatar.value = defaultAvatar;
        }
      });
      probe.src = url;
    },
    { immediate: true },
  );

  onScopeDispose(() => latest.invalidate());

  return avatar;
}
