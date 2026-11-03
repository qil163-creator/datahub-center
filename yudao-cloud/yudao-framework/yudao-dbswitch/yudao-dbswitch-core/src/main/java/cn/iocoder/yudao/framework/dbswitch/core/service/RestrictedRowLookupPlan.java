package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.KeyValue;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;

import java.util.*;

/** 精确相等定位信息，不是授权许可。不接受 SQL 片段或任意客户端表达式。 */
public final class RestrictedRowLookupPlan {

    private final RestrictedSelectPlan select;

    private final List<Object> parameters;

    private final Map<String, KeyValue> keys;

    /**
     * 校验构造参数并初始化 {@code RestrictedRowLookupPlan}。
     */
    private RestrictedRowLookupPlan(RestrictedSelectPlan select, List<Object> parameters, Map<String, KeyValue> keys) {
        this.select = Objects.requireNonNull(select);
        this.parameters = List.copyOf(parameters);
        this.keys = Map.copyOf(keys);
    }

    /**
     * 返回查询。
     */
    public RestrictedSelectPlan select() {
        return select;
    }

    /**
     * 返回参数。
     */
    public List<Object> parameters() {
        return parameters;
    }

    /**
     * 拒绝当前实现未支持的 {@code create} 操作。
     */
    public static RestrictedRowLookupPlan create(ProductTypeEnum product, String schema, String table,
            Map<String, KeyValue> keys) {
        if (product != ProductTypeEnum.POSTGRESQL) {
            throw new UnsupportedOperationException("Row locator is only verified for PostgreSQL");
        }
        if (keys == null || keys.isEmpty() || keys.size() > 4) {
            throw new IllegalArgumentException("One to four row keys required");
        }
        List<Object> values = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (var key : new TreeMap<>(keys).entrySet()) {
            terms.add(RestrictedSelectGuard.quotePhysicalIdentifier(product, key.getKey()) + " = ?");
            values.add(Objects.requireNonNull(key.getValue()).scalar());
        }
        String sql = "SELECT * FROM " + RestrictedSelectGuard.qualifiedTableName(product, schema, table)
                + " WHERE " + String.join(" AND ", terms);
        return new RestrictedRowLookupPlan(RestrictedSelectGuard.parseBound(product, sql, schema), values, keys);
    }

    /** 权限 API 检查查询 AST；键值在整个流程中保持为私有绑定参数。 */
    public String authorizationSql() {
        return select.getNormalizedSql();
    }

    public record BoundQuery(String sql, List<Object> parameters) {

        /**
         * 初始化 {@code BoundQuery}。
         */
        public BoundQuery {
            parameters = List.copyOf(parameters);
        }
    }

    /** 授权后仅绑定原始键相等条件。策略重写不得添加、移除或复用键绑定。 */
    public BoundQuery bindAuthorized(String authorizedSql) {
        var authorized = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL, authorizedSql,
                select.getSchemaName());
        if (!Objects.equals(select.getSchemaName(), authorized.getSchemaName()) || !Objects.equals(
                select.getTableName(), authorized.getTableName())) {
            throw new IllegalArgumentException("Authorized row locator changed table");
        }
        Map<Integer, Object> values = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        var ast = SQLUtils.parseStatements(authorized.getNormalizedSql(), DbType.postgresql);
        for (var statement : ast) {
            statement.accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr bind) {
                    if (!"?".equals(
                            bind.getName()) || !(bind.getParent() instanceof com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr binary)
                            || binary.getOperator() != com.alibaba.druid.sql.ast.expr.SQLBinaryOperator.Equality) {
                        throw new IllegalArgumentException("Unknown row locator bind");
                    }
                    var column = binary.getLeft() == bind ? binary.getRight() : binary.getLeft();
                    if (column instanceof com.alibaba.druid.sql.ast.expr.SQLPropertyExpr property) {
                        column = new com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr(property.getName());
                    }
                    if (!(column instanceof com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr)) {
                        throw new IllegalArgumentException("Row locator bind is not a physical key equality");
                    }
                    var reference = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                            "SELECT " + SQLUtils.toSQLString(column, DbType.postgresql) + " FROM "
                                    + RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL,
                                    select.getSchemaName(), select.getTableName()),
                            select.getSchemaName());
                    String name = reference.getSourceColumns().iterator().next();
                    var key = keys.get(name);
                    int index = bind.getIndex();
                    if (key == null || !seen.add(name) || index < 0 || index >= keys.size()
                            || values.putIfAbsent(index, key.scalar()) != null) {
                        throw new IllegalArgumentException("Row locator bind changed or duplicated");
                    }
                    return false;
                }
            });
        }
        if (values.size() != keys.size() || !seen.equals(keys.keySet())) {
            throw new IllegalArgumentException("Row locator bind is missing");
        }
        return new BoundQuery(authorized.getNormalizedSql(), new ArrayList<>(values.values()));
    }
}
