package br.com.andreao.fleetprobe;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** Cleanup only our own finalized recordings, never Electro or protected events. */
public final class OemMediaRetention {
  private static final String PREFIX="andreao_fleet_cam_";
  private OemMediaRetention(){}
  public static String clean(Context context,SharedPreferences prefs,File folder) {
    if(folder==null||!folder.isDirectory())return "Pasta não disponível";
    int retentionHours=Math.max(1,Math.min(720,(int)prefs.getFloat("retention_hours",24f)));
    float limitGb=prefs.getFloat("max_storage_gb",0f);
    long byteLimit=limitGb>0?(long)(limitGb*1024L*1024L*1024L):Long.MAX_VALUE;
    File[] files=folder.listFiles(f->f.isFile() && f.getName().startsWith(PREFIX)
      && f.getName().endsWith(".mp4") && !f.getName().contains(".protected."));
    if(files==null)return "Sem acesso à lista de gravações";
    Arrays.sort(files,Comparator.comparingLong(File::lastModified));
    long total=0,now=System.currentTimeMillis(),maxAge=retentionHours*3600000L;
    int removed=0,failed=0;
    for(File f:files)total+=Math.max(0L,f.length());
    for(File f:files){
      if(now-f.lastModified()>maxAge || total>byteLimit){
        long len=Math.max(0L,f.length());
        if(f.delete()){removed++;total-=len;}else failed++;
      }
    }
    return "Gravações antigas removidas: "+removed+"; falhas: "+failed
       +"; ocupação gerenciada: "+(total/1048576L)+" MB; retenção: "
       +retentionHours+"h; limite: "+(byteLimit==Long.MAX_VALUE?"sem teto lógico":limitGb+" GB");
  }
  public static boolean enoughSpace(File folder,SharedPreferences prefs){
    if(folder==null||!folder.canWrite())return false;
    float reserved=prefs.getFloat("reserve_free_gb",2f);
    return folder.getUsableSpace()>(long)(reserved*1024L*1024L*1024L);
  }
}