package cn.iocoder.yudao.framework.etl.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ETL 字段元信息（字段契约推导结果）
 *
 * @author Li
 */
@Data
@NoArgsConstructor
public class EtlFieldMeta {

    /**
     * 字段来源追踪，只在 {@link EtlSchemaService#traceOrigins} 中填充。
     * transient 且不生成 getter/setter：不进入 Jackson、hutool 序列化，不参与相等性和字符串表示，
     * 因而不改变既有的字段契约、证据哈希和设计器响应。
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient List<EtlFieldOrigin> origins = List.of();

    /** 经过无法在设计期追溯的组件（脚本、表达式计算）时为 true。 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient boolean originsUnknown;

    private String name;

    private String dataType;

    /** Null 表示数据源或转换尚未证明此属性。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean nullable;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer precision;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer scale;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String timezone;

    /** 仅表示字符长度，不将数值精度或 JDBC 展示宽度当作长度。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long length;

    /** 可追溯的字段来源展示名；不包含连接字符串或凭据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sourceName;

    /** 保留既有八参数全字段构造器（来源追踪字段不属于字段契约）。 */
    public EtlFieldMeta(String name, String dataType, Boolean nullable, Integer precision, Integer scale,
                        String timezone, Long length, String sourceName) {
        this.name = name;
        this.dataType = dataType;
        this.nullable = nullable;
        this.precision = precision;
        this.scale = scale;
        this.timezone = timezone;
        this.length = length;
        this.sourceName = sourceName;
    }

    /** 保留既有六参数字段契约构造器。 */
    public EtlFieldMeta(String name, String dataType, Boolean nullable, Integer precision, Integer scale, String timezone) {
        this(name, dataType, nullable, precision, scale, timezone, null, null);
    }

    /**
     * 使用给定参数初始化 {@code EtlFieldMeta}。
     */
    public EtlFieldMeta(String name, String dataType) {
        this(name, dataType, null, null, null, null);
    }

    /**
     * 返回当前COPY。
     */
    public EtlFieldMeta copy() {
        return new EtlFieldMeta(name, dataType, nullable, precision, scale, timezone, length, sourceName)
                .tracedLike(this);
    }

    /**
     * 根据当前输入构建并返回 {@code EtlFieldMeta}。
     */
    public EtlFieldMeta renamed(String newName) {
        return new EtlFieldMeta(newName, dataType, nullable, precision, scale, timezone, length, sourceName)
                .tracedLike(this);
    }

    // ==================== 来源追踪 ====================

    /**
     * 返回字段来源；未开启追踪或常量字段为空列表。
     */
    public List<EtlFieldOrigin> origins() {
        return origins == null ? List.of() : origins;
    }

    /**
     * 是否经过无法追溯的组件。
     */
    public boolean originsUnknown() {
        return originsUnknown;
    }

    /**
     * 设置为输入节点上的原始字段。
     */
    EtlFieldMeta originAt(String nodeNo) {
        origins = List.of(new EtlFieldOrigin(nodeNo, name, LineageTransform.IDENTITY, false));
        originsUnknown = false;
        return this;
    }

    /**
     * 原样继承另一字段的来源。
     */
    EtlFieldMeta tracedLike(EtlFieldMeta source) {
        origins = source == null ? List.of() : source.origins();
        originsUnknown = source != null && source.originsUnknown;
        return this;
    }

    /**
     * 由若干来源字段经变换得到。
     */
    EtlFieldMeta derivedFrom(Collection<EtlFieldMeta> sources, LineageTransform transform, boolean masking) {
        LinkedHashSet<EtlFieldOrigin> merged = new LinkedHashSet<>();
        boolean unknown = false;
        for (EtlFieldMeta source : sources) {
            if (source == null) {
                continue;
            }
            source.origins().forEach(origin -> merged.add(origin.under(transform, masking)));
            unknown = unknown || source.originsUnknown;
        }
        origins = List.copyOf(new ArrayList<>(merged));
        originsUnknown = unknown;
        return this;
    }

    /**
     * 由单个来源字段经变换得到。
     */
    EtlFieldMeta derivedFrom(EtlFieldMeta source, LineageTransform transform, boolean masking) {
        return derivedFrom(source == null ? List.of() : List.of(source), transform, masking);
    }

    /**
     * 合并另一路上游同名字段的来源（多输入节点）。
     */
    EtlFieldMeta mergedWith(EtlFieldMeta other) {
        LinkedHashSet<EtlFieldOrigin> merged = new LinkedHashSet<>(origins());
        merged.addAll(other.origins());
        origins = List.copyOf(merged);
        originsUnknown = originsUnknown || other.originsUnknown;
        return this;
    }

    /**
     * 标记来源无法在设计期追溯。
     */
    EtlFieldMeta markOriginsUnknown() {
        originsUnknown = true;
        return this;
    }

}
