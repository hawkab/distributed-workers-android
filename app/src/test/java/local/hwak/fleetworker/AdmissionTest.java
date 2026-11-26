package local.hwak.fleetworker;
import org.junit.Test;import static org.junit.Assert.*;
public class AdmissionTest{
 private static final long MB=1024L*1024;
 @Test public void batteryAndForegroundRemainSingleThreaded(){assertEquals(1,Admission.slots(8,220*MB,256*MB,false,false,false,false,0,.01,0,1));assertEquals(1,Admission.slots(8,220*MB,256*MB,false,true,true,false,0,.01,0,1));}
 @Test public void pluggedIdleCanUseLightParallelism(){assertEquals(2,Admission.slots(8,240*MB,256*MB,false,true,false,false,0,.01,0,1));}
 @Test public void pressureHeatAndUnknownCpuNeverExpand(){assertEquals(0,Admission.slots(8,30*MB,256*MB,false,true,false,false,0,.01,1,2));assertEquals(0,Admission.slots(8,240*MB,256*MB,true,true,false,false,0,.01,1,2));assertEquals(0,Admission.slots(8,240*MB,256*MB,false,true,false,false,2,.01,1,2));assertEquals(0,Admission.slots(8,240*MB,256*MB,false,true,false,false,0,.95,1,2));assertEquals(1,Admission.slots(8,240*MB,256*MB,false,true,false,false,0,null,1,2));}
}
