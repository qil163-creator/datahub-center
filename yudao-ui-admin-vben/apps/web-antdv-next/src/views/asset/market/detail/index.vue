<script lang="ts" setup>
import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert } from 'antdv-next';

import { parseResourceId } from '#/utils/resource-id';

import ResourceDetail from '../modules/mart-detail.vue';
defineOptions({ name: 'AssetMarketDetailPage' });
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const detail = ref<InstanceType<typeof ResourceDetail>>();
const id = computed(() => parseResourceId(route.params.id));
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const owner = computed(() =>
  JSON.stringify([
    route.params.id,
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
let active = false;
let sequence = 0;
async function load() {
  const ticket = ++sequence;
  detail.value?.close();
  await nextTick();
  if (active && ticket === sequence && id.value && canQuery.value)
    await detail.value?.open(id.value);
}
function activate() {
  active = true;
  void load();
}
watch(
  owner,
  () => {
    void load();
  },
  { flush: 'sync' },
);
onMounted(activate);
onActivated(() => {
  if (!active) activate();
});
onDeactivated(() => {
  active = false;
  sequence++;
  detail.value?.close();
});
onBeforeUnmount(() => {
  active = false;
  sequence++;
});
</script>
<template>
  <Page auto-content-height content-class="flex min-h-0 flex-col overflow-auto">
    <Alert
      v-if="!canQuery || !id"
      message="资产编号无效或登录身份已失效。"
      type="warning"
      show-icon
    />
    <ResourceDetail
      v-if="canQuery && id"
      ref="detail"
      embedded
      @back="router.push(String(route.meta.activePath || '/asset/mart'))"
    />
  </Page>
</template>
