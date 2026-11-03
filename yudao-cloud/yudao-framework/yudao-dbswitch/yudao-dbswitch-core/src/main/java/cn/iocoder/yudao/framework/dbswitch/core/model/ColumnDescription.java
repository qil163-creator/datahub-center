package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库列描述符信息定义(Column Description)
 *
 * @author jrl
 */
public class ColumnDescription {

    private String fieldName;

    private String labelName;

    private String fieldTypeName;

    private String filedTypeClassName;

    private Integer valType;

    private Integer length;

    private Integer precision;

    private int fieldType;

    private int displaySize;

    private int scaleSize;

    private int precisionSize;

    private boolean isAutoIncrement;

    private boolean isNullable;

    private String remarks;

    private boolean signed = false;

    private ProductTypeEnum dbtype;

    //索引是否可以不唯一
    private boolean nonIndexUnique;

    //索引类别
    private String indexQualifier;

    //索引名称
    private String indexName;

    private List<String> indexes;

    private short indexType;

    private String ascOrDesc;

    //默认值
    private String defaultValue;

    //是否是主键
    private boolean isPk;

    private ColumnMetadataEvidence metadataEvidence = ColumnMetadataEvidence.unknown();

    /**
     * 返回元数据证据。
     */
    public ColumnMetadataEvidence getMetadataEvidence() {
        return metadataEvidence;
    }

    /**
     * 设置元数据证据。
     */
    public void setMetadataEvidence(ColumnMetadataEvidence evidence) {
        this.metadataEvidence = java.util.Objects.requireNonNull(evidence);
    }

    /**
     * 返回字段名。
     */
    public String getFieldName() {
        if (null != this.fieldName) {
            return fieldName;
        }

        return this.labelName;
    }

    /**
     * 设置字段名。
     */
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * 返回列在结果集中的标签名称。
     */
    public String getLabelName() {
        if (null != labelName) {
            return labelName;
        }

        return this.fieldName;
    }

    /**
     * 设置标签名称。
     */
    public void setLabelName(String labalName) {
        this.labelName = labalName;
    }

    /**
     * 返回字段类型名称。
     */
    public String getFieldTypeName() {
        return fieldTypeName;
    }

    /**
     * 设置字段类型名称。
     */
    public void setFieldTypeName(String fieldTypeName) {
        this.fieldTypeName = fieldTypeName;
    }

    /**
     * 返回字段类型类名称。
     */
    public String getFiledTypeClassName() {
        return filedTypeClassName;
    }

    /**
     * 设置字段类型类名称。
     */
    public void setFiledTypeClassName(String filedTypeClassName) {
        this.filedTypeClassName = filedTypeClassName;
    }

    /**
     * 返回字段类型。
     */
    public int getFieldType() {
        return fieldType;
    }

    /**
     * 设置字段类型。
     */
    public void setFieldType(int fieldType) {
        this.fieldType = fieldType;
    }

    /**
     * 返回值类型。
     */
    public Integer getValType() {
        return valType;
    }

    /**
     * 设置值类型。
     */
    public void setValType(Integer valType) {
        this.valType = valType;
    }

    /**
     * 返回长度。
     */
    public Integer getLength() {
        return length;
    }

    /**
     * 设置长度。
     */
    public void setLength(Integer length) {
        this.length = length;
    }

    /**
     * 返回精度。
     */
    public Integer getPrecision() {
        return precision;
    }

    /**
     * 设置精度。
     */
    public void setPrecision(Integer precision) {
        this.precision = precision;
    }

    /**
     * 返回显示长度。
     */
    public int getDisplaySize() {
        return displaySize;
    }

    /**
     * 设置显示长度。
     */
    public void setDisplaySize(int displaySize) {
        this.displaySize = displaySize;
    }

    /**
     * 返回小数位数大小。
     */
    public int getScaleSize() {
        return scaleSize;
    }

    /**
     * 设置小数位数大小。
     */
    public void setScaleSize(int scaleSize) {
        this.scaleSize = scaleSize;
    }

    /**
     * 返回精度。
     */
    public int getPrecisionSize() {
        return precisionSize;
    }

    /**
     * 设置精度。
     */
    public void setPrecisionSize(int precisionSize) {
        this.precisionSize = precisionSize;
    }

