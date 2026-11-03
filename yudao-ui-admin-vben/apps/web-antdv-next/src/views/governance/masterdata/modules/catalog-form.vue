<script lang="ts" setup>
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { handleTree } from '@vben/utils';

import {
  Input,
  InputNumber,
  message,
  RadioGroup,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createMasterCatalog,
  updateMasterCatalog,
} from '#/api/governance/masterdata';
import { getDeptList } from '#/api/system/dept';

defineOptions({ name: 'GovernanceMasterCatalogForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const isCreate = ref(true);
const model = reactive<GovernanceMasterDataApi.Catalog>({});
const parentPath = ref('');
const deptTree = ref<any[]>([]);

const TYPE_OPTIONS = [
  { label: '普通目录', value: 0 },
  { label: '主数据', value: 1 },
];

const getTitle = computed(() => {
  if (!isCreate.value) {
    return '编辑节点';
  }
  return parentPath.value ? '添加子节点' : '添加根目录';
});

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[800px]',
  async onConfirm() {
    if (!model.name?.trim()) {
      message.warning('请填写节点名称');
      return;
    }
    modalApi.lock();
    try {
      await (isCreate.value
        ? createMasterCatalog(model)
        : updateMasterCatalog(model));
      message.success(isCreate.value ? '节点已创建' : '节点已更新');
      await modalApi.close();
      emit('success');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData() as {
      catalog: GovernanceMasterDataApi.Catalog | null;
      parent: GovernanceMasterDataApi.Catalog | null;
    };
    Object.assign(model, {
      id: undefined,
      parentId: undefined,
      type: undefined,
      name: undefined,
      orderNo: undefined,
      description: undefined,
      path: undefined,
      orgId: undefined,
      modelStatus: undefined,
      createTime: undefined,
      children: undefined,
    });
    if (data?.catalog) {
      isCreate.value = false;
      parentPath.value = '';
      Object.assign(model, data.catalog, { children: undefined });
    } else {
      isCreate.value = true;
      parentPath.value = data?.parent?.path ?? '';
      Object.assign(model, {
        parentId: data?.parent?.id ?? 0,
        // 根节点固定为普通目录，子节点默认主数据
        type: data?.parent ? 1 : 0,
        name: '',
        orderNo: 0,
        orgId: data?.parent?.orgId,
      });
    }
    if (deptTree.value.length === 0) {
      deptTree.value = handleTree(await getDeptList());
    }
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div
      class="catalog-form governance-modal-form governance-form-item-horizontal"
    >
      <div v-if="parentPath" class="form-item">
        <label class="form-item__label">上级目录</label>
        <Input :value="parentPath" disabled />
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">名称</label>
        <Input v-model:value="model.name" allow-clear placeholder="节点名称" />
      </div>
      <div v-if="isCreate && model.parentId !== 0" class="form-item">
        <label class="form-item__label">类型</label>
        <RadioGroup v-model:value="model.type" :options="TYPE_OPTIONS" />
        <div class="form-item__tip">
          普通目录用于组织层级；主数据节点下设计模型（创建后类型不可修改）
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">所属机构</label>
        <TreeSelect
          v-model:value="model.orgId"
          :field-names="{ children: 'children', label: 'name', value: 'id' }"
          :tree-data="deptTree"
          allow-clear
          class="w-full"
          placeholder="请选择所属机构"
          show-search
          tree-default-expand-all
          tree-node-filter-prop="name"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">排序号</label>
        <InputNumber
          v-model:value="model.orderNo"
          :max="9999"
          :min="0"
          :precision="0"
          :step="1"
          class="category-sort-stepper !w-40"
          placeholder="排序值"
        />
        <div class="form-item__tip">数字越小越靠前</div>
      </div>
      <div class="form-item">
        <label class="form-item__label">描述</label>
        <TextArea v-model:value="model.description" :rows="2" />
      </div>
    </div>
  </ModalComponent>
</template>

<style scoped>
@import '../../common/custom-modal-form.css';
</style>

<style>
.category-sort-stepper .ant-input-number-handler-wrap {
  width: 28px;
  opacity: 1 !important;
}

.category-sort-stepper
  .ant-input-number-handler
  .ant-input-number-handler-up-inner,
.category-sort-stepper
  .ant-input-number-handler
  .ant-input-number-handler-down-inner {
  font-size: 10px;
}

.category-sort-stepper .ant-input-number-input {
  padding-right: 32px;
  text-align: center;
}
</style>
