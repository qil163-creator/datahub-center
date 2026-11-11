<script lang="ts" setup>
import type { SystemNoticeApi } from '#/api/system/notice';

import { ref } from 'vue';

import { formatDate } from '@vben/utils';

import { Card, Empty, Modal, Skeleton, Tag } from 'antdv-next';

/** 系统公告（system_notice，仅展示开启状态） */
defineOptions({ name: 'WorkspaceNoticePanel' });

defineProps<{
  loading: boolean;
  notices: SystemNoticeApi.Notice[];
}>();

/** 公告类型：1-通知 2-公告（字典 system_notice_type） */
const NOTICE_TYPE: Record<number, { color: string; label: string }> = {
  1: { color: 'blue', label: '通知' },
  2: { color: 'green', label: '公告' },
};

const current = ref<SystemNoticeApi.Notice>();
const open = ref(false);

function show(notice: SystemNoticeApi.Notice) {
  current.value = notice;
  open.value = true;
}
</script>

<template>
  <Card title="系统公告">
    <Skeleton :loading="loading" active :paragraph="{ rows: 4 }">
      <Empty
        v-if="notices.length === 0"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        class="!my-2"
        description="暂无公告"
      />
      <ul v-else class="divide-border -my-1 divide-y">
        <li
          v-for="notice in notices"
          :key="notice.id"
          class="hover:bg-accent/60 -mx-2 flex cursor-pointer items-center gap-3 rounded-md px-2 py-2.5"
          @click="show(notice)"
        >
          <Tag
            :color="NOTICE_TYPE[notice.type]?.color ?? 'default'"
            class="!mr-0 shrink-0"
          >
            {{ NOTICE_TYPE[notice.type]?.label ?? '公告' }}
          </Tag>
          <span class="min-w-0 flex-1 truncate text-sm">{{
            notice.title
          }}</span>
          <span class="text-muted-foreground shrink-0 text-xs tabular-nums">
            {{ formatDate(notice.createTime, 'MM-DD') }}
          </span>
        </li>
      </ul>
    </Skeleton>

    <Modal
      v-model:open="open"
      :footer="null"
      :title="current?.title"
      :width="640"
      destroy-on-hidden
    >
      <div class="text-muted-foreground mb-3 text-xs">
        {{ formatDate(current?.createTime, 'YYYY-MM-DD HH:mm') }}
      </div>
      <div
        class="notice-content"
        v-dompurify-html="current?.content ?? ''"
      ></div>
    </Modal>
  </Card>
</template>

<style scoped>
.notice-content :deep(img) {
  max-width: 100%;
}
</style>
