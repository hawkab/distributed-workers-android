package local.hwak.fleetworker;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public locator metadata is untrusted; only the pinned RSA manifest authorizes code. */
final class ReleaseSource {
    final String id,type,url,manifestAsset,apkAsset,apkUrl;
    ReleaseSource(String id,String type,String url,String manifestAsset,String apkAsset) {
        this(id,type,url,manifestAsset,apkAsset,null);
    }
    ReleaseSource(String id,String type,String url,String manifestAsset,String apkAsset,String apkUrl) {
        if(!id.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("release_source_id");
        if(!type.equals("gitea")&&!type.equals("github")&&!type.equals("gitflic-api")&&!type.equals("channel")&&!type.equals("manifest"))throw new IllegalArgumentException("release_source_type");
        publicHttps(url);
        if(apkUrl!=null){if(!type.equals("manifest"))throw new IllegalArgumentException("release_apk_locator_type");publicHttps(apkUrl);}
        this.id=id;this.type=type;this.url=url;this.manifestAsset=manifestAsset;this.apkAsset=apkAsset;this.apkUrl=apkUrl;
    }
    static List<ReleaseSource> parse(String encoded)throws Exception {
        JSONArray array=new JSONArray(encoded);List<ReleaseSource> sources=new ArrayList<>();
        for(int i=0;i<array.length();i++) {
            JSONObject object=array.getJSONObject(i);
            java.util.Iterator<String> keys=object.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                if(!key.equals("id")&&!key.equals("type")&&!key.equals("url")&&!key.equals("manifest_asset")&&!key.equals("apk_asset")&&!key.equals("apk_url"))throw new SecurityException("release_source_field");
            }
            for(String field:new String[]{"url","apk_url"})if(object.has(field)){
                String query=URI.create(object.getString(field)).getRawQuery();
                if(query!=null && query.toLowerCase(java.util.Locale.ROOT).matches(".*(?:token|password|authorization|credential|secret).*"))throw new SecurityException("release_credentials_forbidden");
            }
            sources.add(new ReleaseSource(object.getString("id"),object.getString("type"),object.getString("url"),object.optString("manifest_asset","android-manifest.json"),object.optString("apk_asset","fleet-worker-android.apk"),object.has("apk_url")?object.getString("apk_url"):null));
        }
        if(sources.size()!=3 || !sources.get(0).id.equals("gitea") || !sources.get(1).id.equals("gitflic") || !sources.get(2).id.equals("github"))throw new IllegalArgumentException("release_chain_order");
        return sources;
    }
    Location discover(ReleaseResolver.Transport transport)throws Exception {
        JSONObject metadata=new JSONObject(transport.text(url));
        if(type.equals("manifest"))return new Location(metadata,apkUrl==null?metadata.getString("url"):apkUrl);
        if(type.equals("channel")) {
            String manifestUrl=metadata.getString("manifest_url"),apkUrl=metadata.getString("apk_url");publicHttps(manifestUrl);publicHttps(apkUrl);
            return new Location(new JSONObject(transport.text(manifestUrl)),apkUrl);
        }
        if(metadata.optBoolean("draft") || metadata.optBoolean("prerelease") || metadata.optBoolean("preRelease"))throw new IllegalArgumentException("release_not_stable");
        JSONArray assets=metadata.getJSONArray(type.equals("gitflic-api")?"attachmentFiles":"assets");
        String manifest=null,apk=null;
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);String name=asset.getString("name");
            if(name.equals(manifestAsset)) {if(manifest!=null)throw new IllegalArgumentException("release_ambiguous_manifest");manifest=asset.getString(type.equals("gitflic-api")?"link":"browser_download_url");}
            if(name.equals(apkAsset)) {if(apk!=null)throw new IllegalArgumentException("release_ambiguous_apk");apk=asset.getString(type.equals("gitflic-api")?"link":"browser_download_url");}
        }
        if(manifest==null || apk==null)throw new IllegalArgumentException("release_asset_missing");
        publicHttps(manifest);publicHttps(apk);
        return new Location(new JSONObject(transport.text(manifest)),apk);
    }
    boolean unconfigured() {
        String host=URI.create(url).getHost();
        return host!=null && (host.equalsIgnoreCase("invalid") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".invalid"));
    }
    static final class Location {
        final JSONObject manifest;final String apkUrl;
        Location(JSONObject manifest,String apkUrl) {publicHttps(apkUrl);this.manifest=manifest;this.apkUrl=apkUrl;}
    }
    static void publicHttps(String address) {
        URI uri=URI.create(address);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)throw new SecurityException("release_https_required");
    }
}
