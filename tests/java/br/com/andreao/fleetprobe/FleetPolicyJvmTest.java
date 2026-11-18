package br.com.andreao.fleetprobe;

import java.util.Arrays;
import java.util.List;

public final class FleetPolicyJvmTest {
  private static void require(boolean condition,String description){
    if(!condition)throw new AssertionError(description);
  }
  private static FleetRetentionPlanner.Clip c(String name,long bytes,long ageMs,long now,boolean incident){
    return new FleetRetentionPlanner.Clip(name,bytes,now-ageMs,incident);
  }
  public static void main(String[] args){
    double day=FleetStorageMath.expectedGiB(24,4,false);
    require(day>39&&day<44,"4Mbps 24h forecast must reflect real-world ~40GiB");
    require(FleetStorageMath.expectedGiB(24,1.5,false)<day,
      "Lower bitrate must lower cost");
    require(FleetStorageMath.hoursInQuota(8,4,false)>4 &&
      FleetStorageMath.hoursInQuota(8,4,false)<5,
      "8GiB at 4Mbps cannot honestly promise 24h");
    require(FleetStorageMath.expectedGiB(0,4,false)==0,"no negative forecast");
    require(FleetStorageMath.bitrateForBudget(24,24,false)>2 &&
      FleetStorageMath.bitrateForBudget(24,24,false)<3,
      "24GiB/24h should suggest ~2Mbps");

    long now=1795029292000L, hour=3600000L;
    FleetRetentionPlanner.Clip old=c("old.mp4",50,30*hour,now,false);
    FleetRetentionPlanner.Clip protectedClip=c("protected.mp4",60,100*hour,now,true);
    FleetRetentionPlanner.Clip recent=c("new.mp4",40,10*60*1000L,now,false);
    FleetRetentionPlanner.Plan byAge=FleetRetentionPlanner.calculate(
      new FleetRetentionPlanner.Clip[]{recent,protectedClip,old},now,24*hour,200);
    require(byAge.deleteNames.equals(Arrays.asList("old.mp4")),"expire ordinary oldest");
    require(byAge.protectedBytes==60,"protected occupancy accounted for");
    require(byAge.afterBytes==100,"future occupation after age cleanup");

    FleetRetentionPlanner.Plan bySize=FleetRetentionPlanner.calculate(
      new FleetRetentionPlanner.Clip[]{recent,old,protectedClip},now,0,105);
    require(bySize.deleteNames.equals(Arrays.asList("old.mp4")),"trim oldest over quota");
    require(bySize.afterBytes==100&&!bySize.overQuota,"must respect cap");
    require(!bySize.deleteNames.contains("protected.mp4"),"never delete evidence");

    FleetRetentionPlanner.Plan impossible=FleetRetentionPlanner.calculate(
      new FleetRetentionPlanner.Clip[]{protectedClip,recent},now,0,32);
    require(impossible.deleteNames.contains("new.mp4"),"reclaim ordinary footage");
    require(impossible.overQuota&&impossible.afterBytes==60,
      "protected evidence over capacity must be flagged, never deleted");
    require(!impossible.deleteNames.contains("protected.mp4"),"incident untouched");
    require(FleetRetentionPlanner.calculate(new FleetRetentionPlanner.Clip[0],now,24*hour,100).deleteNames.isEmpty(),
      "empty directory safe");
    byte[] raw=new byte[32*16*3/2];
    for(int i=0;i<raw.length;i++)raw[i]=(byte)i;
    Nv21Downsampler sampler=new Nv21Downsampler(32,16,16,16);
    java.nio.ByteBuffer nv12=java.nio.ByteBuffer.allocate(16*16*3/2);
    sampler.writeNv12(nv12,raw);
    require(nv12.position()==384,"NV12 output size");
    require(nv12.get(0)==raw[0]&&nv12.get(1)==raw[2],"Y sampling x2");
    require(nv12.get(16)==raw[32],"Y sample row");
    require(nv12.get(255)==raw[510],"last Y sample");
    require(nv12.get(256)==raw[513]&&nv12.get(257)==raw[512],
      "NV21 VU -> NV12 UV");
    require(nv12.get(258)==raw[517]&&nv12.get(259)==raw[516],
      "second chroma source sample");
    java.nio.ByteBuffer i420=java.nio.ByteBuffer.allocate(384);
    sampler.writeI420(i420,raw);
    require(i420.position()==384,"I420 output size");
    require(i420.get(256)==raw[513]&&i420.get(320)==raw[512],
      "I420 U/V planes are in correct order");
    boolean shortInputRejected=false;
    try{sampler.writeNv12(java.nio.ByteBuffer.allocate(384),new byte[2]);}
    catch(IllegalArgumentException expected){shortInputRejected=true;}
    require(shortInputRejected,"undersized raw OEM bytes must fail closed");
    System.out.println("ANDREAO Fleet JVM budget + protected circular retention: PASS");
  }
}
