<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';

import { computed, onMounted, onUnmounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Empty,
  Input,
  message,
  Rate,
  Select,
  Spin,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  createMarketComment,
  getMarketCommentPage,
  replyMarketComment,
} from '#/api/asset/market';
import { createLatestWins, resourceIdKey } from '#/utils/resource-id';

import { formatMarketTime } from '../market-time';

const props = defineProps<{ assetId: number | string }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const owner = computed(() =>
  JSON.stringify([
    resourceIdKey(props.assetId),
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const sessionScope = computed(() =>
  JSON.stringify([owner.value, access.accessToken, access.refreshToken]),
);
const canQuery = computed(() => hasAccessByCodes(['asset:market:query']));
const canComment = computed(() => hasAccessByCodes(['asset:market:comment']));
const canReply = computed(() => hasAccessByCodes(['asset:market:check']));

const list = ref<MarketApi.Comment[]>([]);
const commentStr = ref('');
const level = ref(5);
const commentKind = ref(1);
const problemType = ref('');
const impactScope = ref('');
const replyMap = ref<Record<string, string>>({});
const loading = ref(false);
const submitting = ref(false);
const commentGuard = createLatestWins();
let submission: undefined | { identity: string; key: string };
let generation = 0;
let alive = true;

function submissionData() {
  return {
    assetId: props.assetId,
    commentStr: commentStr.value,
    level: commentKind.value === 1 ? level.value : undefined,
    commentKind: commentKind.value,
    problemType: commentKind.value === 2 ? problemType.value : undefined,
    impactScope: commentKind.value === 2 ? impactScope.value : undefined,
  };
}
const submissionIdentity = computed(() =>
  JSON.stringify([owner.value, submissionData()]),
);
watch(
  submissionIdentity,
  (identity) => {
    if (submission?.identity !== identity) submission = undefined;
  },
  { flush: 'sync' },
);

async function load() {
  if (!alive || !canQuery.value) return;
  const ticket = commentGuard.next();
  loading.value = true;
  try {
    const result = await getMarketCommentPage({
      pageNo: 1,
      pageSize: 50,
      assetId: props.assetId,
    });
    if (!ticket.isCurrent()) return;
    list.value = result.list ?? [];
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

async function submit() {
  if (!alive || submitting.value || !canComment.value) return;
  if (!commentStr.value.trim()) {
    message.warning('请填写内容');
    return;
  }
  if (commentKind.value === 2 && !problemType.value.trim()) {
    message.warning('校核请选择问题类型');
    return;
  }
  const data = submissionData();
  const identity = submissionIdentity.value;
  if (data.commentKind === 2 && !submission) {
    const key = globalThis.crypto?.randomUUID?.();
    if (!key) {
      message.warning('当前浏览器无法生成安全请求编号，请使用 HTTPS 后重试。');
      return;
    }
    submission = { identity, key };
  }
  const request = {
    ...data,
    idempotentKey: data.commentKind === 2 ? submission?.key : undefined,
  };
  const current = ++generation;
  const requestedOwner = sessionScope.value;
  submitting.value = true;
  try {
    await createMarketComment(request);
    if (
      !alive ||
      current !== generation ||
      requestedOwner !== sessionScope.value
    )
      return;
    submission = undefined;
    // 请求期间新编辑的内容仍是下一次提交，不能被旧请求的成功回调清空。
    if (submissionIdentity.value === identity) {
      commentStr.value = '';
      problemType.value = '';
      impactScope.value = '';
      level.value = 5;
    }
    message.success(
      data.commentKind === 2 ? '校核已提交，办理进度以工单为准' : '评价已提交',
    );
    void load();
  } finally {
    if (alive && current === generation) submitting.value = false;
  }
}

async function reply(id: number | string) {
  if (!canReply.value) return;
  const text = replyMap.value[resourceIdKey(id)]?.trim();
  if (!text) {
    message.warning('请填写回复');
    return;
  }
  await replyMarketComment({ id, replyStr: text });
  replyMap.value[resourceIdKey(id)] = '';
  message.success('已回复（回复不等于工单已解决）');
  load();
}

let previousOwner = owner.value;
watch(
  sessionScope,
  () => {
    generation++;
    commentGuard.invalidate();
    list.value = [];
    loading.value = false;
    submitting.value = false;
    if (previousOwner !== owner.value) {
      previousOwner = owner.value;
      submission = undefined;
      commentStr.value = '';
      problemType.value = '';
      impactScope.value = '';
      replyMap.value = {};
    }
    void load();
  },
  { flush: 'sync' },
);

onMounted(load);
onUnmounted(() => {
  alive = false;
  generation++;
  commentGuard.invalidate();
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <Select
      v-if="canComment"
      v-model:value="commentKind"
      :options="[
        { label: '普通交流', value: 1 },
        { label: '结构化校核', value: 2 },
      ]"
    />
    <div v-if="canComment && commentKind === 1" class="flex items-center gap-2">
      <span class="text-sm">评分</span>
      <Rate v-model:value="level" />
    </div>
    <template v-else-if="canComment">
      <Input
        v-model:value="problemType"
        placeholder="问题类型，例如错数、漏数、口径不符"
      />
      <Input v-model:value="impactScope" placeholder="影响范围（可选）" />
    </template>
    <TextArea
      v-if="canComment"
      v-model:value="commentStr"
      :rows="3"
      placeholder="使用体验、数据质量或校核说明"
    />
    <div v-if="canComment">
      <Button type="primary" :loading="submitting" @click="submit">
        <template #icon><IconifyIcon icon="lucide:send" /></template>
        {{ commentKind === 2 ? '提交校核' : '发表评价' }}
      </Button>
    </div>
    <Spin :spinning="loading">
      <Empty v-if="!loading && !list.length" description="还没有评价或校核" />
      <div v-else class="flex flex-col gap-3">
        <div
          v-for="item in list"
          :key="resourceIdKey(item.id)"
          class="border-border rounded border p-3"
        >
          <div class="mb-2 flex items-center gap-2">
            <Rate v-if="item.commentKind !== 2" :value="item.level" disabled />
            <Tag v-else color="warning">校核</Tag>
            <span class="text-sm">{{ item.creatorName || '用户' }}</span>
            <span class="text-muted-foreground text-xs">{{
              formatMarketTime(item.createTime)
            }}</span>
          </div>
          <div>{{ item.commentStr }}</div>
          <div
            v-if="item.commentKind === 2"
            class="text-muted-foreground mt-1 text-xs"
          >
            类型 {{ item.problemType || '-' }} · 工单
            {{
              item.workOrderStatus || (item.workOrderId ? '已受理' : '待创建')
            }}
          </div>
          <div v-if="item.replyStr" class="text-muted-foreground mt-2 text-xs">
            回复（{{ item.replyUserName }}）：{{ item.replyStr }}
          </div>
          <div v-else-if="canReply" class="mt-2 flex gap-2">
            <Input
              v-model:value="replyMap[resourceIdKey(item.id)]"
              placeholder="资产方回复"
              size="small"
            />
            <Button size="small" @click="reply(item.id!)">
              <template #icon><IconifyIcon icon="lucide:reply" /></template>
              回复
            </Button>
          </div>
        </div>
      </div>
    </Spin>
  </div>
</template>
