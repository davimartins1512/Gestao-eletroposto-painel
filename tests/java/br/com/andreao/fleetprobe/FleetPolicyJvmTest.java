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
    System.out.println("ANDREAO Fleet JVM budget + protected circular retention: PASS");
  }
}
