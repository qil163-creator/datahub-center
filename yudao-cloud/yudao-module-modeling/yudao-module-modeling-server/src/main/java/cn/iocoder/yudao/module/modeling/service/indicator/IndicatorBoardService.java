package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorBoardDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorBoardMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_BOARD_CONFLICT;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_BOARD_FORBIDDEN;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_BOARD_INVALID;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_BOARD_NOT_EXISTS;

/**
 * 指标看板：只保存组件的查询定义，不保存结果；展示时前端逐个组件调用指标查询，结果按查看者身份脱敏。
 * <p>
 * 数据范围：有查询权限的用户平台内可见全部看板（看板本身不含数据）；只有创建人可以修改、删除。
 */
@Service
public class IndicatorBoardService {

    @Resource
    private IndicatorBoardMapper boardMapper;

    /**
     * 看板列表（不含组件明细）。
     */
    public List<IndicatorBoardRespVO> list(String name) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        List<IndicatorBoardRespVO> result = new ArrayList<>();
        for (IndicatorBoardDO board : boardMapper.selectListByName(StrUtil.trimToNull(name))) {
            IndicatorBoardRespVO vo = toResp(board, actor);
            vo.setWidgetCount(widgets(board).size());
            vo.setWidgets(null);
            result.add(vo);
        }
        return result;
    }

    /**
     * 看板详情。
     */
    public IndicatorBoardRespVO get(Long id) {
        return toResp(required(id), SecurityFrameworkUtils.getLoginUserId());
    }

    /**
     * 新建看板。
     */
    public Long create(IndicatorBoardSaveReqVO req) {
        validate(req.getWidgets());
        IndicatorBoardDO board = new IndicatorBoardDO();
        board.setName(req.getName().trim());
        board.setDescription(StrUtil.trimToNull(req.getDescription()));
        board.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        board.setWidgetsJson(JsonUtils.toJsonString(req.getWidgets()));
        board.setRevision(0);
        boardMapper.insert(board);
        return board.getId();
    }

    /**
     * 修改本人看板（按修订号 CAS）。
     */
    public void update(IndicatorBoardSaveReqVO req) {
        if (req.getId() == null || req.getExpectedRevision() == null) {
            throw exception(INDICATOR_BOARD_INVALID, "修改时必须提供编号与修订号");
        }
        IndicatorBoardDO current = owned(req.getId());
        validate(req.getWidgets());
        IndicatorBoardDO update = new IndicatorBoardDO();
        update.setId(current.getId());
        update.setOwnerUserId(current.getOwnerUserId());
        update.setName(req.getName().trim());
        update.setDescription(StrUtil.trimToNull(req.getDescription()));
        update.setWidgetsJson(JsonUtils.toJsonString(req.getWidgets()));
        if (boardMapper.updateByRevision(update, req.getExpectedRevision()) != 1) {
            throw exception(INDICATOR_BOARD_CONFLICT);
        }
    }

    /**
     * 删除本人看板。
     */
    public void delete(Long id) {
        owned(id);
        boardMapper.deleteById(id);
    }

    /**
     * 解析看板组件；历史数据无法解析时视为无组件。
     */
    public static List<IndicatorBoardSaveReqVO.Widget> widgets(IndicatorBoardDO board) {
        if (StrUtil.isBlank(board.getWidgetsJson())) {
            return List.of();
        }
        try {
            List<IndicatorBoardSaveReqVO.Widget> widgets = JsonUtils.parseObject(board.getWidgetsJson(),
                    new TypeReference<List<IndicatorBoardSaveReqVO.Widget>>() {
                    });
            return widgets == null ? List.of() : widgets;
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    // ==================== 内部 ====================

    /**
     * 组件标识在看板内唯一。字段格式由 Bean Validation 校验，指标与维度在展示查询时由语义层校验。
     */
    static void validate(List<IndicatorBoardSaveReqVO.Widget> widgets) {
        Set<String> keys = new HashSet<>();
        for (IndicatorBoardSaveReqVO.Widget widget : widgets) {
            if (!keys.add(widget.getKey())) {
                throw exception(INDICATOR_BOARD_INVALID, "组件标识重复：" + widget.getKey());
            }
        }
    }

    /**
     * 读取存在的看板。
     */
    private IndicatorBoardDO required(Long id) {
        IndicatorBoardDO board = id == null ? null : boardMapper.selectById(id);
        if (board == null) {
            throw exception(INDICATOR_BOARD_NOT_EXISTS);
        }
        return board;
    }

    /**
     * 读取本人看板。
     */
    private IndicatorBoardDO owned(Long id) {
        IndicatorBoardDO board = required(id);
        if (!Objects.equals(board.getOwnerUserId(), SecurityFrameworkUtils.getLoginUserId())) {
            throw exception(INDICATOR_BOARD_FORBIDDEN);
        }
        return board;
    }

    /**
     * 转换响应。
     */
    private static IndicatorBoardRespVO toResp(IndicatorBoardDO board, Long actor) {
        IndicatorBoardRespVO vo = new IndicatorBoardRespVO();
        vo.setId(board.getId());
        vo.setName(board.getName());
        vo.setDescription(board.getDescription());
        vo.setOwnerUserId(board.getOwnerUserId());
        vo.setEditable(Objects.equals(board.getOwnerUserId(), actor));
        List<IndicatorBoardSaveReqVO.Widget> widgets = widgets(board);
        vo.setWidgets(widgets);
        vo.setWidgetCount(widgets.size());
        vo.setRevision(board.getRevision());
        vo.setUpdateTime(board.getUpdateTime());
        return vo;
    }
}
