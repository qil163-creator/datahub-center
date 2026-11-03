package cn.iocoder.yudao.module.dataforge.service.push;

/**
 * 增量水位计算：区间为 (prevEnd, currentMax]
 *
 * @author Li
 */
public final class DataPushIncreaseSupport {

    /**
     * 限制推送增量支持工具的实例化，统一通过静态能力使用。
     */
    private DataPushIncreaseSupport() {
    }

    public record Watermark(String startVal, String endVal) {

    }

    /**
     * @param prevEnd    上次成功水位的 end；首次为 null
     * @param currentMax 当前 MAX(increment_column)；无行时为 null
     * @return null 表示无需推送（无行，或水位未前进）
     */
    public static Watermark build(String prevEnd, String currentMax) {
        if (currentMax == null) {
            return null;
        }
        // 水位未前进：区间为空，无需再推
        if (prevEnd != null && prevEnd.equals(currentMax)) {
            return null;
        }
        return new Watermark(prevEnd, currentMax);
    }

    /**
     * 校验长度。
     *
     * @param startVal 开始值
     * @param endVal 结束值
     * @param maxLength 最大值长度
     */
    public static void validateLength(String startVal, String endVal, int maxLength) {
        if (maxLength <= 0) {
            return;
        }
        if (startVal != null && startVal.length() > maxLength) {
            throw new IllegalArgumentException("增量水位下界超过 " + maxLength + " 字符");
        }
        if (endVal != null && endVal.length() > maxLength) {
            throw new IllegalArgumentException("增量水位上界超过 " + maxLength + " 字符");
        }
    }

}
