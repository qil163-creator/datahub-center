package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileValueRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 探查结果 DO → VO 转换：比率与平均值转十进制字符串，Top 值附带占非空数比例。
 *
 * @author Li
 */
public final class ProfileConvert {

    /** 比率保留的小数位 */
    public static final int RATE_SCALE = 6;

    /**
     * 工具类不实例化。
     */
    private ProfileConvert() {
    }

    /**
     * 计算 numerator / denominator（0~1，保留 6 位，四舍五入）；分母为 0 时返回 null。
     */
    public static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 十进制转字符串，不使用科学计数法。
     */
    public static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 探查记录转换；执行人昵称由调用方填充。
     */
    public static ProfileRunRespVO run(ProfileRunDO run) {
        return run == null ? null : BeanUtils.toBean(run, ProfileRunRespVO.class);
    }

    /**
     * 字段指标及其 Top 值转换。
     */
    public static ProfileColumnRespVO column(ProfileColumnDO column, List<ProfileValueDO> values) {
        ProfileColumnRespVO vo = new ProfileColumnRespVO();
        vo.setId(column.getId());
        vo.setMetaColumnId(column.getMetaColumnId());
        vo.setColumnName(column.getColumnName());
        vo.setDataType(column.getDataType());
        vo.setColumnKind(column.getColumnKind());
        vo.setOrdinalPosition(column.getOrdinalPosition());
        vo.setNonNullCount(column.getNonNullCount());
        vo.setNullCount(column.getNullCount());
        vo.setNullRate(decimal(scaled(column.getNullRate())));
        vo.setDistinctCount(column.getDistinctCount());
        vo.setUniqueRate(decimal(scaled(column.getUniqueRate())));
        vo.setMinValue(column.getMinValue());
        vo.setMaxValue(column.getMaxValue());
        vo.setValueMasked(Objects.equals(column.getValueMasked(), 1));
        vo.setMaskReason(column.getMaskReason());
        vo.setAvgLength(decimal(column.getAvgLength()));
        vo.setMinLength(column.getMinLength());
        vo.setMaxLength(column.getMaxLength());
        vo.setAvgValue(decimal(column.getAvgValue()));
        long nonNull = column.getNonNullCount() == null ? 0L : column.getNonNullCount();
        vo.setTopValues(values.stream().map(value -> {
            ProfileValueRespVO item = new ProfileValueRespVO();
            item.setRankNo(value.getRankNo());
            item.setValue(value.getValueText());
            item.setCount(value.getValueCount());
            item.setRatio(decimal(rate(value.getValueCount() == null ? 0L : value.getValueCount(), nonNull)));
            item.setMasked(Objects.equals(value.getValueMasked(), 1));
            return item;
        }).toList());
        return vo;
    }

    /**
     * 字段指标列表转换。
     */
    public static List<ProfileColumnRespVO> columns(List<ProfileColumnDO> columns,
            Map<Long, List<ProfileValueDO>> values) {
        return columns.stream().map(column -> column(column, values.getOrDefault(column.getId(), List.of()))).toList();
    }

    /**
     * 比率统一为 6 位小数输出。
     */
    private static BigDecimal scaled(BigDecimal rate) {
        return rate == null ? null : rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

}
