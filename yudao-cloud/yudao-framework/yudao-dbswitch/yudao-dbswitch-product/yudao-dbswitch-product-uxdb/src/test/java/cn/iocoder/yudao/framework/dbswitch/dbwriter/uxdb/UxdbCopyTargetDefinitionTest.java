package cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Pure CREATE admission tests: no JDBC, vendor credentials or database fixture. */
class UxdbCopyTargetDefinitionTest {
    @Test void scalarModifiersAgreeWithVerifiedNativeDomains() {
        for(String type:List.of("smallint","int2","integer","int","int4","bigint","int8",
                "numeric(1)","decimal(1,1)","numeric(1000,0)","numeric(1000,1000)","real","float4",
                "double precision","float8","boolean","bool","text","bytea","date",
                "varchar(1)","character varying(10485760)","char","character","char(1)","character(10485760)",
                "timestamp","timestamp(0)","timestamp(6)","time","time(0)","time(6)")) {
            assertDoesNotThrow(()->validate(List.of("CREATE TABLE s.t(v "+type+")"),List.of("v")),type);
        }
        for(String type:List.of("varchar","character varying","numeric(4,5)","decimal(4,5)",
                "numeric","decimal","numeric(0)","numeric(1001)","numeric(4,-1)","varchar(0)","char(0)",
                "varchar(10485761)","timestamp(7)","time(7)","integer(4)","bytea(4)","boolean(1)",
                "numeric(18446744073709551617,0)","numeric(1,18446744073709551616)","varchar(18446744073709551617)")) {
            assertThrows(IllegalArgumentException.class,()->validate(List.of("CREATE TABLE s.t(v "+type+")"),List.of("v")),type);
        }
    }

    @ParameterizedTest @ValueSource(strings={"CHECK","UNIQUE"})
    void columnConstraintsAreBoundedAt256(String kind) {
        for(int count:List.of(256,257)) {
            var fields=fields(count);
            String columns=String.join(",",fields.stream().map(f->f+" smallint "
                    +(kind.equals("CHECK")?"CHECK("+f+" BETWEEN -128 AND 127)":"UNIQUE")).toList());
            var statements=List.of("CREATE TABLE s.t("+columns+")");
            if(count==256)assertDoesNotThrow(()->validate(statements,fields));
            else assertThrows(IllegalArgumentException.class,()->validate(statements,fields));
        }
    }

    @Test void columnAndTableConstraintsShareOneNativeBudget() {
        for(int count:List.of(255,256)) {
            var fields=fields(count);
            String columns=String.join(",",fields.stream().map(f->f+" smallint CHECK("+f+" BETWEEN -128 AND 127)").toList());
            var statements=List.of("CREATE TABLE s.t("+columns+",PRIMARY KEY(c0))");
            if(count==255)assertDoesNotThrow(()->validate(statements,fields));
            else assertThrows(IllegalArgumentException.class,()->validate(statements,fields));
        }
    }

    @Test void multipleConstraintsOnOneColumnEachConsumeBudget() {
        for(int count:List.of(128,129)) {
            var fields=fields(count);
            String columns=String.join(",",fields.stream().map(f->f+" smallint UNIQUE CHECK("+f+" BETWEEN -128 AND 127)").toList());
            var statements=List.of("CREATE TABLE s.t("+columns+")");
            if(count==128)assertDoesNotThrow(()->validate(statements,fields));
            else assertThrows(IllegalArgumentException.class,()->validate(statements,fields));
        }
    }

    @ParameterizedTest @ValueSource(strings={"PRIMARY KEY","UNIQUE","INDEX"})
    void keyAndIndexWidthIsBoundedAt32(String kind) {
        for(int count:List.of(32,33)) {
            var fields=fields(count);String columns=String.join(",",fields.stream().map(f->f+" smallint").toList());
            var statements=new ArrayList<String>();
            statements.add("CREATE TABLE s.t("+columns+(kind.equals("INDEX")?"":","+kind+"("+String.join(",",fields)+")")+")");
            if(kind.equals("INDEX"))statements.add("CREATE INDEX ix ON s.t("+String.join(",",fields)+")");
            if(count==32)assertDoesNotThrow(()->validate(statements,fields));
            else assertThrows(IllegalArgumentException.class,()->validate(statements,fields));
        }
    }

    private static List<String> fields(int count) {return IntStream.range(0,count).mapToObj(i->"c"+i).toList();}
    private static void validate(List<String> statements,List<String> fields) {
        UxdbCopyTargetDefinition.validate("s","t",statements,fields);
    }
}
