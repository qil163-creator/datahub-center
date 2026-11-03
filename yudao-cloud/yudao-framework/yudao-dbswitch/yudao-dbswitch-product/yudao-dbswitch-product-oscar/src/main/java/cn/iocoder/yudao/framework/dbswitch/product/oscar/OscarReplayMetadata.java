package cn.iocoder.yudao.framework.dbswitch.product.oscar;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaFingerprint;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.util.OscarRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedReplayColumn;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.sql.*;
import java.util.*;

/** 原始 ShenTong 标量重放配置；目录标记及锁定行为独立于 Oracle 验证。 */
public final class OscarReplayMetadata {

    /**
     * 初始化 {@code OscarReplayMetadata}。
     */
    private OscarReplayMetadata() {
    }

    public record Shape(TableSchemaMetadata schema, Map<String, LockedReplayColumn> columns, String physical,
                        String fingerprint) {

    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    public static void product(Connection c) throws SQLException {
        if (!"OSCAR".equals(c.getMetaData().getDatabaseProductName())
                || !OscarNativeCatalog.verifiedVersion(c.getMetaData().getDatabaseProductVersion())) {
            throw new SQLException("Oscar replay requires verified ShenTong V8.0.251217");
        }
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || !name.matches("[A-Z_][A-Z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Oscar replay requires uppercase ordinary physical identifiers");
        }
        return OscarSqlNames.quote(name);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    public static void lock(Connection c, String relation, boolean writing, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("Oscar table lock requires an owned transaction");
        }
        try (var p = c.createStatement()) {
            p.setQueryTimeout(timeout);
            p.execute("LOCK TABLE " + relation + " IN " + (writing ? "EXCLUSIVE" : "SHARE") + " MODE");
        }
    }

    /**
     * 返回物理。
     */
    public static String physical(Connection c, String schema, String table, int timeout) throws SQLException {
        product(c);
        quote(schema);
        quote(table);
        return OscarRelationIdentity.fingerprint(c, schema + '.' + table);
    }

