package cn.iocoder.yudao.module.governance.dal.mysql.lineage;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.lineage.vo.LineageSourcePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 数据血缘-上报来源 Mapper。
 */
@Mapper
public interface LineageSourceMapper extends BaseMapperX<LineageSourceDO> {

    /**
     * 确保来源行存在；已存在时不做任何修改。返回 1 表示本事务新建、0 表示已存在。
     * 新建行的修订号为 -1、解析器版本为 0，保证任何合法上报都比它新，并由同一事务随后覆盖。
     */
    @Insert("""
            INSERT INTO dh_governance_lineage_source
              (source_type, source_id, source_name, status, creator, updater)
            VALUES (#{sourceType}, #{sourceId}, #{sourceName}, #{status}, #{actor}, #{actor})
            ON CONFLICT (source_type, source_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId,
            @Param("sourceName") String sourceName, @Param("status") String status, @Param("actor") String actor);

    /**
     * 在当前事务内锁定来源行，串行化同一来源的并发上报。
     */
    default LineageSourceDO selectByKeyForUpdate(String sourceType, Long sourceId) {
        return selectOne(new LambdaQueryWrapperX<LineageSourceDO>()
                .eq(LineageSourceDO::getSourceType, sourceType)
                .eq(LineageSourceDO::getSourceId, sourceId)
                .last("FOR UPDATE"));
    }

    /**
     * 上报来源分页；关键字匹配来源名称，纯数字时同时匹配来源编号。
     */
    default PageResult<LineageSourceDO> selectPage(LineageSourcePageReqVO reqVO) {
        LambdaQueryWrapperX<LineageSourceDO> query = new LambdaQueryWrapperX<LineageSourceDO>()
                .eqIfPresent(LineageSourceDO::getSourceType, reqVO.getSourceType())
                .eqIfPresent(LineageSourceDO::getStatus, reqVO.getStatus());
        String keyword = StrUtil.trim(reqVO.getKeyword());
        if (StrUtil.isNotEmpty(keyword)) {
            Long numeric = parseLong(keyword);
            query.and(q -> {
                q.like(LineageSourceDO::getSourceName, keyword);
                if (numeric != null) {
                    q.or().eq(LineageSourceDO::getSourceId, numeric);
                }
            });
        }
        query.orderByDesc(LineageSourceDO::getReportedTime).orderByDesc(LineageSourceDO::getId);
        return selectPage(reqVO, query);
    }

    private static Long parseLong(String value) {
        if (value.length() > 18 || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(value);
    }

}
