package au.com.parristech.ruizusync.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shared, public Drive folder protocol proven by the phone pipeline. No credentials. */
public final class DriveEpisodeProtocol {
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{10,128}");
    private static final Pattern FOLDER=Pattern.compile("/drive/(?:u/[0-9]+/)?folders/([A-Za-z0-9_-]{10,128})/?");
    private static final Pattern HREF=Pattern.compile("href=\"https://drive\\.google\\.com/(drive/folders|file/d)/([A-Za-z0-9_-]{10,128})(?=[/?\"#])");
    private static final Pattern TITLE=Pattern.compile("<div class=\"flip-entry-title\">(.*?)</div>",Pattern.DOTALL);
    private static final Pattern RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    public static final int MAX_PAGE_BYTES=5_000_000, MAX_ENTRIES=2000;
    public static final String USER_AGENT="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private DriveEpisodeProtocol() {}

    public static String folderId(String input) throws IOException {
        String value=input==null?"":input.trim();
        if(ID.matcher(value).matches()) return value;
        try {
            URI uri=URI.create(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||!"drive.google.com".equalsIgnoreCase(uri.getHost())
                    ||uri.getRawUserInfo()!=null||uri.getPort()!=-1)
                throw new IllegalArgumentException();
            Matcher m=FOLDER.matcher(uri.getPath());
            if(m.matches() && (uri.getRawQuery()==null || !uri.getRawQuery().contains("resourcekey="))) return m.group(1);
        } catch(IllegalArgumentException ignored) {}
        throw new IOException("Enter a shared Google Drive folder ID or HTTPS folder link (resource keys not supported)");
    }

    public static String fileId(String id) throws IOException {
        if(id==null||!ID.matcher(id).matches()) throw new IOException("Invalid Drive file identifier");
        return id;
    }

    public static boolean allowedEndpoint(URI uri) {
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getRawUserInfo()!=null
                ||uri.getHost()==null||(uri.getPort()!=-1&&uri.getPort()!=443)) return false;
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("drive.google.com")||host.equals("drive.usercontent.google.com")
                ||host.equals("googleusercontent.com")||host.endsWith(".googleusercontent.com");
    }

    public static String listUrl(String id) throws IOException {
        return "https://drive.google.com/embeddedfolderview?id="+fileId(id);
    }
    public static String downloadUrl(String id) throws IOException {
        return "https://drive.usercontent.google.com/download?id="+fileId(id)+"&export=download&confirm=t";
    }

    public static final class Entry {
        public final String name,id;
        public final boolean folder;
        Entry(String name,String id,boolean folder) { this.name=name; this.id=id; this.folder=folder; }
    }

    public static List<Entry> parseListing(String html) throws IOException {
        if(html==null||html.length()>MAX_PAGE_BYTES||!html.contains("class=\"flip-entries\""))
            throw new IOException("Drive did not return a shared folder listing");
        String[] blocks=html.split("<div class=\"flip-entry\"",-1);
        if(blocks.length-1>MAX_ENTRIES) throw new IOException("Drive folder listing exceeds safe limit");
        List<Entry> entries=new ArrayList<>();
        for(int i=1;i<blocks.length;i++) {
            Matcher href=HREF.matcher(blocks[i]), title=TITLE.matcher(blocks[i]);
            if(!href.find()||!title.find()) throw new IOException("Drive folder listing format changed");
            String name=title.group(1).replaceAll("<[^>]+>","")
                .replace("&quot;","\"").replace("&#39;","'").replace("&#x27;","'")
                .replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
            name=name.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]+"," ").trim();
            if(name.isEmpty()||name.length()>200) throw new IOException("Drive returned an invalid entry name");
            entries.add(new Entry(name,href.group(2),"drive/folders".equals(href.group(1))));
        }
        return entries;
    }

    public static boolean mediaType(String header) {
        if(header==null) return false;
        String type=header.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        return type.startsWith("video/") || type.equals("application/octet-stream")
                ||type.equals("application/force-download")||type.equals("application/download")
                ||type.equals("application/x-matroska");
    }

    public static boolean strongValidator(String etag) {
        return etag!=null && etag.length()>=2 && etag.length()<=512
                && etag.startsWith("\"") && etag.endsWith("\"")
                && !etag.contains("\r") && !etag.contains("\n");
    }

    /** Resume only if both the representation and the exact returned byte interval match. */
    public static long validateRange(String range,long offset,long expected,long contentLength,
                                     String previousTag,String responseTag) throws IOException {
        if(!strongValidator(previousTag)||!previousTag.equals(responseTag))
            throw new IOException("Drive file version changed or validator missing");
        Matcher m=RANGE.matcher(range==null?"":range);
        if(!m.matches()) throw new IOException("Invalid Drive Content-Range");
        try {
            long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2)),total=Long.parseLong(m.group(3));
            if(offset<=0||start!=offset||total!=expected||end!=total-1||end<start
                    ||contentLength!=end-start+1)
                throw new IOException("Drive returned the wrong byte interval");
            return total;
        } catch(NumberFormatException e) { throw new IOException("Invalid Drive Content-Range",e); }
    }
}
