package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.EscapeUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefGenerateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityReportLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_BRIEF_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_BRIEF_TEMPLATE_NOT_EXISTS;

/**
 * 数据治理-数据质量-质检简报 Service 实现
 *
 * <p>变量渲染（数据来自机构评分）：${totalOrg} 参评机构总数、${passRate} 总体通过率、
 * ${paixuOrg} 达标机构数（得分率≥90%）、${lowScoreOrgList}/${lowScoreOrgCount} 低于60%的机构、
 * ${bottomList} 排名末位机构、${cycleLabel} 评估周期描述、${trend} 与上期对比趋势
 *
 * @author Li
 */
@Service
@Validated
public class QualityBriefServiceImpl implements QualityBriefService {

    private static final BigDecimal QUALIFIED_RATE = BigDecimal.valueOf(90);

    private static final BigDecimal LOW_RATE = BigDecimal.valueOf(60);

    private static final int BOTTOM_COUNT = 3;

    @Resource
    private QualityBriefMapper briefMapper;

    @Resource
    private QualityBriefTemplateMapper templateMapper;

    @Resource
    private QualityBriefParagraphMapper paragraphMapper;

    @Resource
    private QualityBriefColumnMapper columnMapper;

    @Resource
    private QualityOrgScoreMapper orgScoreMapper;

    @Resource
    private QualityReportLogMapper reportLogMapper;

    /**
     * 生成简报：按模板段落渲染变量（数据来自机构评分），生成草稿简报并写报告记录
     *
     *  @return 简报编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generateBrief(QualityBriefGenerateReqVO reqVO) {
        QualityBriefTemplateDO template = templateMapper.selectById(reqVO.getTemplateId());
        if (template == null) {
            throw exception(QUALITY_BRIEF_TEMPLATE_NOT_EXISTS);
        }
        QualityReportLogDO reportLog = QualityReportLogDO.builder()
                .reportPeriodStart(reqVO.getAssessStart())
                .reportPeriodEnd(reqVO.getAssessEnd())
                .generateStatus(QualityReportLogDO.STATUS_RUNNING)
                .generateProgress(0)
                .generator(SecurityFrameworkUtils.getLoginUserNickname())
                .generateTime(LocalDateTime.now())
                .build();
        reportLogMapper.insert(reportLog);
        try {
            List<Map<String, Object>> content = renderContent(template, reqVO);
            QualityBriefDO brief = QualityBriefDO.builder()
                    .briefTitle(reqVO.getBriefTitle())
                    .issueNo(reqVO.getIssueNo())
                    .assessCycle(template.getAssessCycle())
                    .assessStart(reqVO.getAssessStart())
                    .assessEnd(reqVO.getAssessEnd())
                    .templateId(template.getId())
                    .briefStatus(QualityBriefDO.STATUS_DRAFT)
                    .briefContent(JsonUtils.toJsonString(content))
                    .generateTime(LocalDateTime.now())
                    .generator(SecurityFrameworkUtils.getLoginUserNickname())
                    .build();
            briefMapper.insert(brief);
            reportLogMapper.updateById(QualityReportLogDO.builder()
                    .id(reportLog.getId())
                    .generateStatus(QualityReportLogDO.STATUS_SUCCESS)
                    .generateProgress(100)
                    .generateMsg("简报生成成功，简报编号：" + brief.getId())
                    .build());
            return brief.getId();
        } catch (Exception ex) {
            reportLogMapper.updateById(QualityReportLogDO.builder()
                    .id(reportLog.getId())
                    .generateStatus(QualityReportLogDO.STATUS_FAILED)
                    .generateMsg(StrUtil.maxLength(ex.getMessage(), 480))
                    .build());
            throw ex;
        }
    }

    /**
     * 发布简报
     */
    @Override
    public void publishBrief(Long id) {
        validateExists(id);
        briefMapper.updateById(QualityBriefDO.builder()
                .id(id).briefStatus(QualityBriefDO.STATUS_PUBLISHED).build());
    }

    /**
     * 删除简报。
     */
    @Override
    public void deleteBrief(Long id) {
        validateExists(id);
        briefMapper.deleteById(id);
    }

