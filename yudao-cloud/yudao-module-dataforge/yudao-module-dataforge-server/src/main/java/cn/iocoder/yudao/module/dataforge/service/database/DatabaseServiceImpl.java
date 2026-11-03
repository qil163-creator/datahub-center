package cn.iocoder.yudao.module.dataforge.service.database;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.DatabaseNamespaceResolver;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseConnectionStatusEnum;
import cn.iocoder.yudao.module.dataforge.enums.database.DatabaseUsageEnum;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceConnectionResolver;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceDiagnosis;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeCapabilityService;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.*;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据源中心--数据库信息 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DatabaseServiceImpl implements DatabaseService {

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DatabaseTypeMapper databaseTypeMapper;

    @Resource
    private DeptApi deptApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private AccessDatabaseResolver accessDatabaseResolver;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    @Resource
    private FileResourceDiagnosis fileResourceDiagnosis;

    @Resource
    private ConnectionOnlyDiagnosis connectionOnlyDiagnosis;

    @Resource
    private DatabaseTypeCapabilityService typeCapabilityService;

    @Resource
    @Lazy
    private DatabaseApi databaseApi;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService executionAccess;

    /**
     * 创建数据源中心--数据库信息
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDatabase(DatabaseSaveReqVO createReqVO) {
        DatabaseDO database = BeanUtils.toBean(createReqVO, DatabaseDO.class);
        fillCreateDefaults(database);
        validateFileConnection(database);
        database.setPassword(DatabasePasswordEncryptUtils.encrypt(
                database.getPassword(), database.getSecretKey()));
        databaseMapper.insert(database);
        return database.getId();
    }

    /**
     * 更新数据源中心--数据库信息
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDatabase(DatabaseSaveReqVO updateReqVO) {
        DatabaseDO oldObj = validateDatabaseExists(updateReqVO.getId());
        DatabaseDO updateObj = BeanUtils.toBean(updateReqVO, DatabaseDO.class);
        // 记录密钥只由服务端管理，不采信请求中的替换密钥。
        updateObj.setSecretKey(oldObj.getSecretKey());
        DatabaseTypeDO type = updateObj.getDatabaseType() == null
                ? null
                : databaseTypeMapper.selectById(updateObj.getDatabaseType().longValue());
        if (StrUtil.isBlank(updateObj.getJdbcUrl())) {
            updateObj.setJdbcUrl(buildJdbcUrl(type, updateObj));
        } else {
            updateObj.setJdbcUrl(resolveJdbcUrl(updateObj.getJdbcUrl(), updateObj));
        }
        fillSqliteAddressDefaults(updateObj);
        boolean passwordless = ConnectionSettings.passwordless(updateObj, type);
        boolean passwordProvided = !passwordless && StrUtil.isNotBlank(updateReqVO.getPassword());
        if (!passwordless && !passwordProvided && StrUtil.isNotBlank(oldObj.getPassword())
                && isCredentialTargetChanged(oldObj, updateObj, type)) {
            throw exception(DATABASE_CONNECT_FAILED, "连接配置已改变，请重新输入密码或令牌后保存");
        }
        if (passwordless) {
            // 显式无认证模式不会把旧协议或旧认证模式的秘密继续传给目标。
            updateObj.setPassword("");
        } else if (StrUtil.isBlank(updateObj.getPassword())) {
            updateObj.setPassword(DatabasePasswordEncryptUtils.encryptIfPlaintext(
                    oldObj.getPassword(), updateObj.getSecretKey()));
        } else {
            updateObj.setPassword(DatabasePasswordEncryptUtils.encrypt(
                    updateObj.getPassword(), updateObj.getSecretKey()));
        }
        validateFileConnection(updateObj);
        boolean connectionChanged = isConnectionConfigChanged(oldObj, updateObj, passwordProvided);
        databaseMapper.updateById(updateObj);
        if (connectionChanged) {
            resetConnectionStatus(oldObj.getId());
        }
    }

    /**
     * 删除数据源中心--数据库信息
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDatabase(Long id) {
        // 校验存在
        validateDatabaseExists(id);
        databaseMapper.deleteById(id);
    }

    /**
     * 批量删除数据源中心--数据库信息
     *
     * @param ids 记录编号集合
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDatabaseListByIds(List<Long> ids) {
        for (Long id : ids) {
            validateDatabaseExists(id);
        }
        databaseMapper.deleteByIds(ids);
    }

    /**
     * 校验数据源存在性。
     *
     * @param id 记录编号
     * @return 数据源
     */
    private DatabaseDO validateDatabaseExists(Long id) {
        DatabaseDO database = databaseMapper.selectById(id);
        if (database == null) {
            throw exception(DATABASE_NOT_EXISTS);
        }
        return database;
    }

    /**
     * 补充创建默认值。
     *
     * @param database 数据源
     */
    private void fillCreateDefaults(DatabaseDO database) {
        DatabaseTypeDO type = database.getDatabaseType() == null
                ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (StrUtil.isBlank(database.getName())) {
            String typeName = type != null && StrUtil.isNotBlank(type.getTypeName())
                    ? type.getTypeName()
                    : "db";
            String host = StrUtil.blankToDefault(database.getDatabaseIp(), "host");
            database.setName(typeName + "_" + host);
        }
        if (StrUtil.isBlank(database.getJdbcUrl())) {
            database.setJdbcUrl(buildJdbcUrl(type, database));
        } else {
            database.setJdbcUrl(resolveJdbcUrl(database.getJdbcUrl(), database));
        }
        fillSqliteAddressDefaults(database);
        // MySQL 协议类数据库（MySQL/MariaDB/Doris/StarRocks 等）没有 schema 概念，兜底为空串，避免 NOT NULL 约束冲突
        if (database.getDatabaseSchema() == null) {
            database.setDatabaseSchema("");
        }
        if (database.getStatus() == null) {
            database.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (database.getUsageType() == null) {
            database.setUsageType(DatabaseUsageEnum.BUSINESS.getType());
        }
        if (database.getConnectionStatus() == null) {
            database.setConnectionStatus(DatabaseConnectionStatusEnum.UNTESTED.getStatus());
        }
        if (database.getSort() == null) {
            database.setSort(0);
        }
        database.setSecretKey(IdUtil.fastSimpleUUID());
        if (ConnectionSettings.passwordless(database, type)) {
            database.setPassword("");
        }
    }

    /**
     * 校验文件连接。
     *
     * @param database 数据源
     */
    private void validateFileConnection(DatabaseDO database) {
        DatabaseTypeDO type = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (type == null) {
            throw exception(DATABASE_TYPE_NOT_EXISTS);
        }
        if (ConnectionSettings.managed(type)) {
            try {
                ConnectionSettings.read(database, type);
                database.setUsageType(DatabaseUsageEnum.BUSINESS.getType());
                // Endpoint、服务器列表型协议没有单独主机列；保留既有非空表约束。
                database.setDatabaseIp(StrUtil.nullToEmpty(database.getDatabaseIp()));
                database.setDatabasePort(StrUtil.nullToEmpty(database.getDatabasePort()));
                database.setDatabaseName(StrUtil.nullToEmpty(database.getDatabaseName()));
                if (!ConnectionSettings.jdbc(type)) {
                    database.setJdbcUrl("");
                }
            } catch (IllegalArgumentException invalid) {
                throw exception(DATABASE_CONNECT_FAILED, invalid.getMessage());
            }
            return;
        }
        if (FileResourceConnectionResolver.supported(type)) {
            FileResourceConnectionResolver.config(database, type, "");
        } else if (Set.of("SQLITE", "SQLITE3").contains(ConnectionSettings.code(type))
                && StrUtil.startWith(database.getJdbcUrl(), "jdbc:sqlite:")) {
            if (StrUtil.isBlank(StrUtil.subAfter(database.getJdbcUrl(), "jdbc:sqlite:", false))) {
                throw exception(DATABASE_CONNECT_FAILED, "SQLite 文件连接地址不能为空");
            }
        } else {
            try {
                ConnectionSettings.address(database);
            } catch (IllegalArgumentException invalid) {
                throw exception(DATABASE_CONNECT_FAILED, invalid.getMessage());
            }
        }
    }

    /**
     * 构造JDBCURL。
     *
     * @param type 类型标识
     * @param database 数据源
     * @return 构造JDBCURL文本
     */
    private String buildJdbcUrl(DatabaseTypeDO type, DatabaseDO database) {
        if (type == null || StrUtil.isBlank(type.getUrlTemplates())) {
            return resolveJdbcUrl(database.getJdbcUrl(), database);
        }
        return resolveJdbcUrl(type.getUrlTemplates(), database);
    }

    /**
     * 补充SQLite地址默认值。
     *
     * @param database 数据源
     */
    private void fillSqliteAddressDefaults(DatabaseDO database) {
        if (StrUtil.startWith(database.getJdbcUrl(), "jdbc:sqlite:")) {
            database.setDatabaseIp(StrUtil.nullToEmpty(database.getDatabaseIp()));
            database.setDatabasePort(StrUtil.nullToEmpty(database.getDatabasePort()));
        }
    }

    /**
     * 解析JDBCURL。
     *
     * @param jdbcUrl JDBCURL
     * @param database 数据源
     * @return 解析JDBCURL文本
     */
    private String resolveJdbcUrl(String jdbcUrl, DatabaseDO database) {
        if (StrUtil.isBlank(jdbcUrl) || database == null) {
            return jdbcUrl;
        }
        return jdbcUrl
                .replace("${host}", StrUtil.blankToDefault(database.getDatabaseIp(), ""))
                .replace("${port}", StrUtil.blankToDefault(database.getDatabasePort(), ""))
                .replace("${dbName}", StrUtil.blankToDefault(database.getDatabaseName(), ""))
                .replace("${sid}", StrUtil.blankToDefault(database.getDatabaseName(), ""))
                .replace("${serviceName}", StrUtil.blankToDefault(database.getDatabaseName(), ""))
                .replace("${schema}", StrUtil.blankToDefault(database.getDatabaseSchema(), ""));
    }

    /**
     * 追加URL参数。
     *
     * @param jdbcUrl JDBCURL
     * @param params 业务参数映射
     * @return 追加URL参数文本
     */
    private String appendUrlParams(String jdbcUrl, String params) {
        if (StrUtil.isBlank(jdbcUrl) || StrUtil.isBlank(params)) {
            return jdbcUrl;
        }
        if (jdbcUrl.contains(params)) {
            return jdbcUrl;
        }
        String sep = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + sep + params;
    }

    /**
     * 按表单内容测试数据库连接
     *
     * @param id 记录编号
     */
    @Override
    public void testDatabase(Long id) {
        DatabaseDO database = validateDatabaseExists(id);
        testAndPersist(database, DatabasePasswordEncryptUtils.decrypt(
                database.getPassword(), database.getSecretKey()));
    }

    /**
     * 按表单内容测试数据库连接
     *
     * @param reqVO 请求参数
     */
    @Override
    public void testDatabase(DatabaseSaveReqVO reqVO) {
        ResolvedTestConnection connection = resolveTestConnection(reqVO);
        testConnection(connection.database(), connection.password());
    }

    /** 复用原密码只允许原有连接配置；绝不使用请求携带的 secretKey 解密。 */
    private ResolvedTestConnection resolveTestConnection(DatabaseSaveReqVO reqVO) {
        DatabaseDO database = BeanUtils.toBean(reqVO, DatabaseDO.class);
        String password = reqVO.getPassword();
        DatabaseTypeDO testType = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (ConnectionSettings.managed(testType)) {
            database.setJdbcUrl(ConnectionSettings.jdbc(testType)
                    ? resolveJdbcUrl(StrUtil.blankToDefault(database.getJdbcUrl(), buildJdbcUrl(testType, database)),
                    database) : "");
        }
        if (ConnectionSettings.passwordless(database, testType)) {
            return new ResolvedTestConnection(database, "");
        }
        if (reqVO.getId() != null && StrUtil.isBlank(password)) {
            DatabaseDO oldObj = validateDatabaseExists(reqVO.getId());
            if (StrUtil.isBlank(database.getJdbcUrl())) {
                database.setJdbcUrl(oldObj.getJdbcUrl());
            }
            // 已存凭据只用于原受管连接；目标、驱动、用户或连接属性变化时必须重新输入密码。
            if (isCredentialTargetChanged(oldObj, database, testType)) {
                throw exception(DATABASE_CONNECT_FAILED, "连接配置已改变，请重新输入密码后测试");
            }
            password = DatabasePasswordEncryptUtils.decrypt(
                    oldObj.getPassword(), oldObj.getSecretKey());
        }
        return new ResolvedTestConnection(database, password);
    }

    /**
     * 测试与持久化。
     *
     * @param database 数据源
     * @param password 密码
     */
    private void testAndPersist(DatabaseDO database, String password) {
        LocalDateTime startedAt = databaseMapper.selectDiagnosticStartTime();
        DatabaseTypeDO type = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (ConnectionSettings.managed(type)) {
            DatabaseDiagnosisRespVO response = connectionOnlyDiagnosis.diagnose(database, type, password, startedAt);
            persistConnectionOnlyResult(database, startedAt, response);
            if (!response.isConnectionSuccessful()) {
                throw exception(DATABASE_CONNECT_FAILED, "连接未验证通过，请查看分组诊断");
            }
            return;
        }
        try {
            testConnection(database, password);
            persistConnectionResult(database, startedAt, DatabaseConnectionStatusEnum.ONLINE.getStatus(), "");
        } catch (ServiceException ex) {
            persistConnectionResult(database, startedAt, DatabaseConnectionStatusEnum.OFFLINE.getStatus(),
                    "连接验证失败，请重新测试查看详情");
            throw ex;
        }
    }

    /**
     * 使用组件执行真实分组诊断；写入检查需要额外更新权限。
     *
     * @param reqVO 请求参数
     * @return 数据源诊断响应数据
     */
    @Override
    public DatabaseDiagnosisRespVO diagnoseDatabase(DatabaseDiagnosisReqVO reqVO) {
        // 与原数据源列表一致：平台内可见，权限由登录身份决定，不采信 orgId 等表单属性。
        if (!securityFrameworkService.hasPermission("dataforge:database:query")
                || (reqVO != null && reqVO.isIncludeWriteChecks()
                && !securityFrameworkService.hasPermission("dataforge:database:update"))) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        if (reqVO == null || !reqVO.isSourceValid()
                || (reqVO.getDatabaseId() != null && reqVO.getDatabaseId() <= 0)) {
            throw exception(BAD_REQUEST);
        }
        DatabaseDO snapshot = reqVO.getDatabaseId() == null ? null : validateDatabaseExists(reqVO.getDatabaseId());
        ResolvedTestConnection connection = snapshot == null
                ? resolveTestConnection(reqVO.getConnection())
                : new ResolvedTestConnection(snapshot,
                DatabasePasswordEncryptUtils.decrypt(snapshot.getPassword(), snapshot.getSecretKey()));
        DatabaseDO database = connection.database();
        DatabaseTypeDO fileType = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        boolean elasticsearchDiagnosis = "ELASTICSEARCH".equals(ConnectionSettings.code(fileType));
        if (ConnectionSettings.managed(fileType) || elasticsearchDiagnosis) {
            LocalDateTime startedAt = snapshot == null ? LocalDateTime.now() : databaseMapper.selectDiagnosticStartTime();
            String jdbcUrl = elasticsearchDiagnosis
                    ? resolveJdbcUrl(StrUtil.blankToDefault(database.getJdbcUrl(), buildJdbcUrl(fileType, database)), database)
                    : database.getJdbcUrl();
            DatabaseDiagnosisRespVO response = elasticsearchDiagnosis
                    ? connectionOnlyDiagnosis.diagnose(database, fileType, connection.password(), startedAt, jdbcUrl)
                    : connectionOnlyDiagnosis.diagnose(database, fileType, connection.password(), startedAt);
            if (snapshot != null) {
                persistConnectionOnlyResult(snapshot, startedAt, response);
            }
            return response;
        }
        if (FileResourceConnectionResolver.supported(fileType)) {
            LocalDateTime startedAt = snapshot == null ? LocalDateTime.now() : databaseMapper.selectDiagnosticStartTime();
            DatabaseDiagnosisRespVO response = fileResourceDiagnosis.diagnose(database, fileType,
                    connection.password(), reqVO.isIncludeWriteChecks());
            if (snapshot != null) {
                persistConnectionResult(snapshot, startedAt,
                        response.isConnectionSuccessful() ? DatabaseConnectionStatusEnum.ONLINE.getStatus() : DatabaseConnectionStatusEnum.OFFLINE.getStatus(),
                        response.isConnectionSuccessful() ? "连接与根目录验证通过" : "文件连接或根目录验证失败");
            }
            return response;
        }
        ProductTypeEnum product = accessDatabaseResolver.resolveProductType(database);
        String schema;
        try {
            schema = DatabaseNamespaceResolver.resolve(product,
                    database.getDatabaseName(), database.getDatabaseSchema()).orElse("");
        } catch (IllegalArgumentException invalidNamespace) {
            throw exception(DATABASE_NAMESPACE_INVALID);
        }
        DatabaseTypeDO type = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        String jdbcUrl = StrUtil.blankToDefault(database.getJdbcUrl(), buildJdbcUrl(type, database));
        jdbcUrl = appendUrlParams(resolveJdbcUrl(jdbcUrl, database), database.getExtendConfig());
        if (StrUtil.isBlank(jdbcUrl)) {
            throw exception(DATABASE_JDBC_URL_EMPTY);
        }
        LocalDateTime startedAt = snapshot == null ? LocalDateTime.now() : databaseMapper.selectDiagnosticStartTime();
        // 外部数据库 I/O 不放入本地持锁事务。组件负责超时、真实探测、临时表隔离与安全错误文案。
        ConnectionDiagnosticResult result = dbSwitchRuntime.diagnoseConnection(product,
                null, jdbcUrl, database.getUserName(), connection.password(),
                new ConnectionDiagnosticOptions(schema, reqVO.isIncludeWriteChecks(), 5000));
        var conversion = DatabaseDiagnosisConverter.convertWithIntegrity(result, startedAt, product);
        DatabaseDiagnosisRespVO response = conversion.response();
        if (snapshot != null) {
            DatabaseConnectionStatusEnum status = diagnosisConnectionStatus(conversion);
            String message = status == DatabaseConnectionStatusEnum.ONLINE ? "连接与目标资源验证通过"
                    : status == DatabaseConnectionStatusEnum.OFFLINE ? "连接或目标资源验证失败，请查看诊断详情"
                    : "连接状态未确认，请查看未验证项目";
            persistConnectionResult(snapshot, startedAt, status.getStatus(), message);
        }
        return response;
    }

    /**
     * 获取数据源的诊断连接状态。
     *
     * @param conversion 转换
     * @return 数据源连接状态
     */
    private static DatabaseConnectionStatusEnum diagnosisConnectionStatus(
            DatabaseDiagnosisConverter.Conversion conversion) {
        // 不支持或预算耗尽只是尚未验证；不把缺乏证据、损坏结果当作数据库离线。
        if (!conversion.validSource()) {
            return DatabaseConnectionStatusEnum.UNTESTED;
        }
        if (conversion.response().isConnectionSuccessful()) {
            return DatabaseConnectionStatusEnum.ONLINE;
        }
        // 显示方案可能把清理警告归入连接配置项，不能用展示位置推断连接离线。
        return conversion.connectionFailed() ? DatabaseConnectionStatusEnum.OFFLINE : DatabaseConnectionStatusEnum.UNTESTED;
    }

    /**
     * 持久化连接仅结果。
     *
     * @param database 数据源
     * @param startedAt 已开始时间
     * @param response 响应对象
     */
    private void persistConnectionOnlyResult(DatabaseDO database, LocalDateTime startedAt,
            DatabaseDiagnosisRespVO response) {
        boolean connectionFailed = !response.isConnectionSuccessful() && response.getGroups() != null
                && response.getGroups().stream().flatMap(group -> group.getItems().stream())
                .anyMatch(item -> "FAIL".equals(item.getStatus()) && Set.of("CONFIG", "connection", "CONNECT",
                        "CONNECTION", "AUTH", "VALIDATION", "SCHEMA", "TARGET", "RESOURCE")
                        .contains(item.getCheckCode()));
        DatabaseConnectionStatusEnum status = response.isConnectionSuccessful() ? DatabaseConnectionStatusEnum.ONLINE
                : connectionFailed ? DatabaseConnectionStatusEnum.OFFLINE : DatabaseConnectionStatusEnum.UNTESTED;
        persistConnectionResult(database, startedAt, status.getStatus(), response.isConnectionSuccessful()
                ? "协议及配置目标资源验证通过" : "连接未验证通过，请查看诊断中的协议、认证与目标资源结果");
    }

    private record ResolvedTestConnection(DatabaseDO database, String password) {

        /**
         * 返回当前对象的字符串表示，供日志或展示使用。
         *
         * @return 当前对象的字符串表示，供日志或展示使用文本
         */
        @Override
        public String toString() {
            return "ResolvedTestConnection[credentials redacted]";
        }
    }

    /**
     * 测试连接。
     *
     * @param database 数据源
     * @param password 密码
     */
    private void testConnection(DatabaseDO database, String password) {
        DatabaseTypeDO type = database.getDatabaseType() == null
                ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        if (ConnectionSettings.managed(type)) {
            if (!connectionOnlyDiagnosis.diagnose(database, type, password, LocalDateTime.now())
                    .isConnectionSuccessful()) {
                throw exception(DATABASE_CONNECT_FAILED, "协议认证或目标资源验证失败，请使用分组诊断查看结果");
            }
            return;
        }
        if (FileResourceConnectionResolver.supported(type)) {
            if (!fileResourceDiagnosis.diagnose(database, type, password, false).isConnectionSuccessful()) {
                throw exception(DATABASE_CONNECT_FAILED, "文件连接或根目录验证失败");
            }
            return;
        }
        String jdbcUrl = StrUtil.blankToDefault(database.getJdbcUrl(), buildJdbcUrl(type, database));
        jdbcUrl = resolveJdbcUrl(jdbcUrl, database);
        jdbcUrl = appendUrlParams(jdbcUrl, database.getExtendConfig());
        if (StrUtil.isBlank(jdbcUrl)) {
            throw exception(DATABASE_JDBC_URL_EMPTY);
        }
        // 复用 dbswitch 组件测试连接：产品类型映射、方言探活 SQL 均由组件统一处理，避免重复实现
        ProductTypeEnum productType = accessDatabaseResolver.resolveProductType(database);
        String validationQuery = type != null && StrUtil.isNotBlank(type.getValidationQuery())
                ? type.getValidationQuery()
                : productType.getTestSql();
        try {
            dbSwitchRuntime.jdbcMetadata(productType).testQuerySQL(
                    jdbcUrl, database.getUserName(), password, validationQuery);
        } catch (Exception ex) {
            // 驱动原始异常可能含连接串和凭据；旧 Boolean 端点同样不回显该内容。
            throw exception(DATABASE_CONNECT_FAILED, "身份认证或数据库连接验证失败，请使用分组诊断查看详情");
        }
    }

    /**
     * 获得数据源中心--数据库信息
     *
     * @param id 记录编号
     * @return 数据源
     */
    @Override
    public DatabaseDO getDatabase(Long id) {
        return databaseMapper.selectById(id);
    }

    /** 只拼主机和端口。连接串里的账号、密码和库名不进入下拉数据。 */
    private static String databaseHost(DatabaseDO database) {
        if (StrUtil.isNotBlank(database.getDatabaseIp())) {
            String ip = database.getDatabaseIp().trim();
            return StrUtil.isBlank(database.getDatabasePort())
                    ? ip : ip + ":" + database.getDatabasePort().trim();
        }
        String jdbcUrl = database.getJdbcUrl();
        if (StrUtil.isBlank(jdbcUrl)) {
            return null;
        }
        String rest = jdbcUrl;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        } else {
            int at = rest.lastIndexOf('@');
            if (at < 0) {
                return null;
            }
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        int userInfo = rest.lastIndexOf('@');
        if (userInfo >= 0) {
            rest = rest.substring(userInfo + 1);
        }
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == ';') {
                cut = i;
                break;
            }
        }
        if (cut == 0) {
            return null;
        }
        String[] parts = rest.substring(0, cut).split(":");
        if (parts.length >= 2 && StrUtil.isNotBlank(parts[0]) && parts[1].chars().allMatch(Character::isDigit)) {
            return parts[0] + ":" + parts[1];
        }
        return StrUtil.isBlank(parts[0]) ? null : parts[0];
    }

    /**
     * 获得数据源精简列表（启用状态），可按库用途过滤
     *
     * @param usageType 用途类型
     * @return 数据源精简列表（启用状态），可按库用途过滤结果集合
     */
    @Override
    public List<DatabaseSimpleRespVO> getSimpleDatabaseList(Integer usageType) {
        List<DatabaseDO> list = databaseMapper.selectList(new LambdaQueryWrapperX<DatabaseDO>()
                .eq(DatabaseDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .eqIfPresent(DatabaseDO::getUsageType, usageType)
                .orderByDesc(DatabaseDO::getId));
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Long> typeIds = convertSet(list, database ->
                database.getDatabaseType() == null ? null : database.getDatabaseType().longValue());
        Map<Long, DatabaseTypeDO> typeMap = CollUtil.isEmpty(typeIds)
                ? Collections.emptyMap()
                : convertMap(databaseTypeMapper.selectByIds(typeIds), DatabaseTypeDO::getId);
        return convertList(list, database -> {
            DatabaseSimpleRespVO vo = BeanUtils.toBean(database, DatabaseSimpleRespVO.class);
            vo.setDatabaseType(database.getDatabaseType() == null ? null : database.getDatabaseType().longValue());
            vo.setHost(databaseHost(database));
            if (database.getDatabaseType() != null) {
                DatabaseTypeDO type = typeMap.get(database.getDatabaseType().longValue());
                if (type != null) {
                    vo.setDatabaseTypeName(type.getTypeName());
                    vo.setCategoryCode(type.getCategoryCode());
                    vo.setIconPath(type.getIconPath());
                    dbSwitchRuntime.products().resolve(type.getTypeCode()).ifPresent(product -> {
                        vo.setProductId(product.name());
                        vo.setCursorTargets(dbSwitchRuntime.products().cursorTargets(product).stream()
                                .map(Enum::name).sorted().toList());
                    });
                }
            }
            return vo;
        });
    }

    /**
     * 获得数据源下的表清单（含视图标记）
     *
     * @param id 记录编号
     * @return 数据源下的表清单（含视图标记）结果集合
     */
    @Override
    public List<DatabaseTableRespVO> getTableList(Long id) {
        DatabaseDO database = validateDatabaseExists(id);
        AccessDatabaseResolver.ConnectionInfo conn = accessDatabaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        if (conn.getProductType() == ProductTypeEnum.ODPS) {
            try {
                return dbSwitchRuntime.catalogMetadata(conn.getProductType()).tables(catalogRequest(database, conn)).stream().map(table -> {
                    var vo = new DatabaseTableRespVO();
                    vo.setTableName(table.name());
                    vo.setRemarks(table.comment());
                    vo.setViewTable(table.view());
                    return vo;
                }).toList();
            } catch (RuntimeException failure) {
                throw exception(DATABASE_QUERY_FAILED, "ODPS 表目录读取失败，请核对项目、目录权限与连接配置");
            }
        }
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        try {
            List<TableDescription> tables = metaDataService.queryTableList(
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema());
            return convertList(tables, table -> {
                DatabaseTableRespVO vo = new DatabaseTableRespVO();
                vo.setTableName(table.getTableName());
                vo.setRemarks(table.getRemarks());
                vo.setViewTable(table.isViewTable());
                return vo;
            });
        } catch (Exception ex) {
            throw wrapBrowseFailure(ex, DATABASE_CONNECT_FAILED);
        }
    }

    /**
     * 获得数据源下指定表的属性与字段结构
     *
     * @param id 记录编号
     * @param tableName 表名
     * @return 数据源表元数据响应数据
     */
    @Override
    public DatabaseTableMetaRespVO getTableMeta(Long id, String tableName) {
        DatabaseDO database = validateDatabaseExists(id);
        AccessDatabaseResolver.ConnectionInfo conn = accessDatabaseResolver.resolve(id, DATABASE_NOT_EXISTS);
        if (conn.getProductType() == ProductTypeEnum.ODPS) {
            try {
                var request = catalogRequest(database, conn);
                var structure = dbSwitchRuntime.catalogMetadata(conn.getProductType()).describe(request, tableName);
                var vo = new DatabaseTableMetaRespVO();
                vo.setSchemaName(database.getDatabaseName());
                vo.setTableName(structure.table().name());
                vo.setRemarks(structure.table().comment());
                if (structure.table().view() != null) vo.setTableType(structure.table().view() ? "VIEW" : "TABLE");
                vo.setColumns(structure.columns().stream().map(column -> {
                    var field = new DatabaseTableColumnRespVO();
                    field.setFieldName(column.name());
                    field.setFieldTypeName(column.nativeType());
                    field.setRemarks(column.comment());
                    field.setNullable(column.nullable());
                    field.setLength(column.length());
                    field.setPrecision(column.precision());
                    field.setScale(column.scale());
                    field.setPartitionKey(column.partition());
                    return field;
                }).toList());
                return vo;
            } catch (RuntimeException failure) {
                throw exception(DATABASE_QUERY_FAILED, "ODPS 表结构读取失败，请核对表名、结构权限与连接配置");
            }
        }
        String canonicalName = DatabaseTableBrowseSupport.resolveTableName(getTableList(id), tableName);
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        try {
            SchemaTableMeta meta = metaDataService.queryTableMeta(
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                    conn.getSchema(), canonicalName);
            return DatabaseTableBrowseSupport.toMetaVO(meta, conn.getSchema());
        } catch (IllegalArgumentException ex) {
            throw exception(DATABASE_TABLE_NOT_EXISTS);
        } catch (Exception ex) {
            throw wrapBrowseFailure(ex, DATABASE_QUERY_FAILED);
        }
    }

    /** 已存平台连接只映射目录请求；凭据不进入返回结果或节点配置。 */
    private cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest catalogRequest(
            DatabaseDO database, AccessDatabaseResolver.ConnectionInfo connection) {
        DatabaseTypeDO type = database.getDatabaseType() == null ? null
                : databaseTypeMapper.selectById(database.getDatabaseType().longValue());
        ConnectionSettings settings = ConnectionSettings.read(database, type);
        settings.validateCredentials(database, connection.getPassword());
        return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest(connection.getProductType(),
                null, null, settings.get("endpoint", ""), connection.getUsername(), connection.getPassword(),
                database.getDatabaseName(), null, Map.of("project", database.getDatabaseName()), settings.timeout());
    }

    /**
     * 只读预览指定表样例数据
     *
     * @param reqVO 请求参数
     * @return 数据源预览响应数据
     */
    @Override
    public DatabasePreviewRespVO previewTable(DatabasePreviewReqVO reqVO) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        executionAccess.requireUserPermission(actor, "dataforge:database:preview");
        DatabaseDO database = validateDatabaseExists(reqVO.getDatabaseId());
        if (!Objects.equals(database.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw exception(DATABASE_UNAVAILABLE);
        }
        var request = new DatabasePreviewReqDTO();
        request.setDatabaseId(reqVO.getDatabaseId());
        request.setTableName(reqVO.getTableName());
        request.setLimit(DatabaseTableBrowseSupport.resolveLimit(reqVO.getLimit()));
        // 登录和数据源状态由服务端校验，查询始终经过只读 SQL 路径。
        return BeanUtils.toBean(databaseApi.previewTable(request).getCheckedData(), DatabasePreviewRespVO.class);
    }

    /**
     * 包装浏览失败。
     *
     * @param ex 异常信息
     * @param fallback 回退
     * @return 服务异常
     */
    private ServiceException wrapBrowseFailure(Exception ex, ErrorCode fallback) {
        if (ex instanceof ServiceException serviceException) {
            return serviceException;
        }
        String detail = DatabaseTableBrowseSupport.failureDetail(ex);
        if (DatabaseTableBrowseSupport.isConnectionFailure(ex)) {
            return exception(DATABASE_CONNECT_FAILED, detail);
        }
        return exception(fallback, detail);
    }

    /**
     * 解析浏览连接。
     *
     * @param id 记录编号
     * @return 连接信息
     */
    private AccessDatabaseResolver.ConnectionInfo resolveBrowseConnection(Long id) {
        validateDatabaseExists(id);
        return accessDatabaseResolver.resolve(id, DATABASE_NOT_EXISTS);
    }

    /**
     * 获得数据源中心--数据库信息分页
     *
     * @param pageReqVO 分页查询条件
     * @return 数据源中心--数据库信息分页结果
     */
    @Override
    public PageResult<DatabaseDO> getDatabasePage(DatabasePageReqVO pageReqVO) {
        return databaseMapper.selectPage(pageReqVO);
    }

    /**
     * 填充类型名称、图标、项目名称、单位名称
     *
     * @param list 待处理列表
     * @return 填充类型名称、图标、项目名称、单位名称结果集合
     */
    @Override
    public List<DatabaseRespVO> convertToRespList(List<DatabaseDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Long> typeIds = convertSet(list, database ->
                database.getDatabaseType() == null ? null : database.getDatabaseType().longValue());
        Map<Long, DatabaseTypeDO> typeMap = CollUtil.isEmpty(typeIds)
                ? Collections.emptyMap()
                : convertMap(databaseTypeMapper.selectByIds(typeIds), DatabaseTypeDO::getId);

        Set<Long> orgIds = convertSet(list, DatabaseDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap()
                : deptApi.getDeptMap(orgIds);

        Set<Long> userIds = new HashSet<>();
        userIds.addAll(convertSet(list, database -> parseUserId(database.getCreator())));
        userIds.addAll(convertSet(list, database -> parseUserId(database.getUpdater())));
        Map<Long, AdminUserRespDTO> userMap = CollUtil.isEmpty(userIds)
                ? Collections.emptyMap()
                : adminUserApi.getUserMap(userIds);

        return convertList(list, database -> {
            DatabaseRespVO vo = BeanUtils.toBean(database, DatabaseRespVO.class);
            vo.setHasPassword(StrUtil.isNotBlank(database.getPassword()));
            vo.setPassword(null);
            vo.setSecretKey(null);
            if (database.getDatabaseType() != null) {
                DatabaseTypeDO type = typeMap.get(database.getDatabaseType().longValue());
                if (type != null) {
                    vo.setDatabaseTypeName(type.getTypeName());
                    vo.setIconPath(type.getIconPath());
                    vo.setTypeCode(type.getTypeCode());
                    var capabilities = typeCapabilityService.describe(type);
                    vo.setProductId(capabilities.getProductId());
                    vo.setCapabilities(capabilities.getCapabilities());
                }
            }
            if (database.getOrgId() != null) {
                DeptRespDTO dept = deptMap.get(database.getOrgId());
                if (dept != null) {
                    vo.setOrgName(dept.getName());
                }
            }
            vo.setCreatorName(resolveUserName(userMap, database.getCreator()));
            vo.setUpdaterName(resolveUserName(userMap, database.getUpdater()));
            return vo;
        });
    }

    /**
     * 持久化连接结果。
     *
     * @param snapshot 快照
     * @param startedAt 已开始时间
     * @param status 目标状态
     * @param message 消息
     */
    void persistConnectionResult(DatabaseDO snapshot, LocalDateTime startedAt, Integer status, String message) {
        if (snapshot == null || snapshot.getId() == null || startedAt == null) {
            return;
        }
        String msg = StrUtil.subPre(StrUtil.blankToDefault(message, ""), 512);
        LambdaUpdateWrapper<DatabaseDO> update = new LambdaUpdateWrapper<DatabaseDO>()
                .eq(DatabaseDO::getId, snapshot.getId())
                .and(condition -> condition.isNull(DatabaseDO::getLastTestTime)
                        .or().lt(DatabaseDO::getLastTestTime, startedAt))
                .set(DatabaseDO::getConnectionStatus, status)
                .set(DatabaseDO::getLastTestTime, startedAt)
                .set(DatabaseDO::getLastTestMsg, msg);
        // 同时校验修订和配置（包含密文与密钥），防止改配置、轮换密码、删除后旧结果回写。
        matchSnapshot(update, DatabaseDO::getUpdateTime, snapshot.getUpdateTime());
        matchSnapshot(update, DatabaseDO::getDatabaseType, snapshot.getDatabaseType());
        matchSnapshot(update, DatabaseDO::getDatabaseIp, snapshot.getDatabaseIp());
        matchSnapshot(update, DatabaseDO::getDatabasePort, snapshot.getDatabasePort());
        matchSnapshot(update, DatabaseDO::getDatabaseName, snapshot.getDatabaseName());
        matchSnapshot(update, DatabaseDO::getDatabaseSchema, snapshot.getDatabaseSchema());
        matchSnapshot(update, DatabaseDO::getUserName, snapshot.getUserName());
        matchSnapshot(update, DatabaseDO::getJdbcUrl, snapshot.getJdbcUrl());
        matchSnapshot(update, DatabaseDO::getExtendConfig, snapshot.getExtendConfig());
        matchSnapshot(update, DatabaseDO::getPassword, snapshot.getPassword());
        matchSnapshot(update, DatabaseDO::getSecretKey, snapshot.getSecretKey());
        matchSnapshot(update, DatabaseDO::getStatus, snapshot.getStatus());
        databaseMapper.update(null, update);
    }

    /**
     * 比对快照。
     *
     * @param update 更新
     * @param column 列
     * @param value 待处理的值
     */
    private static void matchSnapshot(LambdaUpdateWrapper<DatabaseDO> update,
            SFunction<DatabaseDO, ?> column, Object value) {
        if (value == null) {
            update.isNull(column);
        } else {
            update.eq(column, value);
        }
    }

    /**
     * 重置连接状态。
     *
     * @param id 记录编号
     */
    void resetConnectionStatus(Long id) {
        if (id == null) {
            return;
        }
        databaseMapper.update(null, new LambdaUpdateWrapper<DatabaseDO>()
                .eq(DatabaseDO::getId, id)
                .set(DatabaseDO::getConnectionStatus, DatabaseConnectionStatusEnum.UNTESTED.getStatus())
                .set(DatabaseDO::getLastTestTime, null)
                .set(DatabaseDO::getLastTestMsg, null));
    }

    /**
     * 判断是否满足连接配置变化条件。
     *
     * @param oldObj 历史对象
     * @param updateObj 更新对象
     * @param passwordProvided 密码已提供
     * @return 判断结果
     */
    static boolean isConnectionConfigChanged(DatabaseDO oldObj, DatabaseDO updateObj, boolean passwordProvided) {
        if (oldObj == null || updateObj == null) {
            return false;
        }
        if (passwordProvided) {
            return true;
        }
        return isConnectionAddressChanged(oldObj, updateObj)
                || !sameText(oldObj.getExtendConfig(), updateObj.getExtendConfig());
    }

    /**
     * 判断是否满足凭据目标变化条件。
     *
     * @param oldObj 历史对象
     * @param updateObj 更新对象
     * @param type 类型标识
     * @return 判断结果
     */
    private static boolean isCredentialTargetChanged(DatabaseDO oldObj, DatabaseDO updateObj, DatabaseTypeDO type) {
        if (isConnectionAddressChanged(oldObj, updateObj)) {
            return true;
        }
        if (!ConnectionSettings.managed(type) || ConnectionSettings.jdbc(type)) {
            return !sameText(oldObj.getExtendConfig(), updateObj.getExtendConfig());
        }
        try {
            return !ConnectionSettings.read(oldObj, type).credentialProperties()
                    .equals(ConnectionSettings.read(updateObj, type).credentialProperties());
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }

    /**
     * 判断是否满足连接地址变化条件。
     *
     * @param oldObj 历史对象
     * @param updateObj 更新对象
     * @return 判断结果
     */
    private static boolean isConnectionAddressChanged(DatabaseDO oldObj, DatabaseDO updateObj) {
        return !Objects.equals(oldObj.getDatabaseType(), updateObj.getDatabaseType())
                || !sameText(oldObj.getDatabaseIp(), updateObj.getDatabaseIp())
                || !sameText(oldObj.getDatabasePort(), updateObj.getDatabasePort())
                || !sameText(oldObj.getDatabaseName(), updateObj.getDatabaseName())
                || !sameText(oldObj.getDatabaseSchema(), updateObj.getDatabaseSchema())
                || !sameText(oldObj.getUserName(), updateObj.getUserName())
                || !sameText(oldObj.getJdbcUrl(), updateObj.getJdbcUrl());
    }

    /**
     * 判断是否一致文本条件。
     *
     * @param left 左侧
     * @param right 右侧
     * @return 判断结果
     */
    private static boolean sameText(String left, String right) {
        return StrUtil.equals(StrUtil.nullToEmpty(left), StrUtil.nullToEmpty(right));
    }

    /**
     * 解析用户编号。
     *
     * @param userId 用户编号
     * @return 解析用户编号数值
     */
    private Long parseUserId(String userId) {
        return StrUtil.isBlank(userId) ? null : Convert.toLong(userId, null);
    }

    /**
     * 解析用户名称。
     *
     * @param userMap 用户映射
     * @param userId 用户编号
     * @return 解析用户名称文本
     */
    private String resolveUserName(Map<Long, AdminUserRespDTO> userMap, String userId) {
        Long id = parseUserId(userId);
        if (id == null) {
            return null;
        }
        AdminUserRespDTO user = userMap.get(id);
        return user != null ? user.getNickname() : userId;
    }

}
