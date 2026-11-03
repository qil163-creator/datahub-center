package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;

/** Finite, business-neutral operators for a single metadata-approved column predicate. */
public enum QueryFilterOperator {
    EQ, NE, GT, GE, LT, LE, CONTAINS, NOT_CONTAINS, IS_NULL, IS_NOT_NULL, IS_EMPTY, IS_NOT_EMPTY;

    /** Operators without a value distinguish SQL NULL from the empty string. */
    public boolean requiresValue() {
        return this != IS_NULL && this != IS_NOT_NULL && this != IS_EMPTY && this != IS_NOT_EMPTY;
    }

    /** Applicability uses the portable {@link ColumnMetaData} type, never a client-provided type. */
    public boolean supports(int fieldType) {
        if (this == IS_NULL || this == IS_NOT_NULL) {
            return true;
        }
        if (fieldType == ColumnMetaData.TYPE_STRING) {
            return this == EQ || this == NE || this == CONTAINS || this == NOT_CONTAINS
                    || this == IS_EMPTY || this == IS_NOT_EMPTY;
        }
        if (ColumnMetaData.isNumeric(fieldType) || fieldType == ColumnMetaData.TYPE_DATE
                || fieldType == ColumnMetaData.TYPE_TIMESTAMP || fieldType == ColumnMetaData.TYPE_TIME) {
            return this == EQ || this == NE || this == GT || this == GE || this == LT || this == LE;
        }
        return fieldType == ColumnMetaData.TYPE_BOOLEAN && (this == EQ || this == NE);
    }

    /** Only stable uppercase codes are accepted; expressions and aliases are never parsed. */
    public static QueryFilterOperator fromCode(String code) {
        if (code != null) {
            for (QueryFilterOperator operator : values()) {
                if (operator.name().equals(code)) {
                    return operator;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported query filter operator");
    }
}
