package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅在显式指定的一次性本机 PostgreSQL 验证单位字典增量与基线。 */
class IndicatorUnitDictionaryPostgreSqlTest {
    @Test
    void dictionaryMigrationReplaysAndPreservesAdministratorValues() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+$"),
                "需要显式提供本次一次性本机 PostgreSQL");
        try (Connection connection = DriverManager.getConnection(url,
                System.getProperty("datahub.test.postgres.user", "modeling_test"), "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\"; "
                        + "CREATE SEQUENCE system_dict_type_seq; CREATE SEQUENCE system_dict_data_seq; "
                        + "CREATE TABLE system_dict_type(id bigint PRIMARY KEY,name text,type text,status integer,remark text,creator text,updater text,deleted smallint DEFAULT 0); "
                        + "CREATE TABLE system_dict_data(id bigint PRIMARY KEY,sort integer,label text,value text,dict_type text,status integer,remark text,creator text,updater text,deleted smallint DEFAULT 0); "
                        + "INSERT INTO system_dict_type VALUES(500,'既有字典','existing',0,NULL,'fixture','fixture',0); "
                        + "INSERT INTO system_dict_data VALUES(500,1,'既有值','1','existing',0,NULL,'fixture','fixture',0)");
                String migration = Files.readString(ModelingMigrationTestSupport.repoFile(
                        "sql/postgresql/migrations/0214_modeling_indicator-unit-dictionary.sql"));
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT count(*), count(DISTINCT remark), bool_and(label=value AND status=0) "
                        + "FROM system_dict_data WHERE dict_type='modeling_indicator_unit'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(36);
                    assertThat(rows.getInt(2)).isEqualTo(7); assertThat(rows.getBoolean(3)).isTrue();
                }
                var groups = new ArrayList<String>();
                try (var rows = statement.executeQuery("SELECT remark FROM system_dict_data WHERE dict_type='modeling_indicator_unit' GROUP BY remark ORDER BY min(sort)")) {
                    while (rows.next()) groups.add(rows.getString(1));
                }
                assertThat(groups).containsExactly("其他", "货币单位", "时间单位", "比例单位", "名次", "对象量词", "重量单位");
                statement.execute("UPDATE system_dict_data SET label='管理员小时',remark='保留分组',status=1 WHERE dict_type='modeling_indicator_unit' AND value='小时'; "
                        + "INSERT INTO system_dict_data VALUES(900,99,'旧自定义单位','旧自定义单位','modeling_indicator_unit',0,'自定义','fixture','fixture',0)");
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT label,remark,status FROM system_dict_data WHERE dict_type='modeling_indicator_unit' AND value='小时'")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("管理员小时");
                    assertThat(rows.getString(2)).isEqualTo("保留分组"); assertThat(rows.getInt(3)).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM system_dict_data WHERE dict_type='modeling_indicator_unit'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(37);
                }
                try (var rows = statement.executeQuery("SELECT value FROM system_dict_data WHERE id=500")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("1");
                }
                assertThat(ModelingMigrationTestSupport.baselineBlock("-- Migration: 0214 ").strip())
                        .isEqualTo(migration.replace("\r\n", "\n").strip());
            } finally {
                try (var cleanup = connection.createStatement()) {
                    cleanup.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
                }
            }
        }
    }
}
