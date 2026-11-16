package local.hwak.fleetworker;

/** Pure admission/security decisions shared by the real installer and tests. */
final class UpdatePolicy {
    static String silentBlock(int api,boolean permission) {
        if(api<31)return "blocked_unsupported_silent_api";
        return permission?null:"blocked_install_permission";
    }
    static boolean blocksClaims(String state){return "committing".equals(state)||"writing".equals(state);}
    static boolean callbackMatches(int reported,int expected,String persistedSession,int version,String persistedVersion){return reported>=0 && reported==expected && Integer.toString(reported).equals(persistedSession) && Integer.toString(version).equals(persistedVersion);}
    static void checkIdentity(String packageName,String actualPackage,long installedVersion,long expectedVersion,long archiveVersion,String expectedName,String actualName,String installedCert,String archiveCert,String expectedCert) {
        if(!packageName.equals(actualPackage) || expectedVersion!=archiveVersion || archiveVersion<=installedVersion || !expectedName.equals(actualName))throw new SecurityException("apk_identity");
        if(!archiveCert.equalsIgnoreCase(expectedCert) || !archiveCert.equals(installedCert))throw new SecurityException("apk_certificate");
    }
}
