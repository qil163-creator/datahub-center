<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { Descriptions, DescriptionsItem, Tag } from 'antdv-next';

import { formatCount } from '../../data';

defineOptions({ name: 'AssetMarketMapOverviewTab' });
defineProps<{
  asset: DataAssetApi.DataAsset;
  importedSnapshot?: ImportedAssetApi.Snapshot;
}>();
</script>

<template>
  <div class="overview-tab">
    <Descriptions :column="2" bordered size="small">
      <DescriptionsItem label="数据连接名称">
        {{ asset.datasourceName || importedSnapshot?.sourceName || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="数据连接类型">
        {{ asset.datasourceType || importedSnapshot?.sourceType || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="数据连接IP">
        {{ asset.datasourceHost || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="行数">
        {{ formatCount(asset.rowCount ?? importedSnapshot?.sourceDataCount) }}
      </DescriptionsItem>
      <DescriptionsItem label="列数">
        {{ asset.fieldCount ?? '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="资产表类型">
        {{
          importedSnapshot?.fileInfo?.tableType ||
          asset.registrationConfig?.tableType ||
          asset.layer ||
          asset.tableType ||
          '-'
        }}
      </DescriptionsItem>
      <DescriptionsItem label="数据标签">
        <Tag
          v-for="tag in asset.tags"
          :key="tag.id"
          :bordered="false"
          color="blue"
        >
          {{ tag.name }}
        </Tag>
        <span v-if="!asset.tags?.length">-</span>
      </DescriptionsItem>
      <DescriptionsItem label="创建人">
        {{ asset.creatorName || asset.creator || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="创建人联系方式">-</DescriptionsItem>
      <DescriptionsItem label="更新人">
        {{ asset.updaterName || asset.updater || '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="更新人联系方式">-</DescriptionsItem>
      <DescriptionsItem v-if="asset.assetType === 3" label="文件名称">
        {{ asset.fileName || '-' }}
      </DescriptionsItem>
      <DescriptionsItem v-if="asset.assetType === 3" label="文件类型">
        {{ asset.fileType || '-' }}
      </DescriptionsItem>
      <DescriptionsItem v-if="asset.assetType === 3" label="文件路径">
        {{ asset.filePath || importedSnapshot?.fileInfo?.filePath || '-' }}
      </DescriptionsItem>
    </Descriptions>
  </div>
</template>

<style scoped>
.overview-tab :deep(.ant-descriptions-item-label) {
  width: 160px;
}

.overview-tab :deep(.ant-descriptions-item-content) {
  width: 40%;
}
</style>
