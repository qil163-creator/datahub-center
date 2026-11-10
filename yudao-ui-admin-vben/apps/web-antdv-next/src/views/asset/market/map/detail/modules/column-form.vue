<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Radio,
  RadioGroup,
  Select,
} from 'antdv-next';

import {
  getDataAssetColumn,
  updateDataAssetColumn,
} from '#/api/asset/dataasset';
import { getSimpleLevelList } from '#/api/governance/level';
import { useObjectRequest } from '#/hooks/use-object-request';
import PublishedStandardPicker from '#/views/governance/components/published-standard-picker.vue';

defineOptions({ name: 'AssetMarketMapColumnForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

interface FormState extends DataAssetApi.ColumnUpdate {
  columnName?: string;
  dataType?: string;
  dataLength?: number;
  dataScale?: number;
  nullableFlag?: number;
  pkFlag?: number;
  relateElement: boolean;
}

const form = reactive<FormState>({
  assetId: 0,
  id: 0,
  relateElement: false,
  revision: 0,
});
const levelOptions = ref<{ label: string; value: string }[]>([]);
const levelsReady = ref(false);
const ready = ref(false);
const loadFailed = ref(false);
const saveConflict = ref(false);
const identity = ref('closed');
const bindingLabel = ref('');
const picker = ref<InstanceType<typeof PublishedStandardPicker>>();
const access = useAccessStore();
const request = useObjectRequest(
  () => identity.value,
  () => {
    ready.value = false;
    loadFailed.value = false;
  saveConflict.value = false;
    levelsReady.value = false;
    levelOptions.value = [];
    bindingLabel.value = '';
    form.columnName = '';
    picker.value?.close();
  },
);

function handlePicked(standard: GovernanceStandardElementApi.StandardElement) {
  form.standardElementId = standard.id;
  bindingLabel.value = `${standard.name}（${standard.code}）· 保存时固定当前发布版本`;
}

async function loadDetail(assetId: DataAssetApi.Id, id: DataAssetApi.Id) {
  const current = request.begin();
  ready.value = false;
  loadFailed.value = false;
  saveConflict.value = false;
  levelsReady.value = false;
  modalApi.setState({ loading: true, confirmDisabled: true });
  try {
    const column = await getDataAssetColumn(assetId, id);
    if (!current()) return;
    Object.assign(form, {
      assetId,
      id: column.id!,
      revision: column.revision!,
      columnName: column.columnName,
      dataType: column.dataType,
      dataLength: column.dataLength,
      dataScale: column.dataScale,
      nullableFlag: column.nullableFlag,
      pkFlag: column.pkFlag,
      businessName: column.businessName,
      customComment: column.customComment,
      businessDefinition: column.businessDefinition,
      measuringUnit: column.measuringUnit,
      standardElementId: column.standardElementId,
      safetyLevelId:
        column.safetyLevelId === undefined || column.safetyLevelId === null
          ? undefined
          : String(column.safetyLevelId),
      sensitiveFlag: column.sensitiveFlag ?? 0,
      remark: column.remark,
      relateElement:
        column.standardElementId !== undefined &&
        column.standardElementId !== null,
    });
    bindingLabel.value = column.standardElementId
      ? `${column.standardElementName ?? `标准#${column.standardElementId}`}（${column.standardElementCode ?? '-'}）`
      : '';
    ready.value = column.revision !== undefined;
    loadFailed.value = !ready.value;
    try {
      const levels = await getSimpleLevelList();
      if (!current()) return;
      levelOptions.value = levels.map((item) => ({
        label: item.safetyName || item.safetyLevel || `等级#${item.id}`,
        value: String(item.id),
      }));
      if (
        form.safetyLevelId !== undefined &&
        !levelOptions.value.some(
          (item) => item.value === String(form.safetyLevelId),
        )
      ) {
        levelOptions.value.push({
          label: `当前等级#${form.safetyLevelId}`,
          value: String(form.safetyLevelId),
        });
      }
      levelsReady.value = true;
    } catch {
      // 安全等级选项失败时保留当前等级，禁止改动关联。
    }
  } catch {
    if (current()) loadFailed.value = true;
  } finally {
    if (current())
      modalApi.setState({ loading: false, confirmDisabled: !ready.value });
  }
}

watch(
  () => form.relateElement,
  (value) => {
    if (!value) form.standardElementId = undefined;
  },
  { flush: 'sync' },
);

const title = computed(() =>
  form.columnName
    ? `修改数据资产字段 - ${form.columnName}`
    : '修改数据资产字段',
);

const [Modal, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!ready.value) return;
    if (form.relateElement && form.standardElementId === undefined) {
      message.warning('请选择已发布标准，或将关联数据元设为否。');
      return;
    }
    const current = request.begin();
    modalApi.lock();
    try {
      await updateDataAssetColumn({
        assetId: form.assetId,
        id: form.id,
        revision: form.revision,
        businessName: form.businessName,
        customComment: form.customComment,
        businessDefinition: form.businessDefinition,
        measuringUnit: form.measuringUnit,
        standardElementId: form.relateElement
          ? form.standardElementId
          : undefined,
        safetyLevelId: form.safetyLevelId,
        sensitiveFlag: form.sensitiveFlag,
        remark: form.remark,
      });
      if (!current()) return;
      message.success('保存成功；已发布资产继续使用原冻结快照。');
      await modalApi.close();
      emit('success');
    } catch {
      if (current()) {
        ready.value = false;
        loadFailed.value = true;
        saveConflict.value = true;
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
    const data = modalApi.getData() as {
      assetId: DataAssetApi.Id;
      column: DataAssetApi.MetadataColumn;
    };
    if (data?.assetId === undefined || data.column?.id === undefined) return;
    identity.value = `${data.assetId}/${data.column.id}`;
    form.assetId = data.assetId;
    form.id = data.column.id;
    await loadDetail(data.assetId, data.column.id);
  },
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    :title="title"
    class="!top-[60px] !w-[800px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
  >
    <PublishedStandardPicker ref="picker" @picked="handlePicked" />
    <Alert
      v-if="saveConflict"
      class="mb-3"
      message="字段保存失败或发生冲突，请重新加载核对后编辑。"
      show-icon
      type="error"
    >
      <template #action>
        <Button size="small" @click="loadDetail(form.assetId, form.id)">
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          重新加载
        </Button>
      </template>
    </Alert>

    <Form
      :label-col="{ style: { width: '110px' } }"
      :wrapper-col="{ style: { flex: '1 1 0' } }"
      class="column-form mx-2"
    >
      <div class="grid grid-cols-2">
        <FormItem label="中文名称">
          <Input
            v-model:value="form.businessName"
            :maxlength="200"
            placeholder="请输入字段中文名称"
          />
        </FormItem>
        <FormItem label="英文名称">
          <Input :value="form.columnName" disabled />
        </FormItem>
        <FormItem label="字段类型">
          <Input :value="form.dataType" disabled />
        </FormItem>
        <FormItem label="字段长度">
          <Input :value="form.dataLength ?? '-'" disabled />
        </FormItem>
        <FormItem label="小数位">
          <InputNumber :value="form.dataScale ?? 0" class="w-full" disabled />
        </FormItem>
        <FormItem label="是否可空">
          <RadioGroup :value="form.nullableFlag" disabled>
            <Radio :value="1">是</Radio>
            <Radio :value="0">否</Radio>
          </RadioGroup>
        </FormItem>
      </div>
      <FormItem
        :label-col="{ span: 3 }"
        :wrapper-col="{ span: 20 }"
        label="描述"
      >
        <Input.TextArea
          v-model:value="form.customComment"
          :maxlength="256"
          :rows="3"
          placeholder="请输入描述"
          show-count
        />
      </FormItem>
      <div class="grid grid-cols-2">
        <FormItem label="是否主键">
          <RadioGroup :value="form.pkFlag" disabled>
            <Radio :value="1">是</Radio>
            <Radio :value="0">否</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="数据分类">
          <Select
            v-model:value="form.safetyLevelId"
            :disabled="!levelsReady || !ready"
            :options="levelOptions"
            allow-clear
            placeholder="请选择安全等级"
          />
        </FormItem>
        <FormItem label="关联代码表">
          <Input value="未关联" disabled />
        </FormItem>
        <FormItem label="选择代码表"><Input value="-" disabled /></FormItem>
        <FormItem label="关联数据元">
          <RadioGroup v-model:value="form.relateElement" :disabled="!ready">
            <Radio :value="true">是</Radio>
            <Radio :value="false">否</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="选择数据元">
          <div class="mb-1 text-sm">
            {{
              form.relateElement ? bindingLabel || '尚未选择标准' : '未关联标准'
            }}
          </div>
          <Button
            :disabled="!ready || !form.relateElement"
            @click="picker?.open()"
          >
            选择已发布标准
          </Button>
        </FormItem>
      </div>
      <details class="column-form__extra">
        <summary>更多业务属性</summary>
        <p class="mb-3 text-xs text-gray-500">
          此处维护元数据业务属性，已发布资产继续引用原定版快照。
        </p>
        <FormItem label="业务定义">
          <Input.TextArea
            v-model:value="form.businessDefinition"
            :maxlength="1000"
            :rows="2"
          />
        </FormItem>
        <div class="grid grid-cols-2">
          <FormItem label="敏感字段">
            <RadioGroup v-model:value="form.sensitiveFlag">
              <Radio :value="1">是</Radio>
              <Radio :value="0">否</Radio>
            </RadioGroup>
          </FormItem>
          <FormItem label="计量单位">
            <Input
              v-model:value="form.measuringUnit"
              :maxlength="50"
              placeholder="如：米 / 元 / 立方米"
            />
          </FormItem>
        </div>
        <FormItem
          :label-col="{ span: 3 }"
          :wrapper-col="{ span: 20 }"
          label="备注"
        >
          <Input.TextArea
            v-model:value="form.remark"
            :maxlength="500"
            :rows="2"
            placeholder="请输入备注"
            show-count
          />
        </FormItem>
      </details>
    </Form>
  </Modal>
</template>

<style scoped>
.column-form :deep(.ant-form-item) {
  margin-bottom: 14px;
}

.column-form__extra summary {
  margin: 12px 0;
  color: hsl(var(--primary));
  cursor: pointer;
}

@media (width <= 600px) {
  .column-form :deep(.grid) {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
