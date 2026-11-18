package br.com.andreao.fleetprobe;
import android.Manifest;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner opt-in best-effort sync of final MP4s. No camera, wake lock, boot receiver or cellular usage. */
public final class FleetWifiAutoSyncJob extends JobService {
  public static final String PREFS="fleet_wifi_automatic_v34";
  public static final String LAST="last_report";
  private static final int JOB_ID=34018;
  private final AtomicBoolean cancel=new AtomicBoolean();
  private volatile Thread worker;

  public static String connectedSsid(Context c){
    try {
      if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;
      WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      WifiInfo info=wm==null?null:wm.getConnectionInfo();
      if(info==null)return null;
      String s=info.getSSID();
      if(s==null)return null;
      s=s.trim();
      if(s.length()>1&&s.charAt(0)=='"'&&s.charAt(s.length()-1)=='"')s=s.substring(1,s.length()-1);
      if(s.isEmpty()||s.equalsIgnoreCase("<unknown ssid>")||s.length()>64||s.indexOf('\n')>=0||s.indexOf('\r')>=0)return null;
      return s;
    }catch(Exception denied){return null;}
  }
  public static boolean enabled(Context c){return c.getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("enabled",false);}
  public static void disable(Context c){
    c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().clear().commit();
    JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if(js!=null)js.cancel(JOB_ID);
  }
  public static void enable(Context c,String ssid){
    if(ssid==null||!ssid.equals(connectedSsid(c)))throw new IllegalArgumentException("Conexao Wi-Fi alterada ou SSID indisponivel");
    boolean ok=c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("enabled",true).putString("ssid",ssid).commit();
    if(!ok)throw new IllegalStateException("Falha na autorizacao salva");
    try{schedule(c);}catch(RuntimeException e){disable(c);throw e;}
  }
  public static void schedule(Context c){
    if(!enabled(c))return;
    JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if(js==null)throw new IllegalStateException("JobScheduler indisponivel");
    if(js.getPendingJob(JOB_ID)!=null)return; // do not reset the 15-minute clock on every app open
    JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(c,FleetWifiAutoSyncJob.class))
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(15L*60*1000)
      .setPersisted(false).build();
    if(js.schedule(info)!=JobScheduler.RESULT_SUCCESS)throw new IllegalStateException("Agendamento recusado pelo Android");
  }
  public static void restoreWhenAppOpens(Context c){
    if(!enabled(c))return;
    try{schedule(c);}catch(RuntimeException e){c.getSharedPreferences(PREFS,MODE_PRIVATE)
      .edit().putString(LAST,"Firmware nao aceitou agendamento").apply();}
  }
  private static boolean trustedWifi(Context c){
    try{
      ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network n=cm==null?null:cm.getActiveNetwork();
      NetworkCapabilities p=n==null?null:cm.getNetworkCapabilities(n);
      if(p==null||!p.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||
        p.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||
        p.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||
        !p.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)||
        !p.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))return false;
      PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
      if(pm==null||!pm.isInteractive())return false;
      String ssid=connectedSsid(c);
      return enabled(c)&&ssid!=null&&ssid.equals(c.getSharedPreferences(PREFS,MODE_PRIVATE).getString("ssid",""));
    }catch(Exception e){return false;}
  }
  private void record(String message){getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(LAST,message).apply();}
  private static String marker(OemRecordingLibrary.Entry e){
    return e.file.getAbsolutePath()+"|"+e.sizeBytes+"|"+e.lastModified+"|"+e.protectedEvent;
  }
  @Override public boolean onStartJob(JobParameters params){
    if(!trustedWifi(this)){record("Rede nao autorizada, medida ou tela desligada; nenhum envio.");return false;}
    if(worker!=null&&worker.isAlive())return false;
    cancel.set(false);
    worker=new Thread(()->{
      int sent=0,existing=0,failed=0;
      long start=SystemClock.elapsedRealtime(),budget=0;
      try{
        FleetCloudUploader.Config cfg=FleetCloudSecretStore.load(getApplicationContext());
        if(cfg==null||cfg.allowMeteredWifi)throw new SecurityException("Credencial ausente ou Wi-Fi medido");
        List<OemRecordingLibrary.Entry> clips=OemRecordingLibrary.list(getApplicationContext());
        android.content.SharedPreferences sp=getSharedPreferences(PREFS,MODE_PRIVATE);
        Set<String> done=new HashSet<>(sp.getStringSet("uploaded_markers",new HashSet<String>()));
        Set<String> present=new HashSet<>();
        for(OemRecordingLibrary.Entry clip:clips)present.add(marker(clip));
        done.retainAll(present); // no unlimited growth after the retention policy deletes files
        for(OemRecordingLibrary.Entry clip:clips){
          String item=marker(clip);
          if(done.contains(item))continue;
          if(cancel.get()||!trustedWifi(this)||SystemClock.elapsedRealtime()-start>180000)break;
          if(sent+existing>=2||budget+clip.sizeBytes>110L*1024*1024)break;
          budget+=clip.sizeBytes;
          try{
            String result=FleetCloudUploader.uploadOne(getApplicationContext(),cfg,clip.file,cancel,line->{});
            if(result.startsWith("JA EXISTIA"))existing++;else sent++;
            done.add(item);
            if(!sp.edit().putStringSet("uploaded_markers",new HashSet<>(done)).commit())
              throw new IllegalStateException("Nao foi possivel registrar sincronizacao");
          }catch(Exception e){
            failed++;
            if(e instanceof SecurityException||!trustedWifi(this))break;
          }
        }
      }catch(Exception e){failed++;}
      finally{
        record("Wi-Fi autorizado: novos "+sent+", existentes "+existing+", falhas "+failed);
        jobFinished(params,false);
      }
    },"fleet-approved-wifi-only");
    worker.start();
    return true;
  }
  @Override public boolean onStopJob(JobParameters params){
    cancel.set(true);
    Thread t=worker;if(t!=null)t.interrupt();
    return false;
  }
}
