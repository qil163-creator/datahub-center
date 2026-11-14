package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.CreateTableColumnParser;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.SqlParseFieldsReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.SqlParseFieldsRespVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_SQL_PARSE_FAILED;

/**
 * 字段设计的“SQL 解析”：建表语句解析委托 DB Switch 的 {@link CreateTableColumnParser}，
 * 本类只把解析结果映射为模型字段并翻译错误。不连接数据库、不执行语句、不写入任何设计。
 */
@Service
public class ModelSqlParseService {

    /**
     * 解析建表语句为模型字段。
     *
     * @param request 方言与建表语句
     * @return 表名、表注释与字段
     */
    public SqlParseFieldsRespVO parse(SqlParseFieldsReqVO request) {
        ProductTypeEnum product;
        try {
            product = ProductTypeEnum.valueOf(StrUtil.trim(request.getDialect()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw exception(MODEL_SQL_PARSE_FAILED, "不支持的数据库类型 " + request.getDialect());
        }
        CreateTableColumnParser.Result result;
        try {
            result = CreateTableColumnParser.parse(product, request.getSql());
        } catch (UnsupportedOperationException unsupported) {
            throw exception(MODEL_SQL_PARSE_FAILED, "暂不支持解析该数据库类型的建表语句");
        } catch (IllegalArgumentException invalid) {
            throw exception(MODEL_SQL_PARSE_FAILED, invalid.getMessage());
        }
        List<ModelFieldItem> fields = new ArrayList<>();
        int sort = 1;
        for (CreateTableColumnParser.Column column : result.columns()) {
            fields.add(toField(column, sort++));
        }
        SqlParseFieldsRespVO response = new SqlParseFieldsRespVO();
        response.setTableName(result.tableName());
        response.setTableComment(result.tableComment());
        response.setFields(fields);
        return response;
    }

    /** 中文名取字段注释，没有注释时沿用字段名；与逆向导入字段的取值规则一致。 */
    static ModelFieldItem toField(CreateTableColumnParser.Column column, int sort) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldName(column.name());
        item.setCnName(StrUtil.blankToDefault(column.comment(), column.name()));
        item.setDataType(column.typeName());
        item.setDataLength(column.length());
        item.setDataPrecision(column.precision());
        item.setDataScale(column.scale());
        item.setDefaultValue(column.defaultValue());
        item.setComment(StrUtil.nullToEmpty(column.comment()));
        item.setPkFlag(column.primaryKey());
        item.setNullableFlag(column.nullable());
        item.setSort(sort);
        return item;
    }
}
