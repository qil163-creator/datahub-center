<script setup lang="ts">
import type { SensitiveListApi } from '#/api/asset/sensitive';

import { onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDate } from '@vben/utils';

import { Button, Form, FormItem, Input, Tag } from 'antdv-next';

import { getSensitiveList } from '#/api/asset/sensitive';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { useSensitiveAccess } from '../use-sensitive-access';
import LevelBadge from './level-badge.vue';

defineProps<{ variant?: 'rule' }>();

const { owner, can } = useSensitiveAccess();
const detail = ref<SensitiveListApi.Record>();
const loading = ref(false);
const error = ref(false);
const guard = createLatestWins();
let opened = false;
const [Modal, modalApi] = useVbenModal({
  title: '脱敏清单详情',
  fullscreenButton: false,
  showConfirmButton: false,
  showCancelButton: false,
  async onOpenChange(value) {
    opened = value;
    guard.invalidate();
    detail.value = undefined;
    error.value = false;
    loading.value = false;
    if (!value || !can('query')) return;
    const data = modalApi.getData() as { id?: SensitiveListApi.Id };
    const id = parseResourceId(data?.id);
    if (!id) return;
    const ticket = guard.next();
    loading.value = true;
    try {
      const result = await getSensitiveList(id);
      if (ticket.isCurrent() && opened) detail.value = result;
    } catch {
      if (ticket.isCurrent()) error.value = true;
    } finally {
      if (ticket.isCurrent()) loading.value = false;
    }
  },
});
watch(
  owner,
  () => {
    opened = false;
    guard.invalidate();
    detail.value = undefined;
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  guard.invalidate();
  detail.value = undefined;
});
</script>

<template>
  <Modal
    :title="variant === 'rule' ? '敏感清单详情' : '脱敏清单详情'"
    :centered="false"
    :loading="loading"
    class="!top-[60px] !w-[800px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    content-class="!p-0"
  >
<Form
      v-if="detail"
      class="sensitive-detail"
      :colon="false"
      :label-col="{ style: { width: '110px' } }"
      :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
    >
      <div class="sensitive-detail__grid">
        <FormItem label="编号">
          <Input :value="detail.sourceRecordId || String(detail.id)" disabled />
        </FormItem>
        <FormItem label="资产名称">
          <Input :value="detail.assetName || '-'" disabled />
        </FormItem>
        <FormItem v-if="variant !== 'rule'" label="数据分类">
          <Input :value="detail.classificationName || '-'" disabled />
        </FormItem>
        <FormItem v-if="variant !== 'rule'" label="数据分级">
          <LevelBadge :name="detail.levelName" :color="detail.levelColor" />
        </FormItem>
        <FormItem label="表名">
          <Input :value="detail.tableName || '-'" disabled />
        </FormItem>
        <FormItem label="字段名">
          <Input :value="detail.columnName || '-'" disabled />
        </FormItem>
        <template v-if="variant === 'rule'">
          <FormItem label="数据分级">
            <Input :value="detail.levelName || '-'" disabled />
</FormItem><FormItem label="状态">
            <Input :value="detail.status === 0 ? '启用' : '禁用'" disabled />
          </FormItem>
        </template>
      </div>
      <FormItem v-if="variant !== 'rule'" label="状态">
        <Tag :bordered="false" :color="detail.status === 0 ? 'green' : 'red'">
          {{ detail.status === 0 ? '启用' : '禁用' }}
        </Tag>
      </FormItem>
      <FormItem label="描述">
        <Input.TextArea
          :value="
            (variant === 'rule'
              ? detail.assetDescription
              : detail.description) || '-'
          "
          :rows="3"
          disabled
        />
      </FormItem>
      <div class="sensitive-detail__grid">
        <FormItem label="创建人">
          <Input
            :value="detail.creatorName || detail.sourceCreatorName || '-'"
            disabled
          />
        </FormItem>
        <FormItem label="创建时间">
          <Input
            :value="formatDate(detail.createTime, 'YYYY-MM-DD HH:mm') || '-'"
            disabled
          />
        </FormItem>
        <FormItem label="更新人">
          <Input
            :value="detail.updaterName || detail.sourceUpdaterName || '-'"
            disabled
          />
        </FormItem>
        <FormItem label="更新时间">
          <Input
            :value="formatDate(detail.updateTime, 'YYYY-MM-DD HH:mm') || '-'"
            disabled
          />
        </FormItem>
      </div>
    </Form>
    <template #footer>
      <Button type="primary" ghost @click="modalApi.close()"> 关闭 </Button>
    </template>
  </Modal>
</template>

<style scoped>
.sensitive-detail {
  padding: 20px 40px;
}

.sensitive-detail__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.sensitive-detail :deep(.ant-form-item) {
  margin-bottom: 16px;
}

@media (width <= 600px) {
  .sensitive-detail__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .sensitive-detail {
    padding: 16px;
  }

  .sensitive-detail :deep(.ant-form-item-row) {
    display: block;
  }

  .sensitive-detail :deep(.ant-form-item-label) {
    width: 100% !important;
    text-align: left;
  }

  .sensitive-detail :deep(.ant-form-item-control) {
    flex: none !important;
    width: 100%;
  }
}
</style>