    /**
     * 获得简报详情（含渲染后内容，供预览）。
     */
    @Override
    public QualityBriefRespVO getBrief(Long id) {
        QualityBriefDO brief = validateExists(id);
        QualityBriefRespVO respVO = BeanUtils.toBean(brief, QualityBriefRespVO.class);
        if (brief.getTemplateId() != null) {
            QualityBriefTemplateDO template = templateMapper.selectById(brief.getTemplateId());
            if (template != null) {
                respVO.setTemplateName(template.getTemplateName());
            }
        }
        return respVO;
    }

    /**
     * 获得简报分页。
     */
    @Override
    public PageResult<QualityBriefRespVO> getBriefPage(QualityBriefPageReqVO pageReqVO) {
        PageResult<QualityBriefDO> pageResult = briefMapper.selectPage(pageReqVO);
        PageResult<QualityBriefRespVO> result = BeanUtils.toBean(pageResult, QualityBriefRespVO.class);
        List<Long> templateIds = result.getList().stream()
                .map(QualityBriefRespVO::getTemplateId).filter(Objects::nonNull).distinct().toList();
        if (!templateIds.isEmpty()) {
            Map<Long, String> nameMap = templateMapper.selectBatchIds(templateIds).stream()
                    .collect(Collectors.toMap(QualityBriefTemplateDO::getId,
                            QualityBriefTemplateDO::getTemplateName));
            result.getList().forEach(vo -> vo.setTemplateName(nameMap.get(vo.getTemplateId())));
        }
        return result;
    }

    /**
     * 获得报告生成记录分页。
     */
    @Override
    public PageResult<QualityReportLogDO> getReportLogPage(QualityReportLogPageReqVO pageReqVO) {
        return reportLogMapper.selectPage(pageReqVO);
    }

