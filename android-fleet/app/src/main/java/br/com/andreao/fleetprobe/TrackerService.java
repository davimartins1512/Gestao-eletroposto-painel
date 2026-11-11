package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Piloto local de 3 minutos: GPS e sensores Android, apenas após toque explícito. */
public final class TrackerService extends Service implements LocationListener,SensorEventListener {
  public static final String ACTION_START="br.com.andreao.fleetprobe.TRACK_START";
  public static final String ACTION_STOP="br.com.andreao.fleetprobe.TRACK_STOP";
  public static final String PREFS="fleet_tracks";
  public static final String LAST_FILE="last_file";
  public static final String LAST_COUNT="last_count";
  public static final String LAST_STATUS="last_status";
  public static final String RUNNING="running";
  private static final String CHANNEL="andreao_fleet_tracking";
  private static final int NOTIFICATION=8127;
  private static final long MAX_DURATION_MS=180000L;
  private final Handler main=new Handler(Looper.getMainLooper());
  private boolean running=false;
  private BufferedWriter writer;
  private File file;
  private LocationManager locations;
  private SensorManager sensors;
  private long startedElapsed,lastSensorRow;
  private int points,sensorRows;
  private float ax,ay,az,gx,gy,gz;
  private String status="Não iniciado";
  private final Runnable timeout=()->{if(running){stopLogging("Tempo limite de 3 minutos atingido");stopSelf();}};

