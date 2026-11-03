package cn.iocoder.yudao.module.dataforge.service.fileresource;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.file.core.remote.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo.FileResourceConnectionRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.database.DatabasePasswordEncryptUtils;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 文件操作权限授权的平台连接解析；仅输出连接展示信息，密码只在组件调用期间使用。 */
@Component
public class FileResourceConnectionResolver {

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DatabaseTypeMapper typeMapper;

    @Resource
    private ObjectProvider<RemoteFileClientFactory> clientFactories;

    /**
     * 读取文件资源连接连接信息。
     *
     * @return 文件资源连接连接信息结果集合
     */
    public List<FileResourceConnectionRespVO> connections() {
        Map<Long, DatabaseTypeDO> types = new HashMap<>();
        for (DatabaseTypeDO type : typeMapper.selectList()) {
            if (supported(type) && Objects.equals(type.getStatus(), (short) 0)) {
                types.put(type.getId(), type);
            }
        }
        List<FileResourceConnectionRespVO> result = new ArrayList<>();
        for (DatabaseDO db : databaseMapper.selectList(new LambdaQueryWrapperX<DatabaseDO>()
                .eq(DatabaseDO::getStatus, 0).orderByAsc(DatabaseDO::getSort, DatabaseDO::getId))) {
            DatabaseTypeDO type = db.getDatabaseType() == null ? null : types.get(db.getDatabaseType().longValue());
            if (type == null) {
                continue;
            }
            FileResourceConnectionRespVO vo = new FileResourceConnectionRespVO();
            vo.setId(db.getId());
            vo.setName(db.getName());
            vo.setType(type.getTypeCode().toUpperCase(Locale.ROOT));
            vo.setAddress(db.getDatabaseIp() + ":" + db.getDatabasePort());
            vo.setStatus(0);
            vo.setRootPath("/");
            result.add(vo);
        }
        return result;
    }

    /**
     * 打开文件资源连接。
     *
     * @param id 记录编号
     * @return 远程文件客户端
     */
    public RemoteFileClient open(Long id) throws IOException {
        DatabaseDO database = databaseMapper.selectById(id);
        if (database == null || !Objects.equals(database.getStatus(), 0)) {
            throw exception(FILE_RESOURCE_CONNECTION_INVALID);
        }
        DatabaseTypeDO type = database.getDatabaseType() == null ? null : typeMapper.selectById(
                database.getDatabaseType().longValue());
        if (!supported(type) || !Objects.equals(type.getStatus(), (short) 0)) {
            throw exception(FILE_RESOURCE_CONNECTION_INVALID);
        }
        return open(database, type,
                DatabasePasswordEncryptUtils.decrypt(database.getPassword(), database.getSecretKey()));
    }

    /**
     * 打开文件资源连接。
     *
     * @param database 数据源
     * @param type 类型标识
     * @param password 密码
     * @return 远程文件客户端
     */
    public RemoteFileClient open(DatabaseDO database, DatabaseTypeDO type, String password) throws IOException {
        RemoteFileClientFactory factory = clientFactories.getIfAvailable();
        if (factory == null) {
            throw exception(FILE_RESOURCE_DISABLED);
        }
        return factory.open(config(database, type, password));
    }

    /**
     * 判断支持范围条件是否成立。
     *
     * @param type 类型标识
     * @return 处理是否成功
     */
    public static boolean supported(DatabaseTypeDO type) {
        return type != null && ("FTP".equalsIgnoreCase(type.getTypeCode()) || "HDFS".equalsIgnoreCase(
                type.getTypeCode()));
    }

    /**
     * 读取文件资源连接配置。
     *
     * @param db 数据库
     * @param type 类型标识
     * @param password 密码
     * @return 远程文件配置
     */
    public static RemoteFileConfig config(DatabaseDO db, DatabaseTypeDO type, String password) {
        try {
            JsonNode config = JsonUtils.getObjectMapper().readTree(
                    db.getExtendConfig() == null || db.getExtendConfig().isBlank() ? "{}" : db.getExtendConfig());
            if (!config.isObject()) {
                throw new IllegalArgumentException();
            }
            String root = config.path("basePath").asString("/");
            String endpoint = config.path("webHdfsUrl").asString("");
            Set<String> authorities = new HashSet<>();
            JsonNode allowed = config.path("allowedDataNodeAuthorities");
            if (!allowed.isMissingNode() && !allowed.isArray()) {
                throw new IllegalArgumentException();
            }
            if (allowed.isArray()) {
                if (allowed.size() > 100) {
                    throw new IllegalArgumentException();
                }
                for (JsonNode item : allowed) {
                    if (!item.isString()) {
                        throw new IllegalArgumentException();
                    }
                    authorities.add(item.asString());
                }
            }
            return new RemoteFileConfig(RemoteFileConfig.Protocol.valueOf(type.getTypeCode().toUpperCase(Locale.ROOT)),
                    db.getDatabaseIp(), Integer.parseInt(db.getDatabasePort()), db.getUserName(), password,
                    root, endpoint, Set.copyOf(authorities));
        } catch (Exception invalid) {
            throw exception(FILE_RESOURCE_CONFIG_INVALID);
        }
    }
}