    /**
     * 读取并校验 Oscar 当前表的物理身份、列及重放约束。
     */
    public static Shape read(Connection c, String schema, String table, int timeout) throws SQLException {
        String physical = physical(c, schema, table, timeout);
        var metadata = OscarNativeCatalog.read(c, schema, table, timeout);
        long oid = oid(c, schema, table, timeout);
        var ordinals = new LinkedHashMap<String, Integer>();
        var modifiers = new HashMap<String, Integer>();
        try (var p = c.prepareStatement(
                "SELECT ATTNAME,ATTNUM,ATTTYPMOD,ATTISSET,ATTHASDEF,ATTISLOCAL,ATTISAUTO,ATTENCMOD,ATTAUTOINC,ATTGENERATED "
                        + "FROM INFO_SCHEM.SYS_ATTRIBUTE WHERE ATTRELID=? AND ATTNUM>0 AND NOT ATTISDROPPED ORDER BY ATTNUM")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2001);
            p.setLong(1, oid);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (ordinals.size() >= 2000 || r.getBoolean(4) || r.getBoolean(5) || !r.getBoolean(
                            6) || r.getBoolean(7)
                            || r.getInt(8) != 0 || r.getLong(9) != 0 || !"".equals(r.getString(10))) {
                        throw new SQLException(
                                "Oscar replay column has defaults, generation or unverified storage semantics");
                    }
                    String name = r.getString(1);
                    int ordinal = r.getInt(2);
                    if (name == null || ordinal < 1 || ordinals.put(name, ordinal) != null) {
                        throw new SQLException("Oscar replay physical columns are ambiguous");
                    }
                    modifiers.put(name, r.getInt(3));
                }
            }
        }
        if (!new ArrayList<>(ordinals.keySet()).equals(
                metadata.columns().stream().map(ColumnDescription::getFieldName).toList())) {
            throw new SQLException("Oscar replay physical column observations differ");
        }
        rejectOtherObjects(c, oid, metadata.primaryKeys().stream().map(ordinals::get).toList(), timeout);
        var columns = new LinkedHashMap<String, LockedReplayColumn>();
        for (var column : metadata.columns()) {
            var e = column.getMetadataEvidence();
            int jdbc = column.getFieldType();
            String type;
            CursorKeyContract.Domain domain = null;
            int length = 0;
            if (e.defaultExpression().state() != MetadataValue.State.ABSENT || column.isAutoIncrement()) {
                throw new SQLException("Oscar replay column defaults or identity are unsupported");
            }
            if (jdbc == Types.INTEGER) {
                type = "int";
                domain = CursorKeyContract.Domain.integer(32, false);
            } else if (jdbc == Types.SMALLINT) {
                type = "smallint";
                domain = CursorKeyContract.Domain.integer(16, false);
            } else if (jdbc == Types.BIGINT) {
                type = "bigint";
                domain = CursorKeyContract.Domain.integer(64, false);
            } else if (jdbc == Types.NUMERIC || jdbc == Types.DECIMAL) {
                Integer precision = e.numericPrecision().value(), scale = e.numericScale().value();
                if (precision == null || precision < 1 || precision > 38 || scale == null || scale < 0 || scale > precision) {
                    throw new SQLException("Oscar decimal domain is unverified");
                }
                type = "numeric(" + precision + ',' + scale + ')';
                jdbc = Types.NUMERIC;
                domain = CursorKeyContract.Domain.decimal(precision, scale, false);
            } else if (jdbc == Types.VARCHAR) {
                length = modifiers.get(column.getFieldName()) - 4;
                if (length < 1 || length > 8000 || length != column.getPrecisionSize()) {
                    throw new SQLException("Oscar varchar modifier is unverified");
                }
                type = "varchar(" + length + ')';
            } else {
                throw new SQLException("Oscar native scalar is outside the replay profile");
            }
            columns.put(column.getFieldName(),
                    new LockedReplayColumn(type, jdbc, column.isNullable(), domain, length, jdbc == Types.VARCHAR));
        }
        if (!physical.equals(physical(c, schema, table, timeout))) {
            throw new SQLException("Oscar source incarnation changed");
        }
        var source = metadata.provenance();
        var stableBuild = new MetadataProvenance(source.product(), source.serverProduct(),
                OscarNativeCatalog.SERVER_BUILD, source.method(), source.observedAt());
        var stableSchema = new TableSchemaMetadata(metadata.columns(), metadata.primaryKeys(), stableBuild,
                metadata.existence(), metadata.additionalObjects());
        return new Shape(metadata, Collections.unmodifiableMap(columns), physical, SchemaFingerprint.of(stableSchema));
    }

    /**
     * 读取数据库对象在系统目录中的标识。
     */
    public static long oid(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT c.OID FROM INFO_SCHEM.SYS_CLASS c JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE WHERE n.NSPNAME=? AND c.RELNAME=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Oscar table is not visible");
                }
                long oid = r.getLong(1);
                if (oid <= 0 || r.next()) {
                    throw new SQLException("Oscar table identity is ambiguous");
                }
                return oid;
            }
        }
    }

    /**
     * 拒绝目标表上存在当前重放路径不支持的附加对象。
     */
    private static void rejectOtherObjects(Connection c, long oid, List<Integer> keys,
            int timeout) throws SQLException {
        int constraints = 0;
        try (var p = c.prepareStatement(
                "SELECT CONRELID,CONTYPID,CONFRELID,CONTYPE,CONDEFERRABLE,CONDEFERRED,CONENABLE,CONVALIDATE,CONKEY "
                        + "FROM INFO_SCHEM.SYS_CONSTRAINT WHERE CONRELID=? OR CONFRELID=?")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, oid);
            p.setLong(2, oid);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (++constraints > 1 || keys.isEmpty() || r.getLong(1) != oid || r.getLong(2) != 0 || r.getLong(
                            3) != 0
                            || !"p".equals(r.getString(4)) || r.getBoolean(5) || r.getBoolean(6) || !r.getBoolean(
                            7) || !r.getBoolean(8)
                            || !("{" + String.join(",", keys.stream().map(String::valueOf).toList()) + "}").equals(
                            r.getString(9))) {
                        throw new SQLException("Oscar replay requires only an immediate enabled validated primary key");
                    }
                }
            }
        }
        if (constraints != (keys.isEmpty() ? 0 : 1)) {
            throw new SQLException("Oscar primary key observation differs");
        }
        try (var p = c.prepareStatement("SELECT 1 FROM INFO_SCHEM.SYS_TRIGGER WHERE BASEOBJID=? OR TGCONSTRRELID=?")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, oid);
            p.setLong(2, oid);
            try (var r = p.executeQuery()) {
                if (r.next()) {
                    throw new SQLException("Oscar replay triggers are unsupported");
                }
            }
        }
        int indexes = 0;
        try (var p = c.prepareStatement(
                "SELECT INDKEY,INDPROC,INDREFERENCE,INDISCLUSTERED,INDISUNIQUE,INDISPRIMARY,INDUSABLE,INDVISIBLE,INDKEYNUM,INDTYPE,INDEXPRS,INDPRED "
                        + "FROM INFO_SCHEM.SYS_INDEX WHERE INDRELID=?")) {
            p.setQueryTimeout(timeout);
            p.setLong(1, oid);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (++indexes > 1 || keys.isEmpty() || !String.join(" ",
                            keys.stream().map(String::valueOf).toList()).equals(r.getString(1))
                            || !"-".equals(r.getString(2)) || r.getLong(3) != 0 || r.getBoolean(4) || !r.getBoolean(
                            5) || !r.getBoolean(6)
                            || !r.getBoolean(7) || !r.getBoolean(8) || r.getInt(9) != keys.size() || r.getInt(
                            10) != 3 || r.getString(11) != null || r.getString(12) != null) {
                        throw new SQLException("Oscar replay index profile is unsupported");
                    }
                }
            }
        }
        if (indexes != constraints) {
            throw new SQLException("Oscar primary key index observation differs");
        }
    }

    /**
     * 返回键集合。
     */
    public static CursorKeyContract keys(Shape shape, List<String> keys) throws SQLException {
        if (!shape.schema().primaryKeys().equals(keys)) {
            throw new SQLException("Oscar replay requires the exact ordered primary key");
        }
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var facts = new ArrayList<String>();
        for (String name : keys) {
            var column = shape.columns().get(name);
            if (column == null) {
                throw new SQLException("Oscar key is missing");
            }
            column.requireOrdered();
            domains.add(column.domain());
            facts.add(column.nativeType());
        }
        return new CursorKeyContract(ProductTypeEnum.OSCAR, DigestUtil.sha256Hex("OSCAR_KEYS_V1\0" + facts), domains);
    }
}
