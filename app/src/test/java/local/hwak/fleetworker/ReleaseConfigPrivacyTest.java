package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class ReleaseConfigPrivacyTest {
    private static Path root(){return Path.of(System.getProperty("worker.sourceRoot"));}
    private static String read(Path path)throws java.io.IOException {return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();}
    @Test public void trackedTemplateHasPlaceholderInfrastructureAndPublicGithubIdentity()throws Exception {
        List<ReleaseSource> sources=ReleaseSource.parse(read(root().resolve("release-sources.example.json")));
        assertTrue(java.net.URI.create(sources.get(0).url).getHost().endsWith(".invalid"));
        String gitflicQuery=java.net.URI.create(sources.get(1).url).getRawQuery();
        assertTrue("GitFlic raw endpoint selects a ref using commit",gitflicQuery.contains("commit=release-channel"));
        assertFalse("GitFlic raw endpoint ignores the branch parameter",gitflicQuery.contains("branch="));
        assertEquals("manifest",sources.get(2).type);
        assertTrue(sources.get(2).url.startsWith("https://github.com/hawkab/distributed-workers-android/"));
        String ignore=read(root().resolve(".gitignore"));
        assertTrue(ignore.contains("app/src/main/assets/release-sources.json"));
        assertTrue(ignore.contains(".local/"));
    }
    @Test public void trackedSourcesExcludePrivateAssetAndItsInfrastructureEndpoint()throws Exception {
        Process git=new ProcessBuilder("git","ls-files","--cached","-z","--",".").directory(root().toFile()).start();
        byte[] output=git.getInputStream().readAllBytes();assertEquals("Git source inventory failed",0,git.waitFor());
        String inventory=new String(output,StandardCharsets.UTF_8);
        assertFalse("Private release asset must remain ignored",java.util.Arrays.asList(inventory.split("\u0000")).contains("app/src/main/assets/release-sources.json"));
        assertFalse("Operator runtime files must remain ignored",java.util.Arrays.stream(inventory.split("\u0000")).anyMatch(name->name.startsWith(".local/")));
        Path privateAsset=root().resolve(".local/release-sources.json");
        String privateHost=null;
        if(Files.isRegularFile(privateAsset)) {
            try {
                List<ReleaseSource> privateSources=ReleaseSource.parse(read(privateAsset));
                String host=java.net.URI.create(privateSources.get(0).url).getHost();
                if(!host.endsWith(".invalid") && !host.endsWith(".example"))privateHost=host;
            }catch(Exception ignored){throw new AssertionError("Invalid private release configuration; contents omitted");}
        }
        for(String name:inventory.split("\u0000")) {
            if(name.isEmpty())continue;
            Path path=root().resolve(name);
            if(!Files.isRegularFile(path) || name.endsWith(".der") || name.endsWith(".jar"))continue;
            String text;
            try{text=read(path);}catch(java.nio.charset.MalformedInputException ignored){continue;}
            if(privateHost!=null)assertFalse("Private infrastructure endpoint leaked into tracked file: "+name,text.contains(privateHost));
            java.util.regex.Matcher addresses=java.util.regex.Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])").matcher(text);
            while(addresses.find()) {
                String[] octets=addresses.group().split("\\.");int a=Integer.parseInt(octets[0]),b=Integer.parseInt(octets[1]),c=Integer.parseInt(octets[2]),d=Integer.parseInt(octets[3]);
                if(a>255||b>255||c>255||d>255)continue;
                boolean documentation=(a==192&&b==0&&c==2)||(a==198&&b==51&&c==100)||(a==203&&b==0&&c==113);
                assertTrue("Infrastructure IP literal leaked into tracked file: "+name,a==0||a==127||documentation);
            }
        }
    }
}
