package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico local SOMENTE LEITURA. Não abre as câmeras automaticamente e
 * não tem acesso a CAN, BMS, controles do BYD ou APIs privadas.
 */
public final class LaunchActivity extends Activity {
  private static final int REQUEST_CAMERA=110, REQUEST_LOCATION=111, SAVE_REPORT=112;
  private static final String PREF="andreao_fleet_diagnostic";
  private static final String CRASH="last_java_crash";
  private TextView output;
  private final StringBuilder report=new StringBuilder();

  @Override public void onCreate(Bundle savedState) {
    super.onCreate(savedState);
    installCrashReporter();
    ScrollView scroll=new ScrollView(this);
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(25,25,25,25);
    box.setBackgroundColor(0xfff6faf7);
    TextView title=new TextView(this);
    title.setText("ANDREAO FLEET · DIAGNÓSTICO v0.3");
    title.setTextColor(0xff137642);
    title.setTextSize(22);
    box.addView(title);
    TextView note=label("TESTE COMPLETO · somente leitura · offline.\nCarro em P, com freio de estacionamento acionado. Nenhum teste inicia câmeras ou microfone automaticamente.");
    box.addView(note);
    button(box,"1 · Sistema e multimídia",this::systemTest);
    button(box,"2 · Bateria Android (não é bateria de tração)",this::batteryTest);
    button(box,"3 · Sensores / movimento (lista, não condução)",this::sensorTest);
    button(box,"4 · Câmeras disponíveis no Android",this::cameraTest);
    button(box,"5 · GPS (com autorização)",this::locationTest);
    button(box,"6 · Microfones disponíveis (sem gravar)",this::microphoneTest);
    button(box,"7 · Wi-Fi / rede / espaço",this::connectionTest);
    button(box,"8 · SOC, SOH e sensores do veículo BYD",this::bydTest);
    button(box,"9 · Testar câmera + gravação manual (opcional)",this::openCameraTool);
    button(box,"Ver erro de fechamento anterior",this::showPreviousCrash);
    button(box,"Copiar relatório",this::copyReport);
    button(box,"Salvar relatório em arquivo TXT",this::exportReport);
    output=label("Pronto para testar. Comece pelos itens 1 a 8.\nAs câmeras proprietárias do BYD podem não aparecer no Android.");
    output.setTextIsSelectable(true);
    output.setTextSize(15);
    box.addView(output);
    scroll.addView(box);
    setContentView(scroll);
    systemTest();
    String previous=getSharedPreferences(PREF,MODE_PRIVATE).getString(CRASH,"");
    if(previous!=null&&!previous.isEmpty())append("ALERTA","Há um erro Java registrado da sessão anterior. Use 'Ver erro de fechamento anterior'.");
  }

