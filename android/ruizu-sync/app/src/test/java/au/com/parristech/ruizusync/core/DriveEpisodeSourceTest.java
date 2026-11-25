package au.com.parristech.ruizusync.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;

public class DriveEpisodeSourceTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final String ID="synthetic001", TAG="\"version1\"";
    private static final byte[] MEDIA={1,2,3,4,5,6,7,8};
    private static class Reply extends HttpURLConnection {
        final int code; final byte[] bytes; final Map<String,String> headers=new HashMap<>();
        Reply(int status,byte[] bytes) throws Exception {
            super(new URL("https://drive.usercontent.google.com/fixture"));
            code=status; this.bytes=bytes;
            headers.put("Content-Type","video/mp4"); headers.put("ETag",TAG);
        }
        @Override public int getResponseCode(){return code;}
        @Override public String getHeaderField(String name){return headers.get(name);}
        @Override public String getContentType(){return headers.get("Content-Type");}
        @Override public long getContentLengthLong(){return bytes.length;}
        @Override public InputStream getInputStream(){
            return new ByteArrayInputStream(bytes){
                @Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(4,len));}
            };
        }
        @Override public void connect(){}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
    }
    private static void rejected(RunnableIo action) throws Exception {
        try { action.run(); fail("Expected safe refusal"); } catch(IOException expected) {}
    }
    private interface RunnableIo {void run() throws Exception;}

    @Test public void folderParsingRejectsForeignLinksAndResourceKeys() throws Exception {
        assertEquals(ID,DriveEpisodeProtocol.folderId("https://drive.google.com/drive/folders/"+ID+"?usp=sharing"));
        assertEquals(ID,DriveEpisodeProtocol.folderId(ID));
        rejected(()->DriveEpisodeProtocol.folderId("https://drive.google.com.evil.test/drive/folders/"+ID));
        rejected(()->DriveEpisodeProtocol.folderId("https://drive.google.com/drive/folders/"+ID+"?resourcekey=secret"));
        assertFalse(DriveEpisodeProtocol.allowedEndpoint(URI.create("http://drive.google.com")));
        assertFalse(DriveEpisodeProtocol.allowedEndpoint(URI.create("https://googleusercontent.com.evil.test")));
    }
    @Test public void listingRejectsSignInAndMalformedEntries() throws Exception {
        String page="<div class=\"flip-entries\"><div class=\"flip-entry\" id=\"x\">"
            +"<a href=\"https://drive.google.com/file/d/"+ID+"/view\">"
            +"<div class=\"flip-entry-title\">Show &amp; Friends S01E01.mp4</div></a></div></div>";
        List<DriveEpisodeProtocol.Entry> result=DriveEpisodeProtocol.parseListing(page);
        assertEquals(1,result.size()); assertEquals(ID,result.get(0).id);
        assertEquals("Show & Friends S01E01.mp4",result.get(0).name);
        assertFalse(result.get(0).folder);
        rejected(()->DriveEpisodeProtocol.parseListing("<html>Sign in</html>"));
        rejected(()->DriveEpisodeProtocol.parseListing("<div class=\"flip-entries\"><div class=\"flip-entry\"/>"));
    }
    @Test public void rangeMustMatchVersionAndExactInterval() throws Exception {
        assertEquals(8,DriveEpisodeProtocol.validateRange("bytes 4-7/8",4,8,4,TAG,TAG));
        rejected(()->DriveEpisodeProtocol.validateRange("bytes 3-7/8",4,8,5,TAG,TAG));
        rejected(()->DriveEpisodeProtocol.validateRange("bytes 4-7/8",4,8,4,TAG,"\"other\""));
        rejected(()->DriveEpisodeProtocol.validateRange("bytes 4-7/8",4,8,4,"W/"+TAG,"W/"+TAG));
    }
    @Test public void interruptedDownloadResumesWithoutDuplicatingBytes() throws Exception {
        Reply first=new Reply(200,MEDIA), second=new Reply(206,Arrays.copyOfRange(MEDIA,4,8));
        second.headers.put("Content-Range","bytes 4-7/8");
        ArrayDeque<Reply> replies=new ArrayDeque<>(Arrays.asList(first,second));
        DriveEpisodeSource source=new DriveEpisodeSource(uri->replies.removeFirst());
        File part=new File(folder.getRoot(),"episode.download");
        rejected(()->source.download(ID,part,4,0,()->false));
        assertEquals(4,part.length());
        assertEquals(8,source.download(ID,part,8,0,()->false));
        assertEquals("bytes=4-",second.getRequestProperty("Range"));
        assertEquals(TAG,second.getRequestProperty("If-Range"));
        assertArrayEquals(MEDIA,Files.readAllBytes(part.toPath()));
        DriveEpisodeSource.finish(part);
        assertFalse(new File(folder.getRoot(),"episode.download.resume").exists());
    }
    @Test public void nonMediaAndForeignRedirectNeverWriteToPart() throws Exception {
        Reply html=new Reply(200,MEDIA);
        html.headers.put("Content-Type","text/html; charset=UTF-8");
        File part=new File(folder.getRoot(),"episode.download");
        rejected(()->new DriveEpisodeSource(uri->html).download(ID,part,8,0,()->false));
        assertFalse(part.exists());
        Reply redirect=new Reply(302,new byte[0]);
        redirect.headers.put("Location","https://evil.test/payload");
        int[] requests={0};
        DriveEpisodeSource source=new DriveEpisodeSource(uri->{requests[0]++;return redirect;});
        rejected(()->source.download(ID,part,8,0,()->false));
        assertEquals(1,requests[0]); assertFalse(part.exists());
    }
    @Test public void changedVersionRestartsRatherThanAppending() throws Exception {
        Reply first=new Reply(200,MEDIA), second=new Reply(200,new byte[]{8,7,6,5,4,3,2,1});
        second.headers.put("ETag","\"version2\"");
        ArrayDeque<Reply> replies=new ArrayDeque<>(Arrays.asList(first,second));
        DriveEpisodeSource source=new DriveEpisodeSource(uri->replies.removeFirst());
        File part=new File(folder.getRoot(),"episode.download");
        rejected(()->source.download(ID,part,4,0,()->false));
        assertEquals(8,source.download(ID,part,8,0,()->false));
        assertArrayEquals(second.bytes,Files.readAllBytes(part.toPath()));
    }
}
