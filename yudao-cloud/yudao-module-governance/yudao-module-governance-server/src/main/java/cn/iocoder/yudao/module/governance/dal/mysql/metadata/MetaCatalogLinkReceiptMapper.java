package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkCommandDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MetaCatalogLinkReceiptMapper {

    /**
     * 判断目标元数据对象是否存在受管理的目录关联。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM dh_governance_catalog_link_source WHERE table_id=#{id})")
    boolean managed(@Param("id") Long id);

    /**
     * 按指定条件读取并锁定元数据目录关联回执记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_governance_meta_table WHERE id=#{id} AND deleted=0 FOR UPDATE")
    MetaTableDO lockTable(@Param("id") long id);

    /**
     * 按操作标识读取目录关联回执。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT operation_id,request_hash,source_revision,outcome,reason FROM dh_governance_catalog_link_receipt WHERE operation_id=#{id}")
    MetadataCatalogLinkResultDTO receipt(@Param("id") String id);

    /**
     * 校验元数据目录关联回执的预期修订号。
     */
    @Select("SELECT source_revision FROM dh_governance_catalog_link_source WHERE source_key=#{key} AND table_id=#{tableId}")
    Long revision(@Param("key") String key, @Param("tableId") long tableId);

    /**
     * 按业务条件新增元数据目录关联回执的推进状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_catalog_link_source(source_key,table_id,source_revision)
            VALUES(#{command.sourceKey},#{command.tableId},#{command.sourceRevision})
            ON CONFLICT(source_key,table_id) DO UPDATE SET source_revision=EXCLUDED.source_revision
            WHERE dh_governance_catalog_link_source.source_revision<EXCLUDED.source_revision
            """)
    int advance(@Param("command") MetadataCatalogLinkCommandDTO command);

    /**
     * 保存目录关联操作回执，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_catalog_link_receipt
            (operation_id,source_key,table_id,source_revision,linked,request_hash,outcome,reason)
            VALUES(#{command.operationId},#{command.sourceKey},#{command.tableId},
            #{command.sourceRevision},#{command.linked},#{result.requestHash},#{result.outcome},#{result.reason})
            """)
    int insert(@Param("command") MetadataCatalogLinkCommandDTO command,
            @Param("result") MetadataCatalogLinkResultDTO result);
}