  private TextView label(String value) {
    TextView t=new TextView(this);
    t.setText(value);t.setTextSize(14);
    t.setTextColor(Color.DKGRAY);t.setPadding(0,10,0,12);
    return t;
  }
  private void button(LinearLayout box,String caption,Runnable click) {
    Button b=new Button(this);
    b.setText(caption);b.setAllCaps(false);
    b.setOnClickListener(v->{try{click.run();}catch(Throwable e){append("ERRO",safeError(e));}});
    box.addView(b);
  }
  private String safeError(Throwable e) {
    return e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage());
  }
  private void append(String section,String detail) {
    String row="\n━━ "+section+" ━━\n"+detail+"\n";
    report.append(row);
    if(output!=null)output.setText(report.toString());
  }
  private void systemTest() {
    append("SISTEMA","Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n"
      +"Fabricante: "+Build.MANUFACTURER+"\nModelo da multimídia: "+Build.MODEL
      +"\nBuild: "+Build.DISPLAY+"\nABI: "+Build.SUPPORTED_ABIS[0]
      +"\nObservação: nenhuma dessas informações confirma acesso ao BMS.");
  }
  private void batteryTest() {
    String value="BatteryManager descreve apenas a bateria/serviço elétrico exposto ao Android; "
      +"NÃO representa necessariamente a bateria de tração do BYD.\n";
    try {
      Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      if(battery==null){append("BATERIA ANDROID",value+"Sem broadcast de bateria.");return;}
      int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
      int percentage=scale>0?Math.round(100f*level/scale):-1;
      append("BATERIA ANDROID",value+"Nível Android: "+(percentage>=0?percentage+"%":"indisponível")
        +"\nTemperatura Android: "+(battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE)==Integer.MIN_VALUE?
          "indisponível":String.format(Locale.US,"%.1f °C",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10f))
        +"\nSOH da bateria de tração: NÃO ACESSÍVEL por esta API.");
    }catch(Throwable e){append("BATERIA ANDROID","API indisponível: "+safeError(e));}
  }
  private void sensorTest() {
    try {
      SensorManager m=(SensorManager)getSystemService(SENSOR_SERVICE);
      if(m==null){append("SENSORES","Serviço indisponível.");return;}
      List<Sensor> all=m.getSensorList(Sensor.TYPE_ALL);
      StringBuilder b=new StringBuilder("Sensores publicados ao Android: "+all.size()+"\n");
      b.append("Acelerômetro: ").append(m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null?"sim":"não").append("\n");
      b.append("Giroscópio: ").append(m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null?"sim":"não").append("\n");
      b.append("Magnetômetro: ").append(m.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!=null?"sim":"não").append("\n");
      b.append("Pressão: ").append(m.getDefaultSensor(Sensor.TYPE_PRESSURE)!=null?"sim":"não").append("\n");
      for(Sensor s:all) b.append("• ").append(s.getName()).append(" (tipo ").append(s.getType()).append(")\n");
      b.append("Sensor disponível não significa velocidade/odômetro do veículo.\n");
      append("SENSORES",b.toString());
    }catch(Throwable e){append("SENSORES","Falha: "+safeError(e));}
  }
  private void cameraTest() {
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);return;
    }
    try {
      CameraManager m=(CameraManager)getSystemService(CAMERA_SERVICE);
      if(m==null){append("CÂMERAS","Serviço Camera2 indisponível.");return;}
      String[] ids=m.getCameraIdList();
      StringBuilder b=new StringBuilder("IDs Android Camera2: "+ids.length+"\n");
      for(String id:ids) {
        try {
          CameraCharacteristics c=m.getCameraCharacteristics(id);
          Integer facing=c.get(CameraCharacteristics.LENS_FACING);
          b.append("• ID ").append(id).append(" · direção ")
            .append(facing==null?"não informada":String.valueOf(facing)).append("\n");
        }catch(Throwable e){b.append("• ID ").append(id).append(" · metadados não disponíveis\n");}
      }
      b.append("A lista Camera2 NÃO comprova acesso às quatro câmeras 360° originais do BYD.");
      append("CÂMERAS",b.toString());
    }catch(Throwable e){append("CÂMERAS","Sem acesso à API Camera2: "+safeError(e));}
  }
  private void locationTest() {
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
        &&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_LOCATION);
      return;
    }
    try{
      LocationManager m=(LocationManager)getSystemService(LOCATION_SERVICE);
      if(m==null){append("GPS","Serviço de localização indisponível.");return;}
      StringBuilder b=new StringBuilder("Provedores: "+m.getAllProviders()+"\n");
      Location best=null;String provider="";
      for(String p:m.getProviders(true)){
        try{Location loc=m.getLastKnownLocation(p);
          if(loc!=null&&(best==null||loc.getTime()>best.getTime())){best=loc;provider=p;}
        }catch(SecurityException ignored){}
      }
      if(best==null)b.append("Sem posição recente em cache. Isso não prova GPS defeituoso.");
      else b.append("Última posição armazenada pelo Android: provedor ").append(provider)
        .append("\nIdade aproximada: ").append(Math.max(0,(System.currentTimeMillis()-best.getTime())/1000)).append(" s")
        .append("\nPrecisão: ").append(best.hasAccuracy()?best.getAccuracy()+" m":"não informada")
        .append("\nCoordenadas NÃO incluídas no relatório por privacidade.");
      append("GPS",b.toString());
    }catch(Throwable e){append("GPS","Falha: "+safeError(e));}
  }
  private void microphoneTest(){
    try{
      AudioManager m=(AudioManager)getSystemService(AUDIO_SERVICE);
      if(m==null){append("MICROFONE","Serviço indisponível.");return;}
      AudioDeviceInfo[] devices=m.getDevices(AudioManager.GET_DEVICES_INPUTS);
      StringBuilder b=new StringBuilder("Entradas Android: "+devices.length+"\n");
      for(AudioDeviceInfo d:devices)b.append("• dispositivo tipo ").append(d.getType())
        .append(" / canais ").append(d.getChannelCounts().length).append("\n");
      b.append("Nenhum som foi capturado. Gravação real exige aviso aos ocupantes, autorização e teste separado.");
      append("MICROFONE",b.toString());
    }catch(Throwable e){append("MICROFONE","Não disponível: "+safeError(e));}
  }
  private void connectionTest(){
    StringBuilder b=new StringBuilder();
    try{
      ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
      if(cm==null)b.append("Serviço de rede indisponível\n");
      else if(Build.VERSION.SDK_INT>=23){
        NetworkCapabilities cap=cm.getNetworkCapabilities(cm.getActiveNetwork());
        b.append("Rede ativa: ").append(cap==null?"não informada":cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
          cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"celular":"outro transporte").append("\n");
        b.append("Validação da internet pelo Android: ").append(cap!=null&&cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?"sim":"não confirmada").append("\n");
      }else{
        NetworkInfo ni=cm.getActiveNetworkInfo();
        b.append("Rede ativa: ").append(ni!=null&&ni.isConnected()?ni.getTypeName():"desconectada").append("\n");
      }
    }catch(Throwable e){b.append("Rede indisponível: ").append(safeError(e)).append("\n");}
    try{
      java.io.File data=getFilesDir();
      b.append("Armazenamento livre para o app: ").append(data.getUsableSpace()/1048576L).append(" MB\n");
    }catch(Throwable e){b.append("Espaço não disponível\n");}
    b.append("Não há conexão nem envio automático de dados ao ANDREAO.");
    append("REDE E ARMAZENAMENTO",b.toString());
  }
  private void bydTest(){
    append("VEÍCULO BYD","SOC tração: NÃO VERIFICADO\nSOH bateria de tração: NÃO VERIFICADO"
      +"\nTemperatura/células BMS: NÃO VERIFICADO\nOdômetro e velocidade CAN: NÃO VERIFICADOS"
      +"\nPressão dos pneus: NÃO VERIFICADA\nCâmeras 360° via sistema BYD: NÃO VERIFICADAS"
      +"\nEste aplicativo não usa root, CAN, comandos de bloqueio, ADB ou API privada."
      +"\nSerá necessário um adaptador compatível e autorizado, validado no veículo.");
  }
  private void openCameraTool(){
    new AlertDialog.Builder(this).setTitle("Teste de vídeo opcional")
      .setMessage("O carro está parado em P, com freio acionado? A gravação usa somente câmeras que o Android expõe. Microfone só com consentimento dos ocupantes.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Carro estacionado",(d,w)->{
        try{startActivity(new Intent(this,MainActivity.class));}
        catch(Throwable e){append("GRAVAÇÃO","Não foi possível abrir o teste: "+safeError(e));}
      }).show();
  }
  private void showPreviousCrash(){
    String saved=getSharedPreferences(PREF,MODE_PRIVATE).getString(CRASH,"");
    append("ERRO REGISTRADO",saved==null||saved.isEmpty()?
      "Nenhum erro Java registrado nesta versão. Falhas nativas exigem logcat/diagnóstico técnico.":saved);
  }
  private void copyReport(){
    try{
      ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("ANDREAO Fleet diagnóstico",report.toString()));
    }catch(Throwable e){append("COPIAR","Falha: "+safeError(e));}
  }
  private void exportReport(){
    try{
      Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType("text/plain");
      intent.putExtra(Intent.EXTRA_TITLE,"andreao_fleet_diagnostico_v03.txt");
      startActivityForResult(intent,SAVE_REPORT);
    }catch(Throwable e){append("EXPORTAÇÃO","Seletor de arquivos indisponível: "+safeError(e));}
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req!=SAVE_REPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri destination=data.getData();
    try(OutputStream outputStream=getContentResolver().openOutputStream(destination)){
      if(outputStream==null)throw new IllegalStateException("Sem destino");
      outputStream.write(report.toString().getBytes(StandardCharsets.UTF_8));
      append("EXPORTAÇÃO","Relatório TXT salvo com sucesso.");
    }catch(Throwable e){append("EXPORTAÇÃO","Não foi possível salvar: "+safeError(e));}
  }
  @Override public void onRequestPermissionsResult(int req,String[] names,int[] grants){
    super.onRequestPermissionsResult(req,names,grants);
    boolean allowed=false;
    for(int value:grants)if(value==PackageManager.PERMISSION_GRANTED)allowed=true;
    if(!allowed){append("PERMISSÃO","Permissão não concedida; nenhum teste executado.");return;}
    if(req==REQUEST_CAMERA)cameraTest();
    if(req==REQUEST_LOCATION)locationTest();
  }
  private void installCrashReporter(){
    final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      try{
        StringWriter w=new StringWriter();
        error.printStackTrace(new PrintWriter(w));
        String content=w.toString();
        if(content.length()>7000)content=content.substring(0,7000);
        getSharedPreferences(PREF,MODE_PRIVATE).edit().putString(CRASH,content).commit();
      }catch(Throwable ignored){}
      if(previous!=null)previous.uncaughtException(thread,error);
      else android.os.Process.killProcess(android.os.Process.myPid());
    });
  }
}