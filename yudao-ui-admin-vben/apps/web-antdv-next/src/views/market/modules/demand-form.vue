<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';

import { reactive } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Checkbox,
  DatePicker,
  Input,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import {
  createMarketDemand,
  getMarketDemand,
  updateMarketDemand,
} from '#/api/asset/market';

import {
  DELIVERY_OPTIONS,
  DEMAND_TYPE_OPTIONS,
  URGENCY_OPTIONS,
} from '../demand/data';

defineOptions({ name: 'MarketDemandForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<MarketApi.Demand>({});

function resetModel() {
  Object.assign(model, {
    id: undefined,
    demandNo: undefined,
    title: undefined,
    demandType: undefined,
    description: undefined,
    purpose: undefined,
    expectedFields: undefined,
    expectedUpdateFreq: undefined,
    expectedDelivery: undefined,
    expectFinishTime: undefined,
    urgency: undefined,
    applyDeptId: undefined,
    applyDeptName: undefined,
    applyUserId: undefined,
    applyUserName: undefined,
    phone: undefined,
    email: undefined,
    status: undefined,
    publishTime: undefined,
    closeReason: undefined,
    acceptanceStatus: undefined,
    acceptedApplyId: undefined,
    acceptedTime: undefined,
    remark: undefined,
    createTime: undefined,
    matchCount: undefined,
    replyCount: undefined,
    matches: undefined,
    replies: undefined,
    publish: undefined,
  });
}

const [Modal, modalApi] = useVbenModal({
  onConfirm: submit,
  async onOpenChange(isOpen) {
    if (!isOpen) {
      resetModel();
      return;
    }
    const data = (modalApi.getData() ?? {}) as { id?: MarketApi.Id };
    resetModel();
    if (data.id) {
      Object.assign(model, (await getMarketDemand(data.id)) ?? {});
      modalApi.setState({ title: '编辑需求' });
    } else {
      Object.assign(model, {
        demandType: 1,
        expectedDelivery: 4,
        urgency: 2,
        publish: true,
      });
      modalApi.setState({ title: '提出数据需求' });
    }
  },
});

async function submit() {
  if (!model.title?.trim() || !model.description?.trim()) {
    message.warning('请填写标题和需求说明');
    return;
  }
  modalApi.lock();
  try {
    if (model.id) {
      await updateMarketDemand(model);
      message.success('已保存');
    } else {
      await createMarketDemand({ ...model, publish: model.publish !== false });
      message.success(
        model.publish === false ? '已保存草稿' : '已发布到需求大厅',
      );
    }
    emit('success');
    modalApi.close();
  } finally {
    modalApi.unlock();
  }
}
</script>

<template>
  <Modal class="w-[800px]">
    <div class="governance-modal-form governance-form-item-horizontal">
      <div class="form-item">
        <label class="form-item__label form-item__label--required">需求标题</label>
        <Input
          v-model:value="model.title"
          placeholder="例如：需要全省用水户月度汇总"
        />
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">需求类型</label>
          <Select
            v-model:value="model.demandType"
            class="w-full"
            :options="DEMAND_TYPE_OPTIONS"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">期望交付</label>
          <Select
            v-model:value="model.expectedDelivery"
            class="w-full"
            :options="DELIVERY_OPTIONS"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">紧急程度</label>
        <Select
          v-model:value="model.urgency"
          class="w-full"
          :options="URGENCY_OPTIONS"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">需求说明</label>
        <TextArea
          v-model:value="model.description"
          :rows="4"
          placeholder="业务背景、需要什么数据、覆盖范围"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">用途</label>
        <Input v-model:value="model.purpose" />
      </div>
      <div class="form-item">
        <label class="form-item__label">期望字段</label>
        <Input
          v-model:value="model.expectedFields"
          placeholder="字段或指标清单"
        />
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">更新频率</label>
          <Input
            v-model:value="model.expectedUpdateFreq"
            placeholder="日/周/月"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">期望完成时间</label>
          <DatePicker
            v-model:value="model.expectFinishTime"
            class="w-full"
            format="YYYY-MM-DD HH:mm:ss"
            show-time
            value-format="x"
          />
        </div>
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">电话</label>
          <Input v-model:value="model.phone" />
        </div>
        <div class="form-item">
          <label class="form-item__label">邮箱</label>
          <Input v-model:value="model.email" />
        </div>
      </div>
      <Checkbox v-if="!model.id" v-model:checked="model.publish">
        直接发布到需求大厅，供其他部门对接
      </Checkbox>
    </div>
  </Modal>
</template>

<style lang="scss" scoped>
@import '../../governance/common/custom-modal-form.css';
</style>
