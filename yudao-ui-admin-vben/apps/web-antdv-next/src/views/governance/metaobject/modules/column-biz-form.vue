<script lang="ts" setup>
import type { GovernanceMetaObjectApi } from '#/api/governance/metaobject';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  message,
  RadioGroup,
  TextArea,
} from 'antdv-next';

import {
  getMetaColumnDetail,
  updateMetaColumnBiz,
} from '#/api/governance/metaobject';
import { useObjectRequest } from '#/hooks/use-object-request';
import PublishedStandardPicker from '#/views/governance/components/published-standard-picker.vue';

import { metadataIcon } from '../../metadata-common/icons';

defineOptions({ name: 'GovernanceMetaObjectColumnBizForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<GovernanceMetaObjectApi.ColumnBizUpdate>({
  id: 0,
  revision: 0,
});
const columnName = ref('');
const physical = ref<GovernanceMetaObjectApi.MetaColumn>();
const physicalFields = computed(() => [
  ['字段名称', physical.value?.columnName],
  ['字段注释', physical.value?.sourceComment],
  ['字段类型', physical.value?.dataType],
  ['字段长度', physical.value?.dataLength],
  ['字段精度', physical.value?.dataPrecision],
  ['字段小数位', physical.value?.dataScale],
  ['是否必填', physical.value?.nullableFlag === 0 ? '是' : '否'],
  ['默认值', physical.value?.defaultValue],
  ['是否主键', physical.value?.pkFlag === 1 ? '是' : '否'],
  ['是否外键', physical.value?.fkFlag === 1 ? '是' : '否'],
]);
const bindingLabel = ref('');
const identity = ref('closed');
const ready = ref(false);
const loadFailed = ref(false);
const picker = ref<InstanceType<typeof PublishedStandardPicker>>();
const access = useAccessStore();
const request = useObjectRequest(
  () => identity.value,
  () => {
    ready.value = false;
    loadFailed.value = false;
    columnName.value = '';
    physical.value = undefined;
    bindingLabel.value = '';
    picker.value?.close();
  },
);

const FLAG_OPTIONS = [
  { label: '否', value: 0 },
  { label: '是', value: 1 },
];

function handlePicked(standard: GovernanceStandardElementApi.StandardElement) {
  model.standardElementId = standard.id;
  bindingLabel.value = `${standard.name}（${standard.code}）· 保存时固定当前发布版本`;
}

function clearBinding() {
  model.standardElementId = undefined;
  bindingLabel.value = '';
}

async function loadDetail(id: GovernanceMetaObjectApi.Id) {
  const current = request.begin();
  ready.value = false;
  loadFailed.value = false;
  modalApi.setState({ loading: true, confirmDisabled: true });
  try {
    const data = await getMetaColumnDetail(id);
    if (!current()) return;
    columnName.value = data.columnName ?? '';
    physical.value = data;
    bindingLabel.value = data.standardElementId
      ? `${data.standardElementName ?? `标准#${data.standardElementId}`}（${data.standardElementCode ?? '-'}）`
      : '';
    Object.assign(model, {
      id: data.id!,
      revision: data.revision!,
      customComment: data.customComment,
      businessName: data.businessName,
      businessDefinition: data.businessDefinition,
      measuringUnit: data.measuringUnit,
      standardElementId: data.standardElementId,
      safetyLevelId: data.safetyLevelId,
      sensitiveFlag: data.sensitiveFlag ?? 0,
      remark: data.remark,
    });
    ready.value = data.revision !== undefined;
    loadFailed.value = !ready.value;
  } catch {
    if (current()) loadFailed.value = true;
  } finally {
    if (current())
      modalApi.setState({ loading: false, confirmDisabled: !ready.value });
  }
}

const getTitle = computed(() => `修改字段元数据 - ${columnName.value}`);

const [ModalComponent, modalApi] = useVbenModal({
  class: 'metadata-modal w-[1200px] max-w-[calc(100vw-32px)]',
  headerClass: 'metadata-modal__header',
  contentClass: 'metadata-modal__body',
  footerClass: 'metadata-modal__footer',
  async onConfirm() {
    if (!ready.value) return;
    const current = request.begin();
    modalApi.lock();
    try {
      await updateMetaColumnBiz({ ...model });
      if (!current()) return;
      message.success('保存成功（已记录手工变更明细）');
      emit('success');
      await modalApi.close();
    } catch {
      if (current()) {
        ready.value = false;
        loadFailed.value = true;
        modalApi.setState({ confirmDisabled: true });
      }
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      identity.value = 'closed';
      picker.value?.close();
      modalApi.unlock();
      return;
    }
    const data = modalApi.getData() as GovernanceMetaObjectApi.MetaColumn;
    if (data?.id === undefined) return;
    identity.value = String(data.id);
    model.id = data.id;
    await loadDetail(data.id);
  },
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
</script>

<template>
  <ModalComponent :title="getTitle" class="w-[1200px] max-w-[calc(100vw-32px)]">
    <PublishedStandardPicker
      ref="picker"
      class="governance-standard-picker"
      @picked="handlePicked"
    />
    <Alert
      v-if="loadFailed"
      class="mb-3"
      message="字段读取失败或保存发生冲突，请重新加载后编辑。"
      show-icon
      type="error"
    >
      <template #action>
        <Button size="small" @click="loadDetail(model.id)">
          <template #icon>
            <IconifyIcon :icon="metadataIcon('refresh')" />
          </template>
          重新加载
        </Button>
      </template>
    </Alert>
    <div
      class="column-biz-form governance-modal-form governance-form-item-horizontal"
    >
      <div class="column-physical-grid">
        <div v-for="(item, index) in physicalFields" :key="index">
          <label>{{ item[0] }}</label
          ><Input :value="String(item[1] ?? '-')" disabled />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">平台注释</label>
        <Input v-model:value="model.customComment" :maxlength="500" />
        <div class="form-item__tip">不覆盖源库注释，展示优先</div>
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">业务名称/中文名称</label>
          <Input v-model:value="model.businessName" :maxlength="200" />
        </div>
        <div class="form-item">
          <label class="form-item__label">度量单位</label>
          <Input v-model:value="model.measuringUnit" :maxlength="50" />
        </div>
        <div class="form-item">
          <label class="form-item__label">标准数据元</label>
          <div class="mb-1 text-sm">{{ bindingLabel || '尚未关联标准' }}</div>
          <div class="flex gap-2">
            <Button :disabled="!ready" @click="picker?.open()">
              <template #icon><IconifyIcon icon="lucide:link" /></template>
              选择已发布标准
            </Button>
            <Button
              :disabled="!ready || !model.standardElementId"
              danger
              @click="clearBinding"
            >
              <template #icon><IconifyIcon icon="lucide:unlink" /></template>
              解除关联
            </Button>
          </div>
        </div>
        <div class="form-item">
          <label class="form-item__label">敏感/加密标注</label>
          <RadioGroup
            v-model:value="model.sensitiveFlag"
            :options="FLAG_OPTIONS"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">业务定义</label>
        <TextArea
          v-model:value="model.businessDefinition"
          :maxlength="1000"
          :rows="2"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">备注</label>
        <TextArea v-model:value="model.remark" :maxlength="500" :rows="2" />
      </div>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';

.governance-standard-picker :deep(.ant-table-thead > tr > th),
.governance-standard-picker :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.column-physical-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 32px;
  margin-bottom: 20px;
}

.column-physical-grid > div {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
}

.column-physical-grid label {
  text-align: right;
}

@media (max-width: 900px) {
  .column-physical-grid {
    grid-template-columns: 1fr;
  }
}
</style>
