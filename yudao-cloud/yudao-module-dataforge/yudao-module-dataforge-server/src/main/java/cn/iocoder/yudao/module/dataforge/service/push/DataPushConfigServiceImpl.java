package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushConfigMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.CopyNameSupport;
import cn.iocoder.yudao.module.dataforge.service.push.channel.MqPushChannel;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_CONFIG_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_TYPE_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CONFIG_IN_USE;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CONFIG_LOCKED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CONFIG_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_TEST_FAILED;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_TARGET_DATABASE_NOT_EXISTS;

@Service
@Validated
public class DataPushConfigServiceImpl implements DataPushConfigService {

    @Resource
    private DataPushConfigMapper configMapper;

    @Resource
    private DataPushTaskMapper taskMapper;

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private DeptApi deptApi;

    @Resource
    private HttpPushUrlGuard httpPushUrlGuard;

    @Resource
    private DataPushChannelTester channelTester;

    /**
     * 创建推送配置。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createConfig(DataPushConfigSaveReqVO createReqVO) {
        validateSaveReq(createReqVO);
        DataPushConfigDO config = buildConfig(createReqVO);
        configMapper.insert(config);
        return config.getId();
    }

    /**
     * 更新推送配置。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(DataPushConfigSaveReqVO updateReqVO) {
        DataPushConfigDO existing = validateConfigExists(updateReqVO.getId());
        // 前端回显的凭据为掩码，收到掩码表示保持库中凭据不变，需先回填再校验
        DataPushSecretMasker.restoreMasked(updateReqVO.getConfig(),
                JsonUtils.parseObject(existing.getChannelJson(), DataPushChannelConfigVO.class));
        validateSaveReq(updateReqVO);
        validateSnapshot(existing, lockConfig(updateReqVO.getId()));
        if (taskMapper.selectReleasedCountByConfigId(updateReqVO.getId()) > 0) {
            throw exception(DATA_PUSH_CONFIG_LOCKED);
        }
        configMapper.updateById(buildConfig(updateReqVO));
    }

    /**
     * 删除推送配置。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        lockConfig(id);
        if (taskMapper.selectCountByConfigId(id) > 0) {
            throw exception(DATA_PUSH_CONFIG_IN_USE);
        }
        configMapper.deleteById(id);
    }

    /**
     * 获得推送配置。
     *
     * @param id 记录编号
     * @return 数据推送配置响应数据
     */
    @Override
    public DataPushConfigRespVO getConfig(Long id) {
        // 详情与分页同样脱敏：编辑回填只拿掩码，保存时由 updateConfig 回填真实凭据
        return CollUtil.getFirst(maskSecrets(convertToRespList(Collections.singletonList(validateConfigExists(id)))));
    }

    /**
     * 获得推送配置分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送配置分页结果
     */
    @Override
    public PageResult<DataPushConfigRespVO> getConfigPage(DataPushConfigPageReqVO pageReqVO) {
        PageResult<DataPushConfigDO> page = configMapper.selectPage(pageReqVO);
        return new PageResult<>(maskSecrets(convertToRespList(page.getList())), page.getTotal());
    }

    /**
     * 测试推送通道连通性。
     *
     * @param reqVO 请求参数
     */
    @Override
    public void testConnection(DataPushConfigSaveReqVO reqVO) {
        if (reqVO.getId() != null) {
            DataPushConfigDO existing = validateConfigExists(reqVO.getId());
            DataPushSecretMasker.restoreMasked(reqVO.getConfig(),
                    JsonUtils.parseObject(existing.getChannelJson(), DataPushChannelConfigVO.class));
        }
        validateSaveReq(reqVO);
        try {
            channelTester.test(reqVO.getChannelType(), reqVO.getConfig());
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw exception(DATA_PUSH_CHANNEL_TEST_FAILED, StrUtil.blankToDefault(ex.getMessage(), "未知错误"));
        }
    }

    /**
     * 复制推送配置。
     *
     * @param id 记录编号
     * @return 记录编号
     */
    @Override
    public Long copyConfig(Long id) {
        DataPushConfigDO source = validateConfigExists(id);
        DataPushConfigDO copy = BeanUtils.toBean(source, DataPushConfigDO.class);
        copy.setId(null);
        copy.clean();
        copy.setDeleted(null);
        copy.setName(CopyNameSupport.next(source.getName(), 80, this::nameExists));
        configMapper.insert(copy);
        return copy.getId();
    }