    /**
     * 导出简报：按渲染后的简报内容生成 Word（.doc）或 Excel（.xls）文件流
     *
     *  @param format WORD 或 EXCEL 格式
     */
    @Override
    public void exportBrief(Long id, String format, HttpServletResponse response) throws IOException {
        QualityBriefDO brief = validateExists(id);
        boolean word = "WORD".equalsIgnoreCase(format);
        String html = renderExportHtml(brief);
        String fileName = URLEncoder.encode(
                StrUtil.blankToDefault(brief.getBriefTitle(), "质检简报") + (word ? ".doc" : ".xls"),
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType((word ? "application/msword" : "application/vnd.ms-excel")
                + ";charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName);
        response.getWriter().write(html);
        response.getWriter().flush();
    }

    /**
     * 简报内容 JSON → HTML（Word/Excel 均可直接打开 HTML 流）
     */
    @SuppressWarnings("unchecked")
    private String renderExportHtml(QualityBriefDO brief) {
        StringBuilder html = new StringBuilder()
                .append("<html><head><meta charset=\"UTF-8\"/></head><body>")
                .append("<h1 style=\"text-align:center\">")
                .append(EscapeUtil.escapeHtml4(brief.getBriefTitle())).append("</h1>")
                .append("<p style=\"text-align:center;color:#666\">评估周期：")
                .append(EscapeUtil.escapeHtml4(brief.getAssessStart())).append(" ~ ")
                .append(EscapeUtil.escapeHtml4(brief.getAssessEnd()));
        if (StrUtil.isNotBlank(brief.getIssueNo())) {
            html.append("｜第 ").append(EscapeUtil.escapeHtml4(brief.getIssueNo())).append(" 期");
        }
        html.append("</p>");
        List<Map<String, Object>> blocks = StrUtil.isBlank(brief.getBriefContent())
                ? List.of() : JsonUtils.parseObject(brief.getBriefContent(), List.class);
        for (Map<String, Object> block : blocks) {
            String title = String.valueOf(block.getOrDefault("title", ""));
            if (StrUtil.isNotBlank(title) && !"null".equals(title)) {
                html.append("<h3>").append(EscapeUtil.escapeHtml4(title)).append("</h3>");
            }
            if ("TABLE".equals(String.valueOf(block.get("paraType")))) {
                List<Map<String, Object>> columns =
                        (List<Map<String, Object>>) block.getOrDefault("columns", List.of());
                List<Map<String, Object>> rows =
                        (List<Map<String, Object>>) block.getOrDefault("rows", List.of());
                html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\"><tr>");
                for (Map<String, Object> column : columns) {
                    html.append("<th>").append(EscapeUtil.escapeHtml4(
                            String.valueOf(column.getOrDefault("colTitle", "")))).append("</th>");
                }
                html.append("</tr>");
                for (Map<String, Object> row : rows) {
                    html.append("<tr>");
                    for (Map<String, Object> column : columns) {
                        Object value = row.get(String.valueOf(column.get("dataField")));
                        html.append("<td>").append(EscapeUtil.escapeHtml4(
                                value == null ? "" : String.valueOf(value))).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table>");
            } else {
                html.append("<p>").append(EscapeUtil.escapeHtml4(
                                String.valueOf(block.getOrDefault("content", "")))
                        .replace("\n", "<br/>")).append("</p>");
            }
        }
        return html.append("</body></html>").toString();
    }

    // ==================== 内容渲染 ====================

    /**
     * 按模板段落渲染简报内容：正文段落做变量插值，附表段落输出列定义 + 评分数据行
     */
    private List<Map<String, Object>> renderContent(QualityBriefTemplateDO template,
            QualityBriefGenerateReqVO reqVO) {
        List<QualityBriefParagraphDO> paragraphs = paragraphMapper.selectListByTemplateId(template.getId());
        List<Map<String, Object>> content = new ArrayList<>();
        for (QualityBriefParagraphDO paragraph : paragraphs) {
            List<QualityOrgScoreDO> scores = loadScores(paragraph.getRuleSetId(),
                    reqVO.getAssessStart(), reqVO.getAssessEnd());
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("paraType", paragraph.getParaType());
            block.put("title", paragraph.getTitle());
            block.put("sortNo", paragraph.getSortNo());
            if (QualityBriefParagraphDO.TYPE_TABLE.equals(paragraph.getParaType())) {
                List<QualityBriefColumnDO> columns = columnMapper.selectListByParagraphId(paragraph.getId());
                block.put("columns", columns.stream().map(column -> Map.of(
                        "fieldName", StrUtil.nullToEmpty(column.getFieldName()),
                        "colTitle", StrUtil.nullToEmpty(column.getColTitle()),
                        "dataField", StrUtil.nullToEmpty(column.getDataField()),
                        "format", StrUtil.nullToEmpty(column.getFormat()))).toList());
                block.put("rows", scores.stream().map(score -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("orgName", score.getOrgName());
                    row.put("orgCode", score.getOrgCode());
                    row.put("scoreRate", score.getScoreRate());
                    row.put("passRuleCount", score.getPassRuleCount());
                    row.put("totalRuleCount", score.getTotalRuleCount());
                    row.put("regionRank", score.getRegionRank());
                    return row;
                }).toList());
            } else {
                block.put("content", renderVariables(paragraph.getContentText(), scores, reqVO));
            }
            content.add(block);
        }
        return content;
    }

    /**
     * 加载分数。
     */
    private List<QualityOrgScoreDO> loadScores(Long ruleSetId, String periodStart, String periodEnd) {
        LambdaQueryWrapperX<QualityOrgScoreDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.ge(QualityOrgScoreDO::getPeriodStart, periodStart)
                .le(QualityOrgScoreDO::getPeriodEnd, periodEnd)
                .orderByAsc(QualityOrgScoreDO::getRegionRank);
        if (ruleSetId != null) {
            wrapper.eq(QualityOrgScoreDO::getRuleSetId, ruleSetId);
        }
        return orgScoreMapper.selectList(wrapper);
    }

    /**
     * 将质量简报模板变量替换为当前周期的统计及评分内容。
     */
    private String renderVariables(String text, List<QualityOrgScoreDO> scores,
            QualityBriefGenerateReqVO reqVO) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        // 排除合计行（orgCode=ALL），统计真实机构
        List<QualityOrgScoreDO> orgScores = scores.stream()
                .filter(score -> !"ALL".equals(score.getOrgCode()))
                .toList();
        List<QualityOrgScoreDO> statScores = orgScores.isEmpty() ? scores : orgScores;
        long totalOrg = statScores.size();
        BigDecimal passRate = statScores.isEmpty() ? BigDecimal.ZERO
                : statScores.stream().map(QualityOrgScoreDO::getScoreRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(statScores.size()), 2, java.math.RoundingMode.HALF_UP);
        long qualifiedOrg = statScores.stream()
                .filter(score -> score.getScoreRate().compareTo(QUALIFIED_RATE) >= 0).count();
        List<String> lowScoreOrgs = statScores.stream()
                .filter(score -> score.getScoreRate().compareTo(LOW_RATE) < 0)
                .map(QualityOrgScoreDO::getOrgName).toList();
        List<String> bottomOrgs = statScores.stream()
                .sorted(Comparator.comparing(QualityOrgScoreDO::getScoreRate))
                .limit(BOTTOM_COUNT)
                .map(QualityOrgScoreDO::getOrgName).toList();
        String cycleLabel = formatCycleLabel(reqVO.getAssessStart(), reqVO.getAssessEnd());
        String trend = buildTrend(scores, passRate, reqVO);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("${totalOrg}", String.valueOf(totalOrg));
        variables.put("${passRate}", passRate + "%");
        variables.put("${paixuOrg}", String.valueOf(qualifiedOrg));
        variables.put("${lowScoreOrgList}", CollUtil.isEmpty(lowScoreOrgs) ? "无" : String.join("、", lowScoreOrgs));
        variables.put("${lowScoreOrgCount}", String.valueOf(lowScoreOrgs.size()));
        variables.put("${bottomList}", CollUtil.isEmpty(bottomOrgs) ? "无" : String.join("、", bottomOrgs));
        variables.put("${cycleLabel}", cycleLabel);
        variables.put("${trend}", trend);
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 格式化周期显示名称。
     */
    private String formatCycleLabel(String start, String end) {
        String startLabel = start.length() >= 6
                ? start.substring(0, 4) + "年" + Integer.parseInt(start.substring(4, 6)) + "月" : start;
        if (Objects.equals(start, end)) {
            return startLabel;
        }
        String endLabel = end.length() >= 6
                ? end.substring(0, 4) + "年" + Integer.parseInt(end.substring(4, 6)) + "月" : end;
        return startLabel + "至" + endLabel;
    }

    /**
     * 与上期（评估开始周期的前一个月）平均得分率对比生成趋势描述
     */
    private String buildTrend(List<QualityOrgScoreDO> currentScores, BigDecimal currentRate,
            QualityBriefGenerateReqVO reqVO) {
        String prevPeriod = prevMonth(reqVO.getAssessStart());
        if (prevPeriod == null) {
            return "与上期持平";
        }
        List<Long> ruleSetIds = currentScores.stream()
                .map(QualityOrgScoreDO::getRuleSetId).filter(Objects::nonNull).distinct().toList();
        LambdaQueryWrapperX<QualityOrgScoreDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(QualityOrgScoreDO::getPeriodStart, prevPeriod);
        if (!ruleSetIds.isEmpty()) {
            wrapper.in(QualityOrgScoreDO::getRuleSetId, ruleSetIds);
        }
        List<QualityOrgScoreDO> prevScores = orgScoreMapper.selectList(wrapper);
        if (prevScores.isEmpty()) {
            return "与上期持平";
        }
        BigDecimal prevRate = prevScores.stream().map(QualityOrgScoreDO::getScoreRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(prevScores.size()), 2, java.math.RoundingMode.HALF_UP);
        int compare = currentRate.compareTo(prevRate);
        BigDecimal diff = currentRate.subtract(prevRate).abs();
        if (compare > 0) {
            return "较上期上升 " + diff + " 个百分点";
        }
        if (compare < 0) {
            return "较上期下降 " + diff + " 个百分点";
        }
        return "与上期持平";
    }

    /**
     * 按简报周期计算上一月的周期标识。
     */
    private String prevMonth(String period) {
        if (StrUtil.isBlank(period) || period.length() < 6) {
            return null;
        }
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(4, 6));
        month--;
        if (month == 0) {
            year--;
            month = 12;
        }
        return String.format("%04d%02d", year, month);
    }

    /**
     * 读取并校验质量简报记录存在，不存在时抛出业务异常。
     */
    private QualityBriefDO validateExists(Long id) {
        QualityBriefDO brief = id == null ? null : briefMapper.selectById(id);
        if (brief == null) {
            throw exception(QUALITY_BRIEF_NOT_EXISTS);
        }
        return brief;
    }

}
