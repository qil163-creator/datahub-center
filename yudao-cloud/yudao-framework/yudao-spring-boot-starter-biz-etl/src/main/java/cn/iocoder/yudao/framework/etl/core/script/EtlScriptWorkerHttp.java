package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.net.*;
import java.util.*;

/** 无证书、无秘密目录的 worker HTTP 桥；网络访问由宿主的精确资源授权执行。 */
public final class EtlScriptWorkerHttp {
    static final int REQUEST=10,REPLY=11,MAX_BYTES=8*1024*1024;
    private EtlScriptWorkerHttp() { }
    public record Request(String endpoint,String method,Map<String,String> query,Map<String,String> headers,byte[] body,int maximumBytes,int timeoutMillis) {
        public Request {
            var uri=URI.create(endpoint);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawQuery()!=null||uri.getUserInfo()!=null||uri.getFragment()!=null||!Set.of("GET","POST").contains(method)||body==null||body.length>1048576||maximumBytes<1||maximumBytes>MAX_BYTES||timeoutMillis<100||timeoutMillis>30000)throw invalid();
            query=Map.copyOf(query);headers=Map.copyOf(headers);body=body.clone();if(query.size()>64||headers.size()>64)throw invalid();
            var names=new HashSet<String>();
            for(var entry:headers.entrySet())if(!entry.getKey().matches("[A-Za-z][A-Za-z0-9-]{0,99}")||!names.add(entry.getKey().toLowerCase(Locale.ROOT))||entry.getValue().length()>4096||entry.getValue().chars().anyMatch(value->value<32||value==127))throw invalid();
        }
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "WorkerHttpRequest[method="+method+",bodyBytes="+body.length+"]";}
    }
    public record Response(int status,String contentType,byte[]body){public Response{if(status<100||status>599||contentType==null||contentType.length()>500||body==null||body.length>MAX_BYTES)throw invalid();body=body.clone();}@Override public byte[] body(){return body.clone();}@Override public String toString(){return "WorkerHttpResponse[status="+status+",bytes="+body.length+"]";}}
    @FunctionalInterface public interface Executor {Response exchange(Request request);}
    static void install(InputStream input,OutputStream output){
        if(!EtlScriptWorker.isWorkerProcess())throw invalid();
        URL.setURLStreamHandlerFactory(protocol->Set.of("http","https").contains(protocol)?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url){return new Connection(url,input,output);}
            @Override protected URLConnection openConnection(URL url,Proxy proxy){if(proxy!=Proxy.NO_PROXY)throw invalid();return openConnection(url);}
        }:null);
    }
    private static final class Connection extends HttpURLConnection {
        private final InputStream input;private final OutputStream output;private Response response;private final ByteArrayOutputStream body=new ByteArrayOutputStream(){@Override public synchronized void write(int value){if(count>=1048576)throw invalid();super.write(value);}@Override public synchronized void write(byte[]values,int offset,int length){if(length>1048576-count)throw invalid();super.write(values,offset,length);}};
        Connection(URL url,InputStream input,OutputStream output){super(url);this.input=input;this.output=output;}
        @Override public void connect()throws IOException{
            if(response!=null)return;
            try{
                URI uri=url.toURI();var query=new LinkedHashMap<String,String>();if(uri.getRawQuery()!=null)for(String item:uri.getRawQuery().split("&")){var pair=item.split("=",2);String key=URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8),value=pair.length==1?"":URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8);if(query.putIfAbsent(key,value)!=null)throw invalid();}
                String endpoint=new URI(uri.getScheme(),uri.getAuthority(),uri.getPath(),null,null).toASCIIString();var headers=new LinkedHashMap<String,String>();getRequestProperties().forEach((key,values)->{
                    // Hutool 自动生成长度头；帧已携带完整有界实体，宿主 HTTP 客户端必须自行生成传输长度。
                    if ("Content-Length".equalsIgnoreCase(key)) { if (values.size()!=1 || !String.valueOf(body.size()).equals(values.getFirst())) throw invalid(); }
                    else headers.put(key,String.join(", ",values));
                });
                int timeout=getReadTimeout()==0?30000:Math.min(30000,getReadTimeout());var request=new Request(endpoint,getRequestMethod(),query,headers,body.toByteArray(),MAX_BYTES,timeout);
                synchronized(output){EtlScriptProtocol.writeFrame(output,EtlScriptProtocol.encode(MAX_BYTES,out->{out.writeByte(REQUEST);write(out,request);}));try(var result=EtlScriptProtocol.readFrame(input,MAX_BYTES)){if(result.readUnsignedByte()!=REPLY||!result.readBoolean())throw invalid();response=readResponse(result);EtlScriptProtocol.requireEnd(result);}}
                connected=true;
            }catch(Exception failure){throw new IOException("受管脚本HTTP请求未获授权或执行失败");}
        }
        @Override public OutputStream getOutputStream(){if(connected)throw invalid();return body;}
        @Override public InputStream getInputStream()throws IOException{connect();if(response.status>=400)throw new IOException("受管脚本HTTP响应失败");return new ByteArrayInputStream(response.body);}
        @Override public InputStream getErrorStream(){try{connect();return response.status>=400?new ByteArrayInputStream(response.body):null;}catch(IOException failure){return null;}}
        @Override public int getResponseCode()throws IOException{connect();return response.status;}
        @Override public String getResponseMessage()throws IOException{connect();return response.status>=200&&response.status<300?"OK":"ERROR";}
        @Override public Map<String,List<String>> getHeaderFields(){try{connect();return Map.of("Content-Type",List.of(response.contentType),"Content-Length",List.of(String.valueOf(response.body.length)));}catch(IOException failure){throw invalid();}}
        @Override public String getHeaderField(String name){try{connect();return "Content-Type".equalsIgnoreCase(name)?response.contentType:"Content-Length".equalsIgnoreCase(name)?String.valueOf(response.body.length):null;}catch(IOException failure){throw invalid();}}
        @Override public boolean usingProxy(){return false;}
        @Override public void disconnect(){connected=false;}
    }
    static void write(DataOutputStream out,Request request)throws IOException{for(String value:List.of(request.endpoint,request.method))EtlScriptProtocol.writeText(out,value,8192);map(out,request.query);map(out,request.headers);bytes(out,request.body);out.writeInt(request.maximumBytes);out.writeInt(request.timeoutMillis);}
    static Request read(DataInputStream in)throws IOException{return new Request(EtlScriptProtocol.readText(in,8192),EtlScriptProtocol.readText(in,16),map(in),map(in),bytes(in,1048576),in.readInt(),in.readInt());}
    static void write(DataOutputStream out,Response response)throws IOException{out.writeInt(response.status);EtlScriptProtocol.writeText(out,response.contentType,500);bytes(out,response.body);}
    static Response readResponse(DataInputStream in)throws IOException{return new Response(in.readInt(),EtlScriptProtocol.readText(in,500),bytes(in,MAX_BYTES));}
    private static void map(DataOutputStream out,Map<String,String> map)throws IOException{out.writeInt(map.size());for(var entry:map.entrySet()){EtlScriptProtocol.writeText(out,entry.getKey(),4096);EtlScriptProtocol.writeText(out,entry.getValue(),8192);}}
    private static Map<String,String> map(DataInputStream in)throws IOException{int count=in.readInt();if(count<0||count>64)throw invalid();var map=new LinkedHashMap<String,String>();for(int index=0;index<count;index++)if(map.putIfAbsent(EtlScriptProtocol.readText(in,4096),EtlScriptProtocol.readText(in,8192))!=null)throw invalid();return map;}
    private static void bytes(DataOutputStream out,byte[] value)throws IOException{out.writeInt(value.length);out.write(value);}
    private static byte[] bytes(DataInputStream in,int limit)throws IOException{int length=in.readInt();if(length<0||length>limit||length>in.available())throw invalid();return in.readNBytes(length);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("隔离worker HTTP请求不符合有界受管协议");}
}
