import { onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { message } from 'antdv-next';

import {
  CommandPayloadChangedError,
  createCommandTracker,
} from '#/api/dataforge/execution/command';

/** 本领域页面共享：当前身份/lifetime 失效后，旧请求不能关闭新 loading 或写回页面。 */
export function useRunCommands() {
  const access = useAccessStore();
  const user = useUserStore();
  const scope = () =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      user.userInfo?.userId,
    ]);
  const tracker = createCommandTracker(scope);
  const busy = ref(false);
  let generation = 0;
  function invalidate() {
    ++generation;
    tracker.invalidate();
    busy.value = false;
  }
  watch(scope, invalidate, { flush: 'sync' });
  onBeforeUnmount(invalidate);
  return {
    busy,
    invalidate,
    async execute<P extends object, R>(
      key: string,
      payload: P,
      send: (command: P & { requestId: string }) => Promise<R>,
    ): Promise<R | undefined> {
      if (busy.value) return undefined;
      const started = generation;
      busy.value = true;
      try {
        return await tracker.execute(key, payload, send);
      } catch (error) {
        if (error instanceof CommandPayloadChangedError) {
          message.warning(error.message);
          return undefined;
        }
        throw error; // 平台请求错误由 requestClient 统一提示。
      } finally {
        if (started === generation) busy.value = false;
      }
    },
  };
}
