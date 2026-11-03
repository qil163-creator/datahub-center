import { getDatabaseSimpleList } from '#/api/dataforge/database';

/**
 * 节点编辑器可选择的数据源：平台只有一个研发工作空间，所有启用的数据源均可选择；
 * 读写权限由后端在运行前按执行身份逐节点校验。
 */
export function useWorkspaceDatabases() {
  return () => getDatabaseSimpleList();
}
