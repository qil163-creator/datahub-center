package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.util.*;

/** 无源码、无凭据材料的原生分区批次程序；Java17 launcher 与平台共用同一有界编码。 */
public record EtlNativeScriptProgram(String engine, boolean streaming, int parallelism, List<String> initialization,
                                     List<Initializer> initializers, List<Stage> stages, List<String> inserts) {
    private static final int MAX_BYTES = 96 * 1024;
    public EtlNativeScriptProgram(String engine, boolean streaming, int parallelism, List<String> initialization, List<Stage> stages, List<String> inserts) {
        this(engine, streaming, parallelism, initialization, List.of(), stages, inserts);
    }
    public record Initializer(String id, String tokenKey, EtlScriptBrokerClient.Binding binding) implements Serializable {
        public Initializer {
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,100}") || tokenKey == null || !tokenKey.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) throw invalid();
            Objects.requireNonNull(binding);
        }
    }
    public record Field(String name, String sqlType, Boolean nullable) implements Serializable {
        public Field {
            if (name == null || name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl) || sqlType == null
                    || !(Set.of("STRING", "BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "FLOAT", "DOUBLE", "DATE").contains(sqlType)
                    || sqlType.matches("TIMESTAMP\\([0-9]\\)") || sqlType.matches("DECIMAL\\(([1-9]|[12][0-9]|3[0-8]),([0-9]|[12][0-9]|3[0-8])\\)"))) throw invalid();
            if (sqlType.startsWith("DECIMAL(")) { String[] shape = sqlType.substring(8, sqlType.length() - 1).split(","); if (Integer.parseInt(shape[1]) > Integer.parseInt(shape[0])) throw invalid(); }
        }
    }
    public record Stage(String id, String inputSql, String view, List<Field> inputFields, List<Field> outputFields,
                        EtlScriptBrokerClient.Binding binding, boolean partitionBatch, int maximumRows, long flushMillis) implements Serializable {
        public Stage {
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,100}") || view == null || !view.matches("dh_native_script_[a-f0-9]{24}") || inputSql == null || inputSql.isBlank()
                    || maximumRows < 1 || maximumRows > 10_000 || partitionBatch && flushMillis != 0 || !partitionBatch && (flushMillis < 10 || flushMillis > 300_000)) throw invalid();
            inputFields = fields(inputFields); outputFields = fields(outputFields); Objects.requireNonNull(binding);
        }
    }
    public EtlNativeScriptProgram {
        if (!Set.of("SPARK", "FLINK").contains(engine) || streaming && !engine.equals("FLINK") || parallelism < 1 || parallelism > 4) throw invalid();
        initialization = List.copyOf(initialization); initializers = List.copyOf(initializers); stages = List.copyOf(stages); inserts = List.copyOf(inserts);
        if (initialization.size() > 256 || (stages.isEmpty() && initializers.isEmpty()) || stages.size() > 16 || initializers.size() > 16 || initializers.stream().map(Initializer::tokenKey).distinct().count() != initializers.size() || inserts.isEmpty() || inserts.size() > 64
                || stages.stream().map(Stage::id).distinct().count() != stages.size() || stages.stream().map(Stage::view).distinct().count() != stages.size()) throw invalid();
        for (var initializer : initializers) if (!initializer.binding.engine().equals(engine)) throw invalid();
        for (var stage : stages) if (!stage.binding.engine().equals(engine) || streaming == stage.partitionBatch || engine.equals("SPARK") && !stage.partitionBatch) throw invalid();
    }
    private static List<Field> fields(List<Field> fields) {
        fields = List.copyOf(fields);
        if (fields.isEmpty() || fields.size() > 256 || fields.stream().map(Field::name).distinct().count() != fields.size()) throw invalid();
        return fields;
    }
    public List<String> argumentChunks() {
        try {
            byte[] bytes = EtlScriptProtocol.encode(MAX_BYTES, output -> {
                output.writeInt(2); text(output, engine); output.writeBoolean(streaming); output.writeInt(parallelism); texts(output, initialization);
                output.writeInt(initializers.size());
                for (var initializer : initializers) { text(output, initializer.id); text(output, initializer.tokenKey); binding(output, initializer.binding); }
                output.writeInt(stages.size());
                for (var stage : stages) {
                    text(output, stage.id); text(output, stage.inputSql); text(output, stage.view); fieldList(output, stage.inputFields); fieldList(output, stage.outputFields);
                    binding(output, stage.binding);
                    output.writeBoolean(stage.partitionBatch); output.writeInt(stage.maximumRows); output.writeLong(stage.flushMillis);
                }
                texts(output, inserts);
            });
            String encoded = Base64.getUrlEncoder().encodeToString(bytes); var chunks = new ArrayList<String>();
            for (int start = 0; start < encoded.length(); start += 2048) chunks.add(encoded.substring(start, Math.min(start + 2048, encoded.length())));
            return List.copyOf(chunks);
        } catch (IOException failure) { throw invalid(); }
    }
    public static EtlNativeScriptProgram fromArguments(String[] arguments) {
        if (arguments == null || arguments.length < 1 || arguments.length > 64 || Arrays.stream(arguments).anyMatch(value -> value == null || value.length() > 2048 || !value.matches("[A-Za-z0-9_=-]+"))) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(String.join("", arguments)); if (bytes.length > MAX_BYTES) throw invalid();
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != 2) throw invalid();
                String engine = text(input); boolean streaming = input.readBoolean(); int parallelism = input.readInt(); var initialization = texts(input, 256);
                int initializersCount = count(input, 16); var initializers = new ArrayList<Initializer>();
                for (int i = 0; i < initializersCount; i++) initializers.add(new Initializer(text(input), text(input), binding(input)));
                int count = count(input, 16); var stages = new ArrayList<Stage>();
                for (int i = 0; i < count; i++) {
                    String id = text(input), sql = text(input), view = text(input); var in = fieldList(input); var out = fieldList(input);
                    var binding = binding(input);
                    stages.add(new Stage(id, sql, view, in, out, binding, input.readBoolean(), input.readInt(), input.readLong()));
                }
                var result = new EtlNativeScriptProgram(engine, streaming, parallelism, initialization, initializers, stages, texts(input, 64)); EtlScriptProtocol.requireEnd(input); return result;
            }
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    private static void binding(DataOutputStream output, EtlScriptBrokerClient.Binding binding) throws IOException {
        for (String value : List.of(binding.endpoint(), binding.epoch(), binding.bindingId(), binding.sourceHash(), binding.task(), binding.workerGroup(), binding.engine(), binding.privateKeyRef(), binding.certificateRef(), binding.authorityRef())) text(output, value);
    }
    private static EtlScriptBrokerClient.Binding binding(DataInputStream input) throws IOException {
        return new EtlScriptBrokerClient.Binding(text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input), text(input));
    }
    private static void fieldList(DataOutputStream output, List<Field> fields) throws IOException {
        output.writeInt(fields.size()); for (var field : fields) { text(output, field.name); text(output, field.sqlType); output.writeByte(field.nullable == null ? 0 : field.nullable ? 1 : 2); }
    }
    private static List<Field> fieldList(DataInputStream input) throws IOException {
        int count = count(input, 256); var result = new ArrayList<Field>();
        for (int i = 0; i < count; i++) { String name = text(input), type = text(input); int nullable = input.readUnsignedByte(); if (nullable > 2) throw invalid(); result.add(new Field(name, type, nullable == 0 ? null : nullable == 1)); }
        return result;
    }
    private static void texts(DataOutputStream output, List<String> values) throws IOException { output.writeInt(values.size()); for (String value : values) text(output, value); }
    private static List<String> texts(DataInputStream input, int maximum) throws IOException { int count = count(input, maximum); var result = new ArrayList<String>(); for (int i = 0; i < count; i++) result.add(text(input)); return result; }
    private static int count(DataInputStream input, int maximum) throws IOException { int count = input.readInt(); if (count < 0 || count > maximum) throw invalid(); return count; }
    private static void text(DataOutputStream output, String value) throws IOException { EtlScriptProtocol.writeText(output, value, MAX_BYTES); }
    private static String text(DataInputStream input) throws IOException { return EtlScriptProtocol.readText(input, MAX_BYTES); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("受管原生脚本程序、字段、批次策略或编码无效/超限"); }
}
