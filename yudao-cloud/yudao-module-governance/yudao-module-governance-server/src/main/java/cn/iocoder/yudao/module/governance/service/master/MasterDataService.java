package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageRespVO;

import java.util.List;
import java.util.Map;

/**
 * 主数据记录维护 Service 接口
 *
 * srt-cloud 未提供主数据记录维护能力，本实现参考其数据填报模块的动态表读写补齐：
 * 表名与字段名一律取服务端模型元数据，值全部走 JDBC 参数绑定
 *
 * @author Li
 */
public interface MasterDataService {

    /**
     * 分页查询主数据记录（模型须已发布）
     */
    MasterDataPageRespVO getMasterDataPage(MasterDataPageReqVO pageReqVO);

    /** 按完整主键读取原行；空元素表示不存在，不执行任何写入。 */
    List<Map<String, Object>> snapshotRows(Long modelId, List<Map<String, Object>> rows);

    /** 校验动态记录的字段、类型、主键、长度和批次边界。 */
    void validateRows(Long modelId, List<Map<String, Object>> rows, String operation);

    /** 按模型类型规范快照，精确小数和长整数仍为字符串。 */
    List<Map<String, Object>> normalizeRows(Long modelId, List<Map<String, Object>> rows, String operation);

    /** 委托 DB Switch，在外部数据库事务中按原快照进行整批原子条件写。 */
    cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult applyGuardedChange(
            Long modelId, String operation, List<Map<String, Object>> before, List<Map<String, Object>> rows);

}