  @Override public void onCreate(){super.onCreate();}

  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?"":intent.getAction();
    if(ACTION_STOP.equals(action)){
      stopLogging("Parado manualmente");
      stopSelf();
      return START_NOT_STICKY;
    }
    if(ACTION_START.equals(action)){
      if(!running){
        try{startLogging();}
        catch(Throwable e){
          status="Falha ao iniciar: "+e.getClass().getSimpleName();
          getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(LAST_STATUS,status).putBoolean(RUNNING,false).apply();
          stopLogging(status);
          stopSelf();
        }
      }
    }else if(!running)stopSelf();
    return START_NOT_STICKY;
  }
  private void startLogging() throws Exception{
    // A notificação deixa claro que a localização está sendo coletada.
    NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if(Build.VERSION.SDK_INT>=26 && manager!=null){
      NotificationChannel channel=new NotificationChannel(CHANNEL,"ANDREAO Fleet · teste GPS",NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Coleta local voluntária de GPS e movimento por no máximo três minutos.");
      manager.createNotificationChannel(channel);
    }
    Intent stop=new Intent(this,TrackerService.class).setAction(ACTION_STOP);
    PendingIntent action=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification note=new Notification.Builder(this,CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_compass)
      .setContentTitle("ANDREAO Fleet · teste GPS ativo")
      .setContentText("Gravando localização e sensores localmente · máximo 3 min")
      .setOngoing(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Parar coleta",action).build())
      .build();
    startForeground(NOTIFICATION,note);

    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
      &&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("Localização não autorizada");

    File dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
    if(dir==null)dir=new File(getFilesDir(),"tracks");
    if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Pasta de trajetos indisponível");
    String name="andreao_fleet_gps_v06_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv";
    file=new File(dir,name);
    writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),"UTF-8"));
    writer.write("timestamp_utc,event,provider,latitude,longitude,accuracy_m,speed_mps,altitude_m,ax_mps2,ay_mps2,az_mps2,gx_rads,gy_rads,gz_rads\n");
    writer.flush();
    points=0; sensorRows=0; startedElapsed=SystemClock.elapsedRealtime();lastSensorRow=0;
    running=true;
    getSharedPreferences(PREFS,MODE_PRIVATE).edit()
      .putString(LAST_FILE,file.getAbsolutePath()).putString(LAST_STATUS,"Coletando")
      .putInt(LAST_COUNT,0).putBoolean(RUNNING,true).apply();

    locations=(LocationManager)getSystemService(LOCATION_SERVICE);
    if(locations!=null){
      boolean requested=false;
      try{
        if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){
          locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this,Looper.getMainLooper());
          requested=true;
        }
      }catch(Throwable e){writeEvent("GPS_ERRO","gps",e.getClass().getSimpleName());}
      try{
        if(locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
          locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000L,0f,this,Looper.getMainLooper());
          requested=true;
        }
      }catch(Throwable e){writeEvent("GPS_ERRO","network",e.getClass().getSimpleName());}
      if(!requested)writeEvent("GPS_INDISPONIVEL","nenhum_provedor","ative_localizacao");
    }else writeEvent("GPS_INDISPONIVEL","servico","ausente");

    sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
    if(sensors!=null){
      Sensor accel=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
      Sensor gyro=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
      if(accel!=null)sensors.registerListener(this,accel,SensorManager.SENSOR_DELAY_NORMAL);
      if(gyro!=null)sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_NORMAL);
    }
    main.postDelayed(timeout,MAX_DURATION_MS);
  }
  private String utc(long millis){
    SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return fmt.format(new Date(millis));
  }
  private String f(float n){return String.format(Locale.US,"%.4f",n);}
  private void write(String row){
    if(!running||writer==null)return;
    try{writer.write(row);writer.newLine();writer.flush();}
    catch(Throwable e){status="Falha na gravação: "+e.getClass().getSimpleName();stopLogging(status);stopSelf();}
  }
  private void writeEvent(String event,String provider,String detail){
    write(utc(System.currentTimeMillis())+","+event+","+provider+",,,,,,,,,,");
  }
  @Override public void onLocationChanged(Location value){
    if(!running||value==null)return;
    points++;
    String row=utc(value.getTime())+",GPS,"+value.getProvider()+","
      +String.format(Locale.US,"%.7f",value.getLatitude())+","
      +String.format(Locale.US,"%.7f",value.getLongitude())+","
      +(value.hasAccuracy()?f(value.getAccuracy()):"")+","
      +(value.hasSpeed()?f(value.getSpeed()):"")+","
      +(value.hasAltitude()?String.format(Locale.US,"%.2f",value.getAltitude()):"")
      +",,,,,,";
    write(row);
    if(points%5==0)getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(LAST_COUNT,points).apply();
  }
  @Override public void onSensorChanged(SensorEvent event){
    if(!running||event==null||event.values.length<3)return;
    if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER){
      ax=event.values[0];ay=event.values[1];az=event.values[2];
    } else if(event.sensor.getType()==Sensor.TYPE_GYROSCOPE){
      gx=event.values[0];gy=event.values[1];gz=event.values[2];
    } else return;
    long now=SystemClock.elapsedRealtime();
    if(now-lastSensorRow<2000L)return;
    lastSensorRow=now;sensorRows++;
    write(utc(System.currentTimeMillis())+",SENSOR,,,,,,,"+f(ax)+","+f(ay)+","+f(az)+","+f(gx)+","+f(gy)+","+f(gz));
  }
  @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
  private void stopLogging(String reason){
    if(!running && writer==null)return;
    running=false;
    main.removeCallbacks(timeout);
    if(locations!=null)try{locations.removeUpdates(this);}catch(Throwable ignored){}
    if(sensors!=null)try{sensors.unregisterListener(this);}catch(Throwable ignored){}
    status=reason+" · pontos GPS: "+points+" · amostras sensores: "+sensorRows;
    if(writer!=null){
      try{writer.flush();writer.close();}catch(Throwable ignored){}
      writer=null;
    }
    getSharedPreferences(PREFS,MODE_PRIVATE).edit()
      .putBoolean(RUNNING,false).putInt(LAST_COUNT,points)
      .putString(LAST_STATUS,status).apply();
    stopForeground(true);
  }
  @Override public void onDestroy(){stopLogging("Serviço encerrado");super.onDestroy();}
  @Override public IBinder onBind(Intent intent){return null;}
}
