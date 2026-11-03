package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchStructureContract.*;

class ElasticsearchStructureContractTest {
    private ObjectNode body(String mapping) throws Exception {
        ObjectNode result=JSON.createObjectNode();result.set("settings",JSON.createObjectNode());result.set("aliases",JSON.createObjectNode());result.set("mappings",JSON.readTree(mapping));return result;
    }
    @Test void onlyNewFieldsAreAdditiveNotChangedExistingOptions() throws Exception {
        var before=body("{\"properties\":{\"old\":{\"type\":\"text\"},\"n\":{\"type\":\"nested\",\"properties\":{\"a\":{\"type\":\"long\"}}}}}");
        var after=body("{\"properties\":{\"old\":{\"type\":\"text\",\"fields\":{\"raw\":{\"type\":\"keyword\"}}},\"n\":{\"type\":\"nested\",\"properties\":{\"a\":{\"type\":\"long\"},\"b\":{\"type\":\"keyword\"}}}}}");
        assertDoesNotThrow(()->additive(before,after));
        ((ObjectNode)after.path("mappings").path("properties").path("old")).put("index",false);
        assertThrows(IllegalArgumentException.class,()->additive(before,after));
        assertThrows(IllegalArgumentException.class,()->additive(after,before));
    }
    @Test void nestedAndRuntimeCannotAuthorizeDataFramesOrGuessFieldReferences() throws Exception {
        for(String mapping:new String[]{"{\"properties\":{\"n\":{\"type\":\"nested\",\"properties\":{}}}}",
                "{\"properties\":{},\"runtime\":{\"x\":{\"type\":\"long\",\"script\":{\"source\":\"emit(1)\"}}}}",
                "{\"properties\":{\"value\":{\"type\":\"keyword\",\"copy_to\":\"other\"},\"other\":{\"type\":\"keyword\"}}}"}) {
            var body=body(mapping);assertDoesNotThrow(()->validate(body));assertThrows(IllegalArgumentException.class,()->dataFrame(body));
        }
    }
    @Test void nativeMetadataDoesNotSilentlyDropInlineAnalysisOrAliases() throws Exception {
        var body=body("{\"properties\":{\"lookup\":{\"type\":\"keyword\"}},\"_meta\":{\"lookup\":\"annotation\"}}");
        ((ObjectNode)body.path("settings")).put("index.analysis.analyzer.local.type","standard");
        ((ObjectNode)body.path("aliases")).set("ordinary",JSON.readTree("{\"routing\":\"exact\"}"));
        assertDoesNotThrow(()->validate(body));
        assertEquals(body,map(body.deepCopy(),fields(body),Map.of("_id","_id","lookup","lookup")));
        assertThrows(IllegalArgumentException.class,()->map(body.deepCopy(),fields(body),Map.of("_id","_id","lookup","renamed")));
    }
    @Test void externalResourcesAreExplicitlyRefused() throws Exception {
        for(String setting:new String[]{"index.lifecycle.name","index.default_pipeline","index.analysis.filter.custom.synonyms_path","index.analysis.filter.custom.synonyms_set"}) {
            var body=body("{\"properties\":{}}");((ObjectNode)body.path("settings")).put(setting,"external");assertThrows(IllegalArgumentException.class,()->validate(body));
        }
        var stored=body("{\"runtime\":{\"x\":{\"type\":\"long\",\"script\":{\"id\":\"external\"}}}}");assertThrows(IllegalArgumentException.class,()->validate(stored));
    }
    @Test void fingerprintsIgnoreObjectOrderButPreserveArrayOrder()throws Exception {
        assertEquals(fingerprint(JSON.readTree("{\"a\":1,\"b\":[1,2]}")),fingerprint(JSON.readTree("{\"b\":[1,2],\"a\":1}")));
        assertNotEquals(fingerprint(JSON.readTree("[1,2]")),fingerprint(JSON.readTree("[2,1]")));
    }
}