    /**
     * 返回是否自动增量。
     */
    public boolean isAutoIncrement() {
        return isAutoIncrement;
    }

    /**
     * 设置是否自动增量。
     */
    public void setAutoIncrement(boolean isAutoIncrement) {
        this.isAutoIncrement = isAutoIncrement;
    }

    /**
     * 返回是否可空。
     */
    public boolean isNullable() {
        return isNullable;
    }

    /**
     * 设置是否可空。
     */
    public void setNullable(boolean isNullable) {
        this.isNullable = isNullable;
    }

    /**
     * 返回有符号。
     */
    public boolean isSigned() {
        return signed;
    }

    /**
     * 设置有符号。
     */
    public void setSigned(boolean signed) {
        this.signed = signed;
    }

    /**
     * 返回数据库类型。
     */
    public ProductTypeEnum getDbType() {
        return this.dbtype;
    }

    /**
     * 设置数据库类型。
     */
    public void setDbType(ProductTypeEnum dbtype) {
        this.dbtype = dbtype;
    }

    /**
     * 返回备注。
     */
    public String getRemarks() {
        return this.remarks;
    }

    /**
     * 设置备注。
     */
    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    /**
     * 设置非索引唯一。
     */
    public void setNonIndexUnique(boolean nonIndexUnique) {
        this.nonIndexUnique = nonIndexUnique;
    }

    /**
     * 返回非索引唯一。
     */
    public boolean isNonIndexUnique() {
        return nonIndexUnique;
    }

    /**
     * 返回索引限定名。
     */
    public String getIndexQualifier() {
        return indexQualifier;
    }

    /**
     * 设置索引限定名。
     */
    public void setIndexQualifier(String indexQualifier) {
        this.indexQualifier = indexQualifier;
    }

    /**
     * 返回索引名称。
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * 设置索引名称。
     */
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    /**
     * 返回索引类型。
     */
    public short getIndexType() {
        return indexType;
    }

    /**
     * 设置索引类型。
     */
    public void setIndexType(short indexType) {
        this.indexType = indexType;
    }

    /**
     * 返回升序或降序。
     */
    public String getAscOrDesc() {
        return ascOrDesc;
    }

    /**
     * 设置升序或降序。
     */
    public void setAscOrDesc(String ascOrDesc) {
        this.ascOrDesc = ascOrDesc;
    }

    /**
     * 返回默认值。
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 设置默认值。
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * 返回是否主键。
     */
    public boolean isPk() {
        return isPk;
    }

    /**
     * 设置是否主键。
     */
    public void setPk(boolean pk) {
        isPk = pk;
    }

    /**
     * 返回索引集合。
     */
    public List<String> getIndexes() {
        if (indexes == null) {
            setIndexes(new ArrayList<>());
        }
        return indexes;
    }

    /**
     * 设置索引集合。
     */
    public void setIndexes(List<String> indexes) {
        this.indexes = indexes;
    }

    /**
     * 返回当前COPY。
     */
    public ColumnDescription copy() {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(fieldName);
        description.setLabelName(labelName);
        description.setFieldTypeName(fieldTypeName);
        description.setFiledTypeClassName(filedTypeClassName);
        description.setValType(valType);
        description.setLength(length);
        description.setPrecision(precision);
        description.setFieldType(fieldType);
        description.setDisplaySize(displaySize);
        description.setScaleSize(scaleSize);
        description.setPrecisionSize(precisionSize);
        description.setAutoIncrement(isAutoIncrement);
        description.setNullable(isNullable);
        description.setRemarks(remarks);
        description.setSigned(signed);
        description.setDbType(dbtype);
        description.setNonIndexUnique(nonIndexUnique);
        description.setIndexQualifier(indexQualifier);
        description.setIndexName(indexName);
        description.setIndexes(indexes == null ? null : new ArrayList<>(indexes));
        description.setIndexType(indexType);
        description.setAscOrDesc(ascOrDesc);
        description.setDefaultValue(defaultValue);
        description.setPk(isPk);
        description.setMetadataEvidence(metadataEvidence);
        return description;
    }

    /////////////////////////////////////////////

    public ColumnMetaData getMetaData() {
        return new ColumnMetaData(this);
    }
}
