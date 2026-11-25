package local.hwak.fleetworker;
import org.junit.Test;
import static org.junit.Assert.*;
public class ClaimWaitTest {
 @Test public void honorsOnlyAnActualClaimWaitPreference(){
  assertTrue(Api.appliedClaimWait("/v1/jobs/claim","wait=20"));
  assertTrue(Api.appliedClaimWait("/v1/jobs/claim","return=minimal, wait=20"));
  assertFalse(Api.appliedClaimWait("/v1/jobs/claim",null));
  assertFalse(Api.appliedClaimWait("/v1/jobs/claim","wait=0"));
  assertFalse(Api.appliedClaimWait("/v1/jobs/claim","return=minimal"));
  assertFalse(Api.appliedClaimWait("/v1/workers/heartbeat","wait=20"));
 }
}
