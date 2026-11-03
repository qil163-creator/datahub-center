package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.SupplyDatabaseSimpleRespVO;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_DATABASE_NOT_EXISTS;

/**
 * 数据服务 → 数据源连接信息解析器
 *
 * 数据源实例由 dataforge 模块统一管理，通过 RPC 现取现用（密码不在本模块落库）
 *
 * @author Li
 */
@Component
public class SupplyDatabaseResolver {

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 解析后的连接信息
     */
    @Data
    public static class ConnectionInfo {

        private Long databaseId;

        private String name;

        private ProductTypeEnum productType;

        private String jdbcUrl;

        private String username;

        private String password;

        private String schema;

        private String configurationRevision;

    }

    /**
     * 按数据源编号解析连接信息（密码已解密）
     */
    public ConnectionInfo resolve(Long databaseId) {
        DatabaseConnectionRespDTO dto = databaseApi.getDatabaseConnection(databaseId).getCheckedData();
        if (dto == null) {
            throw exception(DATA_API_DATABASE_NOT_EXISTS);
        }
        ConnectionInfo info = new ConnectionInfo();
        info.setDatabaseId(dto.getId());
        info.setName(dto.getName());
        info.setProductType(ProductTypeEnum.valueOf(dto.getProductType()));
        info.setJdbcUrl(dto.getJdbcUrl());
        info.setUsername(dto.getUsername());
        info.setPassword(dto.getPassword());
        info.setSchema(StrUtil.blankToDefault(dto.getSchema(), ""));
        info.setConfigurationRevision(dto.getConfigurationRevision());
        return info;
    }

    /**
     * 数据源精简列表（向导下拉用，不含连接凭据）
     */
    public List<SupplyDatabaseSimpleRespVO> getDatabaseList() {
        List<DatabaseConnectionRespDTO> list = databaseApi.getDatabaseList(null).getCheckedData();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(item -> {
            SupplyDatabaseSimpleRespVO vo = new SupplyDatabaseSimpleRespVO();
            vo.setId(item.getId());
            vo.setName(item.getName());
            vo.setDatabaseType(item.getDatabaseType());
            vo.setDatabaseTypeName(item.getDatabaseTypeName());
            vo.setCategoryCode(item.getCategoryCode());
            vo.setProductType(item.getProductType());
            vo.setIconPath(item.getIconPath());
            return vo;
        }).toList();
    }

    /**
     * 数据源下的表名列表（向导下拉用）
     */
    public List<String> getTableNameList(Long databaseId) {
        List<String> list = databaseApi.getTableNameList(databaseId).getCheckedData();
        return list == null ? Collections.emptyList() : list;
    }

}
