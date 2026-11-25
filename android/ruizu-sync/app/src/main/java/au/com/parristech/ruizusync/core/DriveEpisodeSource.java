package au.com.parristech.ruizusync.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/** Read-only transport for explicitly approved, shared Drive folders. All staging is app private. */
public final class DriveEpisodeSource {
    private static final int TIMEOUT=30000, MAX_REDIRECTS=5;
    public static final long MAX_FILE_BYTES=8L*1024*1024*1024;
    private final ConnectionFactory connections;

    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }
    public DriveEpisodeSource() { this(uri -> (HttpURLConnection)uri.toURL().openConnection()); }
    DriveEpisodeSource(ConnectionFactory connections) { this.connections=connections; }

    private static void check(BooleanSupplier stopped) throws InterruptedIOException {
        if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Episode download paused");
    }

    private HttpURLConnection open(String address,long offset,String etag,BooleanSupplier stopped) throws IOException {
        URI current=URI.create(address);
        for(int redirects=0;redirects<=MAX_REDIRECTS;redirects++) {
            check(stopped);
            if(!DriveEpisodeProtocol.allowedEndpoint(current)) throw new IOException("Untrusted Drive endpoint");
            HttpURLConnection c=connections.open(current);
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT);
                c.setRequestProperty("User-Agent",DriveEpisodeProtocol.USER_AGENT);
                c.setRequestProperty("Accept-Encoding","identity");
                if(offset>0) {
                    c.setRequestProperty("Range","bytes="+offset+"-");
                    c.setRequestProperty("If-Range",etag);
                }
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=c.getHeaderField("Location");
                    if(location==null) throw new IOException("Drive redirect missing destination");
                    current=current.resolve(location);
                    c.disconnect();
                    continue;
                }
                return c;
            } catch(IOException|RuntimeException e) { c.disconnect(); throw e; }
        }
        throw new IOException("Too many Drive redirects");
    }

    public List<DriveEpisodeProtocol.Entry> list(String folder,BooleanSupplier stopped) throws IOException {
        HttpURLConnection c=open(DriveEpisodeProtocol.listUrl(folder),0,null,stopped);
        try {
            if(c.getResponseCode()!=200) throw new IOException("Drive shared folder unavailable: HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[16384]; int n;
                while((n=in.read(buffer))!=-1) {
                    check(stopped);
                    if(out.size()+n>DriveEpisodeProtocol.MAX_PAGE_BYTES) throw new IOException("Drive listing too large");
                    out.write(buffer,0,n);
                }
                return DriveEpisodeProtocol.parseListing(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); }
    }

    private static class Resume {
        final String id,etag;
        final long total;
        Resume(String id,String etag,long total) { this.id=id; this.etag=etag; this.total=total; }
    }
    private static Resume load(File metadata,String id) {
        if(!metadata.isFile()||metadata.length()>4096) return null;
        Properties values=new Properties();
        try(InputStream in=new FileInputStream(metadata)) {
            values.load(in);
            long total=Long.parseLong(values.getProperty("total",""));
            String tag=values.getProperty("etag");
            if(id.equals(values.getProperty("id"))&&DriveEpisodeProtocol.strongValidator(tag)&&total>0&&total<=MAX_FILE_BYTES)
                return new Resume(id,tag,total);
        } catch(IOException|RuntimeException ignored) {}
        return null;
    }
    private static void save(File metadata,Resume resume) throws IOException {
        File temporary=new File(metadata.getParentFile(),metadata.getName()+".tmp");
        Properties values=new Properties();
        values.setProperty("id",resume.id); values.setProperty("etag",resume.etag);
        values.setProperty("total",Long.toString(resume.total));
        try(FileOutputStream out=new FileOutputStream(temporary,false)) {
            values.store(out,null); out.getFD().sync();
        }
        if(!temporary.renameTo(metadata)) throw new IOException("Cannot persist download checkpoint");
    }
    private static void remove(File file) throws IOException {
        if(file.exists()&&!file.delete()) throw new IOException("Cannot reset app-private download checkpoint");
    }
    private static void reset(File part,File metadata) throws IOException { remove(metadata); remove(part); }

    /** Limit is the maximum bytes this invocation may append, not a promise to finish. */
    public long download(String fileId,File part,long budget,long reserve,BooleanSupplier stopped) throws IOException {
        String id=DriveEpisodeProtocol.fileId(fileId);
        if(budget<=0) throw new IOException("Download budget exhausted");
        File metadata=new File(part.getParentFile(),part.getName()+".resume");
        Resume resume=load(metadata,id);
        long offset=part.isFile()?part.length():0;
        if(resume==null||offset<=0||offset>resume.total) {
            reset(part,metadata); offset=0; resume=null;
        }
        if(resume!=null&&offset==resume.total) return offset;
        HttpURLConnection c=open(DriveEpisodeProtocol.downloadUrl(id),offset,resume==null?null:resume.etag,stopped);
        try {
            int status=c.getResponseCode();
            if(status!=200&&status!=206) throw new IOException("Drive download unavailable: HTTP "+status);
            if(!DriveEpisodeProtocol.mediaType(c.getContentType())) throw new IOException("Drive returned a non-media response; retry later");
            String encoding=c.getHeaderField("Content-Encoding");
            if(encoding!=null&&!"identity".equalsIgnoreCase(encoding)) throw new IOException("Unexpected Drive content encoding");
            String tag=c.getHeaderField("ETag");
            long total;
            if(status==206) {
                if(resume==null) throw new IOException("Unexpected partial response");
                try {
                    total=DriveEpisodeProtocol.validateRange(c.getHeaderField("Content-Range"),
                        offset,resume.total,c.getContentLengthLong(),resume.etag,tag);
                } catch(IOException changed) {
                    reset(part,metadata);
                    throw changed;
                }
            } else {
                // If-Range fell back to a complete representation. Never append it to old bytes.
                reset(part,metadata); offset=0;
                total=c.getContentLengthLong();
                if(total<=0||total>MAX_FILE_BYTES) throw new IOException("Drive file length unknown or exceeds safe size");
                if(DriveEpisodeProtocol.strongValidator(tag)) save(metadata,new Resume(id,tag,total));
            }
            long done=offset,added=0;
            try(InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(part,offset>0)) {
                try {
                    byte[] buffer=new byte[128*1024]; int n;
                    while((n=in.read(buffer))!=-1) {
                        check(stopped);
                        if(n==0) continue;
                        if(n>total-done) throw new IOException("Drive response exceeded expected size");
                        if(n>budget-added) throw new IOException("Download budget reached; checkpoint retained if server supports safe resume");
                        if(part.getParentFile().getUsableSpace()<reserve+n) throw new IOException("Phone storage reserve reached");
                        out.write(buffer,0,n); done+=n; added+=n;
                    }
                    if(done!=total) throw new EOFException("Drive download incomplete");
                } finally { out.getFD().sync(); }
            }
            return done;
        } finally { c.disconnect(); }
    }

    /** Clean up only the matching app-private metadata after its source file is checkpointed. */
    public static void finish(File part) throws IOException {
        remove(new File(part.getParentFile(),part.getName()+".resume"));
    }
}
