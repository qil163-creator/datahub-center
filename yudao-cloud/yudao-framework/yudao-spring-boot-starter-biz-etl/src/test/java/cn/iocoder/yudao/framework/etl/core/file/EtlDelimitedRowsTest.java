package cn.iocoder.yudao.framework.etl.core.file;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlDelimitedRowsTest {
    @Test void quotedNullMarkerEmptyTextEscapedQuotesAndMultilineRemainDistinct() throws Exception {
        var rows=new ArrayList<List<EtlDelimitedRows.Cell>>();
        EtlDelimitedRows.read(new StringReader("id,label,optional\r\n1,\"line\n\"\"quoted\"\"\",\\N\r\n2,\"\",\"\\N\"\n"),',','"','"',true,10,3,100,rows::add);
        assertEquals(3,rows.size());assertEquals(new EtlDelimitedRows.Cell("line\n\"quoted\"",true),rows.get(1).get(1));
        assertEquals(new EtlDelimitedRows.Cell("\\N",false),rows.get(1).get(2));assertEquals(new EtlDelimitedRows.Cell("",true),rows.get(2).get(1));
        assertEquals(new EtlDelimitedRows.Cell("\\N",true),rows.get(2).get(2));
    }
    @Test void malformedQuotesAndBudgetsFailRatherThanYieldingPartialSuccess() {
        for(String input:List.of("a,\"unclosed","a,\"closed\" trailing","a,b\n1,2\n")) {
            assertThrows(Exception.class,()->EtlDelimitedRows.read(new StringReader(input),',','"','"',true,1,3,20,row->{}));
        }
        assertThrows(Exception.class,()->EtlDelimitedRows.read(new StringReader("abc"),',','"','"',true,10,3,2,row->{}));
        assertThrows(Exception.class,()->EtlDelimitedRows.read(new StringReader("a,b"),',','"','"',true,10,1,20,row->{}));
        assertThrows(Exception.class,()->EtlDelimitedRows.read(new StringReader("\"a\nb\""),',','"','"',false,10,3,20,row->{}));
    }
    @Test void excelExecutionDefaultMatchesFiftyMiBUploadEnvelope() { assertEquals(50L*1024*1024,EtlBoundedExcelReader.Limits.defaults().maxBytes()); }
}
