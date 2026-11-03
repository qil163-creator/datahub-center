package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MongoNativeStructureSafetyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> mutations = new ArrayList<>();
    private DataSource source;
    private MongoNativeSession session;
    private MockedStatic<MongoNativeSession> nativeOpen;
    private boolean failIndex, loseRename, changed;
    private int targetObservations;

    @BeforeEach void setup() throws Exception {
        source = mock(DataSource.class); var connection = mock(Connection.class); session = mock(MongoNativeSession.class);
        when(source.getConnection()).thenReturn(connection);
        nativeOpen = mockStatic(MongoNativeSession.class);
        nativeOpen.when(() -> MongoNativeSession.open(any(), anyLong(), anyInt())).thenReturn(session);
        when(session.encode(anyString())).thenAnswer(call -> call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8));
        when(session.canonicalJson(any())).thenAnswer(call -> new String(call.<byte[]>getArgument(0), StandardCharsets.UTF_8));
        when(session.command(anyString(), any())).thenAnswer(call -> {
            var command = json.readTree(call.<byte[]>getArgument(1));
            if (command.has("buildInfo")) return bytes("{\"ok\":1,\"version\":\"7.0.43\",\"modules\":[]}");
            if (command.has("hello")) return bytes("{\"ok\":1,\"isWritablePrimary\":true}");
            if (command.has("listCollections")) {
                String table = command.path("filter").path("name").asText();
                if (table.startsWith("dbswitch_stage_") && mutations.isEmpty())
                    return bytes("{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":[]}}");
                int identity = table.equals("target") ? (changed && ++targetObservations >= 2 ? 9 : 2) : 3;
                return bytes("{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":[{\"name\":\"" + table
                        + "\",\"type\":\"collection\",\"options\":{},\"info\":{\"uuid\":" + uuid(identity) + "}}]}}");
            }
            if (command.has("listIndexes")) return bytes("{\"ok\":1,\"cursor\":{\"id\":0,\"firstBatch\":" + indexes() + "}}");
            mutations.add(command);
            if (command.has("createIndexes") && failIndex || command.has("renameCollection") && loseRename)
                throw new SQLException("untrusted server text must not escape");
            return bytes("{\"ok\":1}");
        });
    }
    @AfterEach void close() { nativeOpen.close(); }

    @Test void unknownNativeAttributesAndRenamingTheFrameFailBeforeTargetIo() throws Exception {
        var envelope = json.readTree(definition().document());
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("options")).put("capped", true);
        var unsupported = new NativeTableDefinition(ProductTypeEnum.MONGODB, "mongodb-collection-v1", envelope.toString(), MongoMigrationFrame.FIELDS);
        assertThrows(IllegalArgumentException.class, () -> prepare(unsupported));
        assertThrows(IllegalArgumentException.class, () -> MongoNativeStructure.prepare(source, definition(), "db", "target",
                Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, "other"), true, 65536, 2));
        verifyNoInteractions(source); assertTrue(mutations.isEmpty());
    }

    @Test void failedStagingIndexCannotDropOrRenameTheExistingTargetAndCannotReplay() {
        failIndex = true;
        var plan = prepare(definition());
        assertTrue(plan.requiresExclusiveDdlOwnership());
        assertEquals(2, plan.recoveryResources().size());
        assertNotNull(plan.recoveryResources().get(0).expectedPhysicalIdentity());
        assertNull(plan.recoveryResources().get(1).expectedPhysicalIdentity());
        var failure = assertThrows(UnconfirmedWriteException.class, plan::execute);
        assertFalse(failure.toString().contains("untrusted"));
        assertEquals(List.of("create", "createIndexes"), mutations.stream().map(node -> node.fieldNames().next()).toList());
        assertThrows(IllegalStateException.class, plan::execute);
        assertEquals(2, mutations.size());
    }

    @Test void renameResponseLossIsUnknownAndTheSingleUsePlanNeverReplaysIt() {
        loseRename = true;
        var plan = prepare(definition());
        assertThrows(UnconfirmedWriteException.class, plan::execute);
        var resources = plan.recoveryResources();
        assertNotNull(resources.get(1).expectedPhysicalIdentity());
        assertEquals("target", resources.get(0).name());
        assertTrue(resources.get(1).name().startsWith("dbswitch_stage_"));
        assertThrows(UnsupportedOperationException.class, () -> resources.clear());
        assertEquals(1, mutations.stream().filter(node -> node.has("renameCollection")).count());
        assertTrue(mutations.stream().filter(node -> node.has("renameCollection")).findFirst().orElseThrow().path("dropTarget").booleanValue());
        assertTrue(mutations.stream().noneMatch(node -> node.has("drop")));
        assertThrows(IllegalStateException.class, plan::execute);
        assertEquals(1, mutations.stream().filter(node -> node.has("renameCollection")).count());
    }

    @Test void changedPhysicalIdentityBeforeExecutionCannotCreateStagingOrMutateTarget() {
        changed = true;
        var plan = prepare(definition());
        assertThrows(IllegalStateException.class, plan::execute);
        assertTrue(mutations.isEmpty());
    }

    private cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepare(NativeTableDefinition definition) {
        return MongoNativeStructure.prepare(source, definition, "db", "target",
                Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, MongoMigrationFrame.DOCUMENT), true, 65536, 2);
    }
    @Test void evolutionIndexFailureIsUnknownWithoutDroppingRenamingOrReplaying()throws Exception {
        var envelope=json.readTree(definition().document());
        ((com.fasterxml.jackson.databind.node.ArrayNode)envelope.path("indexes")).addObject().put("v",2).put("name","added_idx").putObject("key").put("added",1);
        var desired=new NativeTableDefinition(ProductTypeEnum.MONGODB,"mongodb-collection-v1",envelope.toString(),MongoMigrationFrame.FIELDS);
        var plan=MongoNativeStructure.evolve(source,desired,"db","target",65536,2);failIndex=true;
        assertTrue(plan.requiresExclusiveDdlOwnership());assertFalse(plan.replacesExisting());
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertThrows(IllegalStateException.class,plan::execute);
        assertEquals(List.of("createIndexes"),mutations.stream().map(node->node.fieldNames().next()).toList());
        assertEquals(1,plan.recoveryResources().size());assertNotNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());
    }
    @Test void additiveEvolutionCannotHideRemovedIndexesOrImmutableCollectionChanges()throws Exception {
        var envelope=json.readTree(definition().document());((com.fasterxml.jackson.databind.node.ArrayNode)envelope.path("indexes")).remove(1);
        var removed=new NativeTableDefinition(ProductTypeEnum.MONGODB,"mongodb-collection-v1",envelope.toString(),MongoMigrationFrame.FIELDS);
        assertThrows(IllegalArgumentException.class,()->MongoNativeStructure.evolve(source,removed,"db","target",65536,2));
        envelope=json.readTree(definition().document());((com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("options")).putObject("collation").put("locale","en");
        var collation=new NativeTableDefinition(ProductTypeEnum.MONGODB,"mongodb-collection-v1",envelope.toString(),MongoMigrationFrame.FIELDS);
        assertThrows(IllegalArgumentException.class,()->MongoNativeStructure.evolve(source,collation,"db","target",65536,2));assertTrue(mutations.isEmpty());
    }
    private NativeTableDefinition definition() {
        return new NativeTableDefinition(ProductTypeEnum.MONGODB, "mongodb-collection-v1",
                "{\"major\":7,\"sourceIdentity\":" + uuid(1) + ",\"options\":{},\"indexes\":" + indexes() + "}", MongoMigrationFrame.FIELDS);
    }
    private static String indexes() { return "[{\"v\":2,\"key\":{\"_id\":1},\"name\":\"_id_\"},{\"v\":2,\"key\":{\"value\":1},\"name\":\"value_idx\"}]"; }
    private static String uuid(int value) { byte[] bytes = new byte[16]; bytes[15] = (byte) value; return "{\"$binary\":{\"base64\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"subType\":\"04\"}}"; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
