package br.com.andreao.fleetprobe;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Circular retention for OWN finalized MP4s only.
 *
 * Preserve incidents, stop if protected footage alone exceeds quota, never
 * touch non-app files, .partial, or external apps. Retention hours are a
 * maximum age, NOT a 24h guarantee if quota or disk capacity is insufficient.
 */
public final class OemMediaRetention {
  private static final String PREFIX="andreao_fleet_cam_";
  private OemMediaRetention(){}

  private static boolean scopedMp4(String filename){
    return filename.startsWith(PREFIX)&&filename.endsWith(".mp4") &&
      !filename.contains("/")&&!filename.contains("\\")&&!filename.contains("..");
  }
  private static long quotaBytes(SharedPreferences prefs){
    float cap=prefs.getFloat("max_storage_gb",FleetStorageMath.DEFAULT_QUOTA_GB);
    return cap>0?(long)((double)cap*FleetStorageMath.GIB):0L;
  }
  private static File[] clips(File folder){
    File[] files=folder.listFiles(f->f.isFile() && scopedMp4(f.getName()));
    return files==null?new File[0]:files;
  }
  public static String clean(Context context,SharedPreferences prefs,File folder){
    if(folder==null||!folder.isDirectory())return "Pasta indisponível";
    int hours=Math.max(1,Math.min(720,(int)prefs.getFloat("retention_hours",24f)));
    long quota=quotaBytes(prefs);
    long now=System.currentTimeMillis();
    File[] files=clips(folder);
    List<FleetRetentionPlanner.Clip> candidates=new ArrayList<>();
    long actualProtected=0;
    for(File f:files){
      boolean protectedEvent=f.getName().contains(".protected.");
      if(protectedEvent)actualProtected+=Math.max(0,f.length());
      candidates.add(new FleetRetentionPlanner.Clip(f.getName(),f.length(),
        f.lastModified(),protectedEvent));
    }
    FleetRetentionPlanner.Plan plan=FleetRetentionPlanner.calculate(
      candidates.toArray(new FleetRetentionPlanner.Clip[0]),now,hours*3600000L,quota);
    int deleted=0,errors=0;long remaining=plan.beforeBytes;
    // Only filenames from own folder and trusted scan are considered.
    for(String name:plan.deleteNames){
      if(!scopedMp4(name)||name.contains(".protected."))continue;
      File target=new File(folder,name);
      if(!target.isFile() || !target.getParentFile().equals(folder)){
        errors++;continue;
      }
      long bytes=Math.max(0,target.length());
      if(target.delete()){deleted++;remaining-=bytes;}else errors++;
    }
    return "Retenção circular (somente MP4 do ANDREAO Fleet)\n"+
      "Gravações removidas: "+deleted+"; erros: "+errors+"\n"+
      "Uso após limpeza: "+(remaining/1048576L)+" MB; ocorrências protegidas: "+
      (actualProtected/1048576L)+" MB\n"+
      "Máximo por idade: "+hours+"h; teto lógico: "+
      (quota==0?"SEM TETO (risco de lotação)":String.format(java.util.Locale.US,"%.1f GiB",quota/FleetStorageMath.GIB))+
      (quota>0&&remaining>quota?"\nATENÇÃO: ocorrência protegida/erro impediu cumprir o limite!":"")+
      "\nIMPORTANTE: limite de espaço pode reduzir a duração efetiva abaixo da retenção configurada.";
  }

  public static File protect(File clip){
    if(clip==null||!clip.isFile())return null;
    String name=clip.getName();
    if(!scopedMp4(name))return null;
    if(name.contains(".protected."))return clip;
    File parent=clip.getParentFile();
    if(parent==null||!parent.canWrite())return null;
    File protectedFile=new File(parent,name.substring(0,name.length()-4)+".protected.mp4");
    if(protectedFile.exists())return null;
    if(!clip.renameTo(protectedFile) || !protectedFile.isFile())return null;
    return protectedFile;
  }

  public static boolean enoughSpace(File folder,SharedPreferences prefs){
    if(folder==null||!folder.canWrite())return false;
    float reserved=prefs.getFloat("reserve_free_gb",2f);
    long required=(long)((double)reserved*FleetStorageMath.GIB);
    if(folder.getUsableSpace()<=required)return false;
    long quota=quotaBytes(prefs);
    if(quota<=0)return true;
    long total=0;
    for(File file:clips(folder))total+=Math.max(0L,file.length());
    return total<quota;
  }
}
