package cn.iocoder.yudao.module.dataforge.service.etltemplate;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etltemplate.EtlTemplateDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etltemplate.EtlTemplateMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.*;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 本平台团队可见元数据；图详情需要当前源对象授权；删除仅创建人。模板是不可变片段，应用不修改目标草稿。 */
@Service
public class EtlTemplateService {

    @Resource
    private EtlTemplateMapper mapper;

    @Resource
    private EtlNodeAccessService nodeAccess;

    @Resource
    private EtlPolicyPlanService policies;

    /**
     * 创建团队图片段模板，不复制运行身份或参数默认值。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    public Long create(EtlTemplateCreateReqVO request) {
        Long actor = actor();
        var fragment = EtlTemplateGraph.sanitize(request.getNodes(), request.getEdges());
        authorize(fragment, actor);
        String config = JSONUtil.toJsonStr(new TreeMap<>(Map.of("nodes", fragment.nodes(), "edges", fragment.edges())));
        var row = new EtlTemplateDO();
        row.setName(request.getName().trim());
        row.setDescription(request.getDescription());
        row.setOwnerUserId(actor);
        row.setNodeCount(fragment.nodes().size());
        row.setRequestId(request.getRequestId());
        row.setConfigJson(config);
        row.setContentHash(DigestUtil.sha256Hex(config));
        var existing = byRequest(actor, request.getRequestId());
        if (existing != null) {
            return sameRequest(existing, row);
        }
        try {
            if (mapper.insert(row) != 1) {
                throw new IllegalStateException("模板未成功保存");
            }
        } catch (DuplicateKeyException ex) {
            existing = byRequest(actor, request.getRequestId());
            if (existing == null) {
                throw exception(ETL_TEMPLATE_REQUEST_CONFLICT);
            }
            return sameRequest(existing, row);
        }
        return row.getId();
    }

    /**
     * 分页查看本平台团队模板元数据。
     *
     * @param request 请求参数
     * @return 分页查看本平台团队模板元数据结果
     */
    public PageResult<EtlTemplateRespVO> page(EtlTemplatePageReqVO request) {
        Long actor = actor();
        var page = mapper.selectPage(request,
                new LambdaQueryWrapperX<EtlTemplateDO>().likeIfPresent(EtlTemplateDO::getName, request.getName())
                        .select(EtlTemplateDO::getId, EtlTemplateDO::getName, EtlTemplateDO::getDescription,
                                EtlTemplateDO::getOwnerUserId, EtlTemplateDO::getNodeCount,
                                EtlTemplateDO::getCreateTime)
                        .orderByDesc(EtlTemplateDO::getId));
        return new PageResult<>(page.getList().stream().map(row -> summary(row, actor)).toList(), page.getTotal());
    }

    /**
     * 授权读取模板图片段，应用前须重新读取。
     *
     * @param id 记录编号
     * @return ETL模板响应数据
     */
    public EtlTemplateRespVO get(Long id) {
        Long actor = actor();
        var row = require(id);
        if (!Objects.equals(row.getContentHash(), DigestUtil.sha256Hex(row.getConfigJson()))) {
            throw exception(ETL_TEMPLATE_INVALID, "内容摘要不一致");
        }
        var json = JSONUtil.parseObj(row.getConfigJson());
        var fragment = EtlTemplateGraph.sanitize(maps(json.getJSONArray("nodes")), maps(json.getJSONArray("edges")));
        authorize(fragment, actor);
        var response = summary(row, actor);
        response.setNodes(fragment.nodes());
        response.setEdges(fragment.edges());
        return response;
    }

    /**
     * 创建人删除团队模板，不影响已应用的流程。
     *
     * @param id 记录编号
     */
    public void delete(Long id) {
        Long actor = actor();
        var row = require(id);
        if (!Objects.equals(actor, row.getOwnerUserId())) {
            throw exception(ETL_TEMPLATE_NOT_OWNER);
        }
        mapper.delete(new LambdaQueryWrapperX<EtlTemplateDO>().eq(EtlTemplateDO::getId, id)
                .eq(EtlTemplateDO::getOwnerUserId, actor));
    }

    /**
     * 校验ETL模板所需的访问权限。
     *
     * @param graph 图
     * @param actor 操作人编号
     */
    private void authorize(EtlTemplateGraph.Fragment graph, Long actor) {
        var nodes = EtlGraphConverter.toNodeDefs(graph.nodes());
        nodeAccess.authorize(nodes, actor, Map.of());
        policies.freeze(nodes, actor, Map.of());
    }

    /**
     * 获取ETL模板的操作人。
     *
     * @return 操作人数值
     */
    private Long actor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        return actor;
    }

    /**
     * 校验并获取ETL模板。
     *
     * @param id 记录编号
     * @return ETL模板
     */
    private EtlTemplateDO require(Long id) {
        var row = mapper.selectById(id);
        if (row == null) {
            throw exception(ETL_TEMPLATE_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 获取ETL模板的按请求。
     *
     * @param actor 操作人编号
     * @param request 请求参数
     * @return ETL模板
     */
    private EtlTemplateDO byRequest(Long actor, String request) {
        return mapper.selectOne(new LambdaQueryWrapperX<EtlTemplateDO>().eq(EtlTemplateDO::getOwnerUserId, actor)
                .eq(EtlTemplateDO::getRequestId, request));
    }

    /**
     * 判断是否一致请求条件。
     *
     * @param previous 先前
     * @param current 当前
     * @return 记录编号
     */
    private Long sameRequest(EtlTemplateDO previous, EtlTemplateDO current) {
        if (!Objects.equals(previous.getContentHash(), current.getContentHash()) || !Objects.equals(previous.getName(),
                current.getName()) || !Objects.equals(previous.getDescription(), current.getDescription())) {
            throw exception(ETL_TEMPLATE_REQUEST_CONFLICT);
        }
        return previous.getId();
    }

    /**
     * 汇总ETL模板信息。
     *
     * @param row 当前行数据
     * @param actor 操作人编号
     * @return ETL模板响应数据
     */
    private EtlTemplateRespVO summary(EtlTemplateDO row, Long actor) {
        var response = BeanUtils.toBean(row, EtlTemplateRespVO.class);
        response.setCanDelete(Objects.equals(actor, row.getOwnerUserId()));
        return response;
    }

    /**
     * 将 JSON 数组转换为对象映射列表。
     *
     * @param values 字段值集合
     * @return 将 JSON 数组转换为对象映射列表结果集合
     */
    private List<Map<String, Object>> maps(cn.hutool.json.JSONArray values) {
        if (values == null) {
            throw exception(ETL_TEMPLATE_INVALID, "缺少图配置");
        }
        return values.stream().map(value -> (Map<String, Object>) JSONUtil.parseObj(value)).toList();
    }
}
