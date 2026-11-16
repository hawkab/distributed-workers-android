package local.hwak.fleetworker;

import static org.junit.Assert.*;
import org.junit.Test;

public class UpdatePolicyTest {
    @Test public void permissionAndPlatformFailureDoNotEnterInstaller(){assertEquals("blocked_unsupported_silent_api",UpdatePolicy.silentBlock(30,true));assertEquals("blocked_install_permission",UpdatePolicy.silentBlock(34,false));assertNull(UpdatePolicy.silentBlock(34,true));}
    @Test public void staleCallbackCannotChangeNewSession(){assertTrue(UpdatePolicy.callbackMatches(5,5,"5",2,"2"));assertFalse(UpdatePolicy.callbackMatches(4,4,"5",2,"2"));assertFalse(UpdatePolicy.callbackMatches(5,5,"5",1,"2"));assertFalse(UpdatePolicy.callbackMatches(-1,-1,"-1",2,"2"));}
    @Test public void noClaimDuringReplacementEvenBetweenUpdateChecks(){assertTrue(UpdatePolicy.blocksClaims("writing"));assertTrue(UpdatePolicy.blocksClaims("committing"));assertFalse(UpdatePolicy.blocksClaims("applied"));assertFalse(UpdatePolicy.blocksClaims("blocked_user_action"));}
    @Test public void wrongCertificateDowngradeAndIdentityAreRefused(){
        UpdatePolicy.checkIdentity("own","own",1,2,2,"0.1.1","0.1.1","a","a","a");
        for(int variant=0;variant<4;variant++){
            try{UpdatePolicy.checkIdentity("own",variant==0?"foreign":"own",variant==1?2:1,2,2,"0.1.1",variant==2?"different":"0.1.1","a",variant==3?"b":"a","a");fail("variant "+variant);}catch(SecurityException expected){}
        }
    }
}
