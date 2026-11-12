package cn.iocoder.yudao.module.governance.service.profile;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_TABLE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_DATASOURCE_MISSING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_NO_COLUMNS;

/**
 * 读取探查目标：元数据表、所属库与有效字段。存在性判定与元数据表详情一致（未删除即可见，平台内全可见）。
 *
 * @author Li
 */
@Component
public class ProfileTargetLoader {

    /** 单次探查最多覆盖的字段数（按字段序号取前 N 个），超出部分不探查 */
    public static final int MAX_PROFILE_COLUMNS = 300;

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaDbMapper dbMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    /**
     * 探查目标。
     *
     * @param table   元数据表
     * @param db      所属库
     * @param columns 按字段序号排序的有效字段（至多 {@link #MAX_PROFILE_COLUMNS} 个）
     */
    public record ProfileTarget(MetaTableDO table, MetaDbDO db, List<MetaColumnDO> columns) {

        /**
         * 物理 schema：优先元数据库的 schema；MySQL 系与 Hive 中库即 schema，回退到库名；其余为空时交由连接默认 schema。
         */
        public String schemaName(ProductTypeEnum product) {
            if (db == null) {
                return null;
            }
            if (StrUtil.isNotBlank(db.getSchemaName())) {
                return db.getSchemaName();
            }
            if (product != null && (product.isLikeMysql() || product == ProductTypeEnum.HIVE)
                    && StrUtil.isNotBlank(db.getDbName())) {
                return db.getDbName();
            }
            return null;
        }

    }

    /**
     * 读取元数据表，已删除或不存在时抛出 META_TABLE_NOT_EXISTS。
     */
    public MetaTableDO requireTable(Long metaTableId) {
        MetaTableDO table = metaTableId == null ? null : tableMapper.selectById(metaTableId);
        if (table == null || Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        return table;
    }

    /**
     * 读取完整探查目标并校验数据源与字段。
     */
    public ProfileTarget load(Long metaTableId) {
        MetaTableDO table = requireTable(metaTableId);
        if (table.getDatasourceId() == null) {
            throw exception(PROFILE_DATASOURCE_MISSING);
        }
        MetaDbDO db = table.getDbId() == null ? null : dbMapper.selectById(table.getDbId());
        List<MetaColumnDO> columns = columnMapper.selectListByTableId(table.getId()).stream()
                .filter(column -> !Objects.equals(column.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED))
                .filter(column -> StrUtil.isNotEmpty(column.getColumnName()))
                .limit(MAX_PROFILE_COLUMNS)
                .toList();
        if (columns.isEmpty()) {
            throw exception(PROFILE_NO_COLUMNS);
        }
        return new ProfileTarget(table, db, columns);
    }

}
