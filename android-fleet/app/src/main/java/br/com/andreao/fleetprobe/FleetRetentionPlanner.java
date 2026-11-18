package br.com.andreao.fleetprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Pure policy: only the calling code supplies scoped app MP4 files; skip
 * protected incidents, age out old clips then trim oldest until under limit.
 * Counts PROTECTED files towards total quota, but never schedules deletion.
 */
public final class FleetRetentionPlanner {
  private FleetRetentionPlanner(){}
  public static final class Clip {
    public final String filename;
    public final long size,lastModifiedMs;
    public final boolean protectedIncident;
    public Clip(String name,long size,long modified,boolean protectedIncident){
      this.filename=name;this.size=Math.max(0,size);
      this.lastModifiedMs=modified;this.protectedIncident=protectedIncident;
    }
  }
  public static final class Plan {
    public final List<String> deleteNames;
    public final long beforeBytes,afterBytes,protectedBytes;
    public final boolean overQuota;
    Plan(List<String> files,long before,long after,long protect,boolean over){
      this.deleteNames=files;this.beforeBytes=before;this.afterBytes=after;
      this.protectedBytes=protect;this.overQuota=over;
    }
  }
  public static Plan calculate(Clip[] entries,long nowMs,long ttlMs,long capBytes){
    Clip[] sorted=entries.clone();
    Arrays.sort(sorted,Comparator.comparingLong(c->c.lastModifiedMs));
    long total=0,protectedTotal=0;
    for(Clip c:sorted){
      total+=c.size;
      if(c.protectedIncident)protectedTotal+=c.size;
    }
    long before=total;
    List<String> targets=new ArrayList<>();
    for(Clip c:sorted){
      if(c.protectedIncident)continue;
      boolean expired=ttlMs>0 && nowMs>c.lastModifiedMs &&
        nowMs-c.lastModifiedMs>ttlMs;
      boolean large=capBytes>0 && total>capBytes;
      if(expired||large){
        targets.add(c.filename);
        total-=c.size;
      }
    }
    return new Plan(targets,before,total,protectedTotal,
      capBytes>0&&total>capBytes);
  }
}
