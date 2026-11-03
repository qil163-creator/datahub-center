package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import cn.iocoder.yudao.framework.etl.core.file.EtlDelimitedRows;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.compress.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/** HDFS 普通文件的有界读取和只创建新文件的提交，执行时重新验证真实路径与根目录授权。 */
final class ManagedFlinkHdfs {
    private ManagedFlinkHdfs() { }
    private static JSONObject options(JSONObject plan) { var options = plan.getJSONObject("options"); if (options == null) throw new IllegalArgumentException("HDFS 执行参数缺失"); return options; }
    private static Configuration configuration(JSONObject options) {
        var result = new Configuration(); result.setInt("dfs.client.socket-timeout", 30000); result.setInt("dfs.client.failover.max.attempts", 3);
        for (var entry : options.entrySet()) if (entry.getKey().startsWith("datahub.hadoop.")) {
            String key = entry.getKey().substring(15);
            if (!Set.of("dfs.client.socket-timeout", "dfs.client.failover.max.attempts", "dfs.replication").contains(key)) throw new IllegalArgumentException("HDFS 客户端参数未授权");
            int value = Integer.parseInt(entry.getValue().toString()); if (value < 1 || value > (key.equals("dfs.client.socket-timeout") ? 300000 : 100)) throw new IllegalArgumentException("HDFS 客户端预算无效");
            result.setInt(key, value);
        }
        return result;
    }
    private static URI root(JSONObject options) {
        URI root = URI.create(options.getStr("datahub.root")); String allowed = System.getenv("DATAHUB_ETL_HDFS_ROOTS");
        if (!"hdfs".equals(root.getScheme()) || root.getHost() == null || root.getUserInfo() != null || root.getRawQuery() != null || root.getRawFragment() != null
                || root.getPath() == null || root.getPath().equals("/") || allowed == null || Arrays.stream(allowed.split(";", -1)).noneMatch(root.toString()::equals))
            throw new IllegalArgumentException("工作节点未授权此 HDFS 根目录");
        return root;
    }
    private static void within(URI path, URI root) {
        if (!Objects.equals(path.getScheme(), root.getScheme()) || !Objects.equals(path.getAuthority(), root.getAuthority())) throw new IllegalArgumentException("HDFS 物理服务超出授权范围");
        java.nio.file.Path base = java.nio.file.Path.of(root.getPath()).normalize(), current = java.nio.file.Path.of(path.getPath()).normalize();
        if (!current.startsWith(base) || !current.toString().equals(path.getPath()) || current.equals(java.nio.file.Path.of("/"))) throw new IllegalArgumentException("HDFS 路径超出授权根目录");
    }
    private static void authorize(FileSystem fs, Path path, URI root, boolean output) throws Exception {
        within(path.toUri(), root); Path existing = path;
        while (!fs.exists(existing) && existing.getParent() != null) existing = existing.getParent();
        within(fs.resolvePath(existing).toUri(), root);
        if (!output && !fs.exists(path)) throw new IllegalArgumentException("HDFS 输入路径不存在");
    }
    static void read(JSONObject plan, Consumer<String> rows) throws Exception {
        var options = options(plan); var root = root(options); var conf = configuration(options); Path path = new Path(options.getStr("path"));
        try (FileSystem fs = FileSystem.newInstance(path.toUri(), conf)) {
            authorize(fs, path, root, false);
            var files = new ArrayList<FileStatus>(); var initial = fs.getFileStatus(path);
            if (initial.isFile()) files.add(initial);
            else for (var file : fs.listStatus(path)) {
                if (file.getPath().getName().startsWith(".") || file.getPath().getName().startsWith("_")) continue;
                if (!file.isFile() || file.isSymlink()) throw new IllegalArgumentException("HDFS 有界输入不隐式遍历子目录或符号链接");
                files.add(file);
            }
            if (files.size() > 1000) throw new IllegalArgumentException("HDFS 输入文件数超过预算");
            files.sort(Comparator.comparing(file -> file.getPath().toString())); long[] budget = {0,0,0};
            for (var file : files) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("HDFS 读取已取消");
                authorize(fs, file.getPath(), root, false);
                if ((budget[0] += file.getLen()) > plan.getLong("maxBytes", 67108864L)) throw new IllegalArgumentException("HDFS 输入总字节数超过预算");
                try (InputStream raw = fs.open(file.getPath())) {
                    var codec = new CompressionCodecFactory(conf).getCodec(file.getPath());
                    InputStream expanded = codec == null ? raw : codec.createInputStream(raw);
                    var bounded = new FilterInputStream(expanded) {
                        private void count(int value) throws IOException { if (value > 0 && (budget[1] += value) > plan.getLong("maxBytes",67108864L)) throw new IOException("HDFS 解压数据超过预算"); }
                        @Override public int read() throws IOException { int value=in.read(); count(value < 0 ? 0 : 1); return value; }
                        @Override public int read(byte[] bytes,int offset,int length) throws IOException { int value=in.read(bytes,offset,length); count(value); return value; }
                    };
                    var decoder = Charset.forName(options.getStr("encoding","UTF-8")).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                    var reader = new InputStreamReader(bounded,decoder); boolean[] first={true};
                    var columns=plan.getJSONArray("columns"); var resultTypes=plan.getJSONObject("resultTypes");
                    boolean csv=options.getStr("datahub.filetype","csv").equals("csv");
                    EtlDelimitedRows.read(reader,options.getStr("delimiter",",").charAt(0),csv?'"':0,'"',csv,1_000_001,1024,1_048_576,cells->{
                        if (cells.size()==1 && cells.get(0).value().isEmpty() && !cells.get(0).quoted()) return;
                        var values=new ArrayList<String>(); for(var cell:cells) values.add(cell.value());
                        if(first[0]) { first[0]=false; if(values.get(0).startsWith("\uFEFF")) values.set(0,values.get(0).substring(1));
                            if(Boolean.parseBoolean(options.getStr("header","false"))) {
                                var names=columns.stream().map(JSONUtil::parseObj).map(field->field.getStr("name")).toList();
                                if(!names.equals(values)) throw new IllegalArgumentException("HDFS 表头与完整字段声明不一致"); return;
                            }
                        }
                        if(++budget[2]>plan.getInt("maxRows",100000) || values.size()!=columns.size()) throw new IllegalArgumentException("HDFS 行数或字段数不符合声明");
                        var row=JSONUtil.createObj();
                        for(int index=0;index<columns.size();index++) {
                            var field=JSONUtil.parseObj(columns.get(index)); String name=field.getStr("name"), value=values.get(index);
                            if(!cells.get(index).quoted() && value.equals(options.getStr("nullValue","\\N"))) value=null;
                            value=ManagedFlinkFileRows.normalize(resultTypes.getJSONObject(name).getStr("sqlType"),value,options.getStr("dateFormat",""),options.getStr("timestampFormat",""));
                            row.set(name,value);
                        }
                        rows.accept(row.toString());
                    });
                }
            }
        }
    }
    static EtlBoundedConnectorTransport.Writer writer(JSONObject plan) throws Exception {
        var options=options(plan); var root=root(options); var conf=configuration(options); Path path=new Path(options.getStr("path"));
        var filePlan=new JSONObject(plan).set("fieldDelimiter",options.getStr("delimiter",",")).set("nullLiteral",options.getStr("nullValue","\\N"))
                .set("encoding",options.getStr("encoding","UTF-8")).set("fileType",options.getStr("datahub.filetype","csv")).set("includeHeader",false);
        var stage=new ManagedFlinkTextStage(filePlan,false); FileSystem fs=FileSystem.newInstance(path.toUri(),conf);
        try { authorize(fs,path,root,true); }
        catch(Exception failure) { try { stage.close(); } finally { fs.close(); } throw failure; }
        String prefix=options.getStr("datahub.filename","data"); if(!prefix.matches("[A-Za-z0-9_-]{1,100}")) { stage.close(); fs.close(); throw new IllegalArgumentException("HDFS 文件名前缀无效"); }
        var codec=codec(options.getStr("compression",""),conf); String suffix=options.getStr("datahub.filetype","csv").equals("csv")?".csv":".txt";
        String name=prefix+"-"+UUID.randomUUID()+suffix+(codec==null?"":codec.getDefaultExtension());
        Path temporary=new Path(path,".datahub-stage-"+UUID.randomUUID()),destination=new Path(path,name);
        return new EtlBoundedConnectorTransport.Writer() {
            private boolean published;
            @Override public void write(Map<String,String> row) throws Exception { stage.write(row); }
            @Override public void finish() throws Exception {
                if(published) return; stage.finish(); authorize(fs,path,root,true); fs.mkdirs(path);
                try(InputStream source=Files.newInputStream(stage.file); OutputStream raw=fs.create(temporary,false)) {
                    OutputStream target=codec==null?raw:codec.createOutputStream(raw);
                    try { byte[] bytes=new byte[65536]; for(int count;(count=source.read(bytes))>=0;) { if(Thread.currentThread().isInterrupted()) throw new IOException("HDFS 写入已取消"); if(count>0) target.write(bytes,0,count); } }
                    finally { if(target!=raw) target.close(); }
                }
                if(fs.getFileStatus(temporary).getLen()>plan.getLong("maxBytes",67108864L)) throw new IOException("HDFS 输出文件超过字节预算");
                FileContext.getFileContext(fs.getUri(),conf).rename(temporary,destination,Options.Rename.NONE); published=true;
            }
            @Override public void close() throws Exception { try { stage.close(); if(!published) fs.delete(temporary,false); } finally { fs.close(); } }
        };
    }
    private static CompressionCodec codec(String name,Configuration conf) throws Exception {
        String type=switch(name) { case "","none"->null; case "gzip"->GzipCodec.class.getName(); case "bzip2"->BZip2Codec.class.getName(); case "snappy"->SnappyCodec.class.getName(); case "lzo"->"com.hadoop.compression/lzo/LzopCodec".replace('/','.'); default->throw new IllegalArgumentException("HDFS 压缩格式无效"); };
        if(type==null) return null; var codec=(CompressionCodec)Class.forName(type).getDeclaredConstructor().newInstance();
        if(codec instanceof org.apache.hadoop.conf.Configurable configurable) configurable.setConf(conf); return codec;
    }
}
