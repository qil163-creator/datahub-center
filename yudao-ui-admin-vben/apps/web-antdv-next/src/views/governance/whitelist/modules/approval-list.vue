<script lang="ts" setup>
import type { SafetyWhitelistApi } from '#/api/governance/whitelist';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { Empty, Tag, Timeline, TimelineItem } from 'antdv-next';

import { getWhitelistApprovalList } from '#/api/governance/whitelist';

import {
  APPROVAL_ACTION_OPTIONS,
  approvalOptionColor,
  approvalOptionLabel,
} from '../data';

defineOptions({ name: 'SafetyWhitelistApprovalList' });

const records = ref<SafetyWhitelistApi.WhitelistApproval[]>([]);
const whitelistName = ref('');

function actionDotColor(action?: string) {
  switch (action) {
    case 'APPROVE': {
      return 'green';
    }
    case 'REJECT': {
      return 'red';
    }
    default: {
      return 'blue';
    }
  }
}

const [ModalComponent, modalApi] = useVbenModal<SafetyWhitelistApi.Whitelist>({
  showCancelButton: false,
  showConfirmButton: false,
  class: 'w-[960px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      records.value = [];
      return;
    }
    const data = modalApi.getData();
    if (!data?.id) {
      return;
    }
    whitelistName.value = data.name ?? '';
    records.value = (await getWhitelistApprovalList(data.id)) ?? [];
  },
});
</script>

<template>
  <ModalComponent :title="`审批记录 - ${whitelistName}`">
    <div class="px-3 py-2">
      <Timeline v-if="records.length > 0">
        <TimelineItem
          v-for="record in records"
          :key="record.id"
          :color="actionDotColor(record.action)"
        >
          <div class="flex items-center gap-2">
            <Tag
              :color="
                approvalOptionColor(APPROVAL_ACTION_OPTIONS, record.action)
              "
            >
              {{ approvalOptionLabel(APPROVAL_ACTION_OPTIONS, record.action) }}
            </Tag>
            <span class="text-xs text-gray-500">
              {{ record.creator || '-' }} ·
              {{ formatDateTime(record.createTime) || '-' }}
            </span>
          </div>
          <div v-if="record.opinion" class="mt-1 text-sm">
            {{ record.opinion }}
          </div>
        </TimelineItem>
      </Timeline>
      <Empty
        v-else
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无审批记录"
      />
    </div>
  </ModalComponent>
</template>