    /**
     * 判断名称存在性条件是否成立。
     *
     * @param name 名称
     * @return 处理是否成功
     */
    private boolean nameExists(String name) {
        return configMapper.selectCount(Wrappers.lambdaQuery(DataPushConfigDO.class)
                .eq(DataPushConfigDO::getName, name)) > 0;
    }

    /**
     * 获得推送配置精简列表。
     *
     * @return 精简列表
     */
    @Override
    public List<DataPushConfigRespVO> getSimpleList() {
        return convertList(configMapper.selectList(), config -> {
            DataPushConfigRespVO vo = new DataPushConfigRespVO();
            vo.setId(config.getId());
            vo.setName(config.getName());
            vo.setChannelType(config.getChannelType());
            return vo;
        });
    }

    /**
     * 校验配置存在性。
     *
     * @param id 记录编号
     * @return 数据推送配置
     */
    private DataPushConfigDO validateConfigExists(Long id) {
        DataPushConfigDO config = configMapper.selectById(id);
        if (config == null) {
            throw exception(DATA_PUSH_CONFIG_NOT_EXISTS);
        }
        return config;
    }

    /**
     * 在锁外校验任务配置，并仅从该任务原接收端恢复掩码凭据。
     *
     * @param reqVO 请求参数
     * @param existingConfigId 已有配置编号
     * @return 数据推送配置
     */
    @Override
    public DataPushConfigDO prepareTaskConfig(DataPushConfigSaveReqVO reqVO, Long existingConfigId) {
        if (reqVO.getId() != null && !Objects.equals(reqVO.getId(), existingConfigId)) {
            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "接收端编号只能对应当前编辑任务的原配置");
        }
        // 深拷贝请求，避免把回填后的真实凭据写回控制器持有的请求对象。
        DataPushConfigSaveReqVO prepared = JsonUtils.parseObject(JsonUtils.toJsonString(reqVO),
                DataPushConfigSaveReqVO.class);
        DataPushConfigDO existing = existingConfigId == null ? null : validateConfigExists(existingConfigId);
        DataPushSecretMasker.restoreMasked(prepared.getConfig(), existing == null ? null
                : JsonUtils.parseObject(existing.getChannelJson(), DataPushChannelConfigVO.class));
        validateSaveReq(prepared);
        DataPushConfigDO config = buildConfig(prepared);
        config.setId(existingConfigId);
        if (existing != null) {
            config.setUpdateTime(existing.getUpdateTime());
        }
        return config;
    }

    /**
     * 在任务写事务内保存接收端；共享历史配置复制后再编辑。
     *
     * @param prepared 已准备的执行配置或记录
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Long saveTaskConfig(DataPushConfigDO prepared) {

        DataPushConfigDO config = BeanUtils.toBean(prepared, DataPushConfigDO.class);
        if (config.getId() != null) {
            DataPushConfigDO existing = lockConfig(config.getId());
            validateSnapshot(config, existing);
            if (taskMapper.selectCountByConfigId(config.getId()) <= 1) {
                if (taskMapper.selectReleasedCountByConfigId(config.getId()) > 0) {
                    throw exception(DATA_PUSH_CONFIG_LOCKED);
                }
                // 清除快照时间，沿用框架的更新时间填充。
                config.setUpdateTime(null);
                if (configMapper.updateById(config) != 1) {
                    throw exception(DATA_PUSH_CONFIG_NOT_EXISTS);
                }
                return config.getId();
            }
            // 保留其他任务使用的原配置，当前任务切换到独立副本。
            config.setId(null);
            config.setUpdateTime(null);
        }
        if (configMapper.insert(config) != 1) {
            throw exception(DATA_PUSH_CONFIG_NOT_EXISTS);
        }
        return config.getId();
    }

    /**
     * 在任务写事务内锁定兼容调用引用的配置，防止引用计数与编辑竞争。
     *
     * @param expected 预期值
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockTaskConfig(DataPushConfigDO expected) {
        validateSnapshot(expected, lockConfig(expected.getId()));
    }

    /**
     * 删除任务后，仅清理该任务原接收端中已无引用的配置。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void deleteTaskConfigIfUnused(Long id) {
        lockConfig(id);
        if (taskMapper.selectCountByConfigId(id) == 0) {
            configMapper.deleteById(id);
        }
    }

    /**
     * 锁定数据推送配置的配置。
     *
     * @param id 记录编号
     * @return 数据推送配置
     */
    private DataPushConfigDO lockConfig(Long id) {

        DataPushConfigDO config = configMapper.selectOne(new LambdaQueryWrapperX<DataPushConfigDO>()
                .eq(DataPushConfigDO::getId, id).last("FOR UPDATE"));
        if (config == null) {
            throw exception(DATA_PUSH_CONFIG_NOT_EXISTS);
        }
        return config;
    }

    /**
     * 校验快照。
     *
     * @param expected 预期值
     * @param actual 实际值
     */
    private void validateSnapshot(DataPushConfigDO expected, DataPushConfigDO actual) {
        if (!Objects.equals(expected.getUpdateTime(), actual.getUpdateTime())) {
            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "接收端配置已发生变化，请刷新任务后重试");
        }
    }

    /**
     * 校验保存。
     *
     * @param reqVO 请求参数
     */
    private void validateSaveReq(DataPushConfigSaveReqVO reqVO) {
        PushChannelTypeEnum type = PushChannelTypeEnum.of(reqVO.getChannelType());
        if (type == null) {
            throw exception(DATA_PUSH_CHANNEL_TYPE_INVALID);
        }
        DataPushChannelConfigVO config = reqVO.getConfig();
        if (config == null) {
            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "请填写通道参数");
        }
        switch (type) {
            case HTTP -> {
                DataPushChannelConfigVO.HttpConfig http = config.getHttp();
                if (http == null || StrUtil.isBlank(http.getUrl())) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "请填写推送地址");
                }
                try {
                    httpPushUrlGuard.validate(http.getUrl().trim());
                    HttpPushSecretParams.validate(http);
                } catch (IllegalArgumentException ex) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, ex.getMessage());
                }
                if (http.getTimeoutMs() != null && (http.getTimeoutMs() < 1000 || http.getTimeoutMs() > 300_000)) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "HTTP 超时须在 1000~300000 毫秒");
                }
                if (http.getRetryTimes() != null && (http.getRetryTimes() < 0 || http.getRetryTimes() > 10)) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "HTTP 重试次数须在 0~10");
                }
                if (http.getRetryIntervalMs() != null
                        && (http.getRetryIntervalMs() < 0 || http.getRetryIntervalMs() > 60_000)) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "HTTP 重试间隔须在 0~60000 毫秒");
                }
                validateCertFiles(http.getTruststorePath(), http.getKeystorePath());
            }
            case DB -> {
                if (config.getDb() == null || config.getDb().getDatabaseId() == null) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "请选择目标数据源");
                }
                if (databaseMapper.selectById(config.getDb().getDatabaseId()) == null) {
                    throw exception(DATA_PUSH_TARGET_DATABASE_NOT_EXISTS);
                }
            }
            case MQ -> {
                DataPushChannelConfigVO.MqConfig mq = config.getMq();
                if (mq == null || StrUtil.isBlank(mq.getMqType())) {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "请选择队列类型");
                }
                if (MqPushChannel.MQ_TYPE_KAFKA.equals(mq.getMqType())) {
                    DataPushChannelConfigVO.KafkaConfig kafka = mq.getKafka();
                    if (kafka == null || StrUtil.isBlank(kafka.getBootstrapServers())
                            || StrUtil.isBlank(kafka.getTopic())) {
                        throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "请填写 Kafka 服务地址与 Topic");
                    }
                    String protocol = StrUtil.blankToDefault(kafka.getSecurityProtocol(), "PLAINTEXT")
                            .trim().toUpperCase();
                    if (!"PLAINTEXT".equals(protocol) && !"SASL_PLAINTEXT".equals(protocol)
                            && !"SASL_SSL".equals(protocol) && !"SSL".equals(protocol)) {
                        throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID,
                                "Kafka 安全协议仅支持 PLAINTEXT / SASL_PLAINTEXT / SASL_SSL / SSL");
                    }
                    if (protocol.contains("SASL")) {
                        if (StrUtil.isBlank(kafka.getSaslUsername()) || StrUtil.isBlank(kafka.getSaslPassword())) {
                            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "Kafka SASL 请填写用户名和密码");
                        }
                        String mechanism = StrUtil.blankToDefault(kafka.getSaslMechanism(), "PLAIN").trim();
                        if (!"PLAIN".equals(mechanism) && !"SCRAM-SHA-256".equals(mechanism)
                                && !"SCRAM-SHA-512".equals(mechanism)) {
                            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID,
                                    "Kafka SASL 机制仅支持 PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512");
                        }
                    }
                    try {
                        kafka.setAcks(MqPushChannel.normalizeKafkaAcks(kafka.getAcks()));
                    } catch (IllegalArgumentException ex) {
                        throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, ex.getMessage());
                    }
                    boolean hasCert = StrUtil.isNotBlank(kafka.getTruststorePath())
                            || StrUtil.isNotBlank(kafka.getKeystorePath());
                    if (hasCert && !protocol.contains("SSL")) {
                        throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID,
                                "配置了证书时 Kafka 安全协议须为 SSL 或 SASL_SSL");
                    }
                    validateCertFiles(kafka.getTruststorePath(), kafka.getKeystorePath());
                } else if (MqPushChannel.MQ_TYPE_RABBIT.equals(mq.getMqType())) {
                    DataPushChannelConfigVO.RabbitConfig rabbit = mq.getRabbit();
                    if (rabbit == null || StrUtil.isBlank(rabbit.getHost()) || rabbit.getPort() == null
                            || StrUtil.isBlank(rabbit.getExchange()) || StrUtil.isBlank(rabbit.getRoutingKey())) {
                        throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID,
                                "请填写 RabbitMQ 主机、端口、交换机与路由键");
                    }
                    validateCertFiles(rabbit.getTruststorePath(), rabbit.getKeystorePath());
                } else {
                    throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, "队列类型仅支持 kafka / rabbitmq");
                }
            }
        }
    }

    /**
     * 构造数据推送配置的配置。
     *
     * @param reqVO 请求参数
     * @return 数据推送配置
     */
    private DataPushConfigDO buildConfig(DataPushConfigSaveReqVO reqVO) {
        DataPushConfigDO config = BeanUtils.toBean(reqVO, DataPushConfigDO.class);
        DataPushChannelConfigVO trimmed = new DataPushChannelConfigVO();
        PushChannelTypeEnum type = PushChannelTypeEnum.of(reqVO.getChannelType());
        switch (type) {
            case HTTP -> trimmed.setHttp(reqVO.getConfig().getHttp());
            case DB -> trimmed.setDb(reqVO.getConfig().getDb());
            case MQ -> trimmed.setMq(reqVO.getConfig().getMq());
        }
        config.setChannelJson(JsonUtils.toJsonString(trimmed));
        return config;
    }

    /**
     * 转换目标列表。
     *
     * @param list 待处理列表
     * @return 转换目标列表结果集合
     */
    private List<DataPushConfigRespVO> convertToRespList(List<DataPushConfigDO> list) {
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Long> orgIds = convertSet(list, DataPushConfigDO::getOrgId);
        Map<Long, DeptRespDTO> deptMap = CollUtil.isEmpty(orgIds)
                ? Collections.emptyMap() : deptApi.getDeptMap(orgIds);
        Set<Long> lockedIds = taskMapper.selectReleasedConfigIds(convertSet(list, DataPushConfigDO::getId));
        return convertList(list, config -> {
            DataPushConfigRespVO vo = BeanUtils.toBean(config, DataPushConfigRespVO.class);
            vo.setConfig(JsonUtils.parseObject(config.getChannelJson(), DataPushChannelConfigVO.class));
            vo.setLocked(lockedIds.contains(config.getId()));
            if (config.getOrgId() != null && deptMap.get(config.getOrgId()) != null) {
                vo.setOrgName(deptMap.get(config.getOrgId()).getName());
            }
            return vo;
        });
    }

    /**
     * 脱敏密钥。
     *
     * @param list 待处理列表
     * @return 脱敏密钥结果集合
     */
    private List<DataPushConfigRespVO> maskSecrets(List<DataPushConfigRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        for (DataPushConfigRespVO vo : list) {
            DataPushSecretMasker.mask(vo.getConfig());
        }
        return list;
    }

    /**
     * 校验证书文件。
     *
     * @param truststorePath 信任库路径
     * @param keystorePath 密钥库路径
     */
    private void validateCertFiles(String truststorePath, String keystorePath) {
        try {
            DataPushSslContexts.validateOptionalFile(truststorePath);
            DataPushSslContexts.validateOptionalFile(keystorePath);
        } catch (IllegalArgumentException ex) {
            throw exception(DATA_PUSH_CHANNEL_CONFIG_INVALID, ex.getMessage());
        }
    }

}
