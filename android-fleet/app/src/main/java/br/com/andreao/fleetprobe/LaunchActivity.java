package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
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
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
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
  private static final int REQUEST_CAMERA=110, REQUEST_LOCATION=111, SAVE_REPORT=112, REQUEST_ALL=113, REQUEST_TRACK=116, OEM_FRAMES=117;
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
    title.setText("ANDREAO FLEET · GRAVAÇÃO LOCAL + GALERIA v1.5");
    title.setTextColor(0xff137642);
    title.setTextSize(22);
    box.addView(title);
    TextView note=label("TESTE COMPLETO · somente leitura · offline.\nCarro em P, com freio de estacionamento acionado. Nenhum teste inicia câmeras ou microfone automaticamente.");
    box.addView(note);
    button(box,"▶ EXECUTAR TODOS OS TESTES E SALVAR LOG",this::runAllChecks);
    box.addView(label("Executa 1 a 8 em sequência e salva um único TXT automaticamente na pasta Download. Permissões de câmera/GPS são solicitadas uma vez, se necessário. Gravação e microfone permanecem desativados."));
    button(box,"1 · Sistema e multimídia",this::systemTest);
    button(box,"2 · Bateria Android (não é bateria de tração)",this::batteryTest);
    button(box,"3 · Sensores / movimento (lista, não condução)",this::sensorTest);
    button(box,"4 · Câmeras disponíveis no Android",this::cameraWithPermission);
    button(box,"5 · GPS (com autorização)",this::locationWithPermission);
    button(box,"6 · Microfones disponíveis (sem gravar)",this::microphoneTest);
    button(box,"7 · Wi-Fi / rede / espaço",this::connectionTest);
    button(box,"8 · SOC, SOH e sensores do veículo BYD",this::bydTest);
    box.addView(label("NOVO v0.7: consulta opcional a um provedor de dados BYD conhecido e contadores de rede. Não há escrita no veículo nem leitura garantida de SOC/SOH."));
    button(box,"15 · BYD CarStatus (consulta somente leitura)",this::readBydProvider);
    button(box,"16 · Uso de dados móveis (contadores Android)",this::readNetworkUsage);
    button(box,"17 · CONFIGURAR ARMAZENAMENTO / USB / LIMITES",this::openStoragePreferences);
    box.addView(label("FOCO CÂMERAS E ÁUDIO v0.9 · consulta local das interfaces BYD originais. Não abre câmeras nem interfere no Electro."));
    button(box,"18 · DIAGNOSTICAR CÂMERAS + bmmcamera.jar",this::probeOemCameraCompatibility);
    button(box,"19 · IDENTIFICAR CÂMERAS BYD (IDs OEM)",this::probeCameraIds);
    box.addView(label("NOVO v1.4: teste curto de foto/MP4 ou teste manual de clipes sequenciais, com duração configurável (padrão 90s) e sem reabrir a câmera entre arquivos. Áudio só com aviso explícito; nada inicia sozinho."));
    button(box,"20 · TESTE INTEGRADO DE FOTO + MP4 (ÁUDIO OPCIONAL)",this::openOemFrameProbe);
    button(box,"21 · GALERIA LOCAL · VER, EXPORTAR E PROTEGER CLIPES",this::openRecordingLibrary);

    box.addView(label("NOVOS TESTES v0.6 · toque para começar e interromper. Não há rastreamento automático ou envio à internet."));
    button(box,"10 · INICIAR GPS + MOVIMENTO (até 3 minutos)",this::confirmStartTracking);
    button(box,"11 · PARAR TESTE GPS AGORA",this::stopTracking);
    button(box,"12 · STATUS DO GPS E CONTAGEM DE PONTOS",this::showTrackStatus);
    button(box,"13 · TESTE DO MICROFONE (com autorização, 5 segundos)",this::openAudioTest);
    button(box,"14 · EXPORTAR LOG ÚNICO (diagnóstico + GPS + áudio)",this::exportCombined);
    button(box,"9 · Testar câmera + gravação manual (opcional)",this::openCameraTool);
    button(box,"Ver erro de fechamento anterior",this::showPreviousCrash);
    button(box,"Copiar relatório",this::copyReport);
    button(box,"Exibir resumo curto",this::showCompactSummary);
    button(box,"Salvar relatório em arquivo TXT",this::exportReport);
    output=label("Toque em EXECUTAR TODOS OS TESTES E SALVAR LOG.\nAs câmeras originais da BYD podem não aparecer no Android.");
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
  private void runAllChecks(){
    // Solicita permissões Android uma vez, mas continua gerando o relatório
    // mesmo se o usuário negar câmera e/ou GPS.
    java.util.ArrayList<String> missing=new java.util.ArrayList<>();
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
      missing.add(Manifest.permission.CAMERA);
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
        &&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
      missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
    }
    if(!missing.isEmpty()){
      try{
        requestPermissions(missing.toArray(new String[0]),REQUEST_ALL);
        return;
      }catch(Throwable e){append("PERMISSÕES","Não foi possível solicitar: "+safeError(e));}
    }
    performAllChecks();
  }
  private void performAllChecks(){
    report.setLength(0);
    if(output!=null)output.setText("");
    append("INÍCIO DO DIAGNÓSTICO","ANDREAO Fleet v1.5 · executado em "
      +new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new java.util.Date())
      +"\nSomente APIs públicas do Android; sem controle do veículo nem leitura CAN/OBD.");
    safeRun("Sistema",this::systemTest);
    safeRun("Bateria Android",this::batteryTest);
    safeRun("Sensores",this::sensorTest);
    safeRun("Câmeras",this::cameraTest);
    safeRun("Localização",this::locationTest);
    safeRun("Microfone",this::microphoneTest);
    safeRun("Rede",this::connectionTest);
    safeRun("Dados proprietários BYD",this::bydTest);
    append("RESULTADO","Diagnóstico automático concluído. O teste 9 (gravação real de vídeo/áudio) não é executado automaticamente: exige confirmação do carro parado e autorização dos ocupantes para áudio.");
    exportReport();
  }
  private void safeRun(String name,Runnable test){
    try{test.run();}catch(Throwable e){append("ERRO EM "+name,safeError(e));}
  }
  private void cameraWithPermission(){
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);return;
    }
    cameraTest();
  }
  private void locationWithPermission(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
        &&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_LOCATION);
      return;
    }
    locationTest();
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
      append("CÂMERAS","Não foi possível obter permissão Android para consultar Camera2.");return;
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
      append("GPS","Permissão de localização não concedida: posição não consultada.");return;
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
  private void openRecordingLibrary(){
    startActivity(new Intent(this,OemRecordingLibraryActivity.class));
  }
  private void openOemFrameProbe(){
    startActivityForResult(new Intent(this,OemFrameProbeActivity.class),OEM_FRAMES);
  }
  private void probeCameraIds(){
    append("CAMERAS OEM · IDs","Consultando IDs por nome, sem abrir nenhuma câmera.");
    new Thread(()->{
      final String result=BydCameraIdProbe.inspect(getApplicationContext());
      runOnUiThread(()->append("CAMERAS OEM · IDS IDENTIFICADOS",result));
    },"fleet-byd-camera-ids").start();
  }
  private void probeOemCameraCompatibility(){
    append("CÂMERAS OEM BYD", "Consultando apenas a disponibilidade das interfaces da plataforma. Nenhuma câmera será aberta.");
    new Thread(()->{
      final String result=BydCameraCompatibility.summarize(getApplicationContext());
      runOnUiThread(()->append("COMPATIBILIDADE CÂMERAS BYD",result));
    },"fleet-oem-camera-probe").start();
  }
  private void openStoragePreferences(){
    startActivity(new Intent(this,StoragePreferencesActivity.class));
  }
  private void readBydProvider(){
    append("BYD CARSTATUS","Consultando apenas campos públicos conhecidos do provedor local... Pode estar bloqueado no seu firmware.");
    new Thread(()->{
      String text=BydReadOnlyProbe.snapshot(getApplicationContext());
      runOnUiThread(()->append("BYD CARSTATUS · RESULTADO",text));
    },"fleet-byd-readonly").start();
  }
  private void readNetworkUsage(){
    append("DADOS MÓVEIS",NetworkDataProbe.snapshot());
  }
  private void confirmStartTracking(){
    new AlertDialog.Builder(this)
      .setTitle("Teste de localização por 3 minutos")
      .setMessage("Confirme que o carro está estacionado em P, com freio acionado. O teste registra COORDENADAS GPS precisas e sensores de movimento em um CSV local. Não compartilhe o arquivo se contiver endereços particulares. O serviço mostra notificação, é encerrado em até 3 minutos e pode ser parado a qualquer momento. Não haverá envio para servidor. Você autoriza iniciar?")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Iniciar teste",(d,w)->startTracking())
      .show();
  }
  private void startTracking(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
        &&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_TRACK);
      return;
    }
    try{
      if(getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE).getBoolean(TrackerService.RUNNING,false)){
        append("GPS CONTÍNUO","Uma coleta já está ativa. Use PARAR TESTE GPS AGORA.");return;
      }
      Intent intent=new Intent(this,TrackerService.class).setAction(TrackerService.ACTION_START);
      startForegroundService(intent);
      append("GPS CONTÍNUO","Coleta iniciada sob sua autorização: no máximo 3 minutos. Notificação de acompanhamento ativa. Use PARAR para encerrar antes.");
    }catch(Throwable e){append("GPS CONTÍNUO","Falha ao iniciar: "+safeError(e));}
  }
  private void stopTracking(){
    try{
      if(!getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE).getBoolean(TrackerService.RUNNING,false)){
        append("GPS CONTÍNUO","Não há coleta ativa registrada.");
        return;
      }
      startService(new Intent(this,TrackerService.class).setAction(TrackerService.ACTION_STOP));
      append("GPS CONTÍNUO","Solicitada parada. Consulte o status e exporte depois que encerrar.");
    }catch(Throwable e){append("GPS CONTÍNUO","Falha ao parar: "+safeError(e));}
  }
  private void showTrackStatus(){
    android.content.SharedPreferences p=getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE);
    String info="Ativo: "+(p.getBoolean(TrackerService.RUNNING,false)?"SIM":"NÃO")
      +"\nPontos GPS recebidos: "+p.getInt(TrackerService.LAST_COUNT,0)
      +"\nEstado: "+p.getString(TrackerService.LAST_STATUS,"Nunca testado")
      +"\nArquivo interno: "+p.getString(TrackerService.LAST_FILE,"Nenhum");
    append("GPS CONTÍNUO",info);
    new AlertDialog.Builder(this).setTitle("Status do rastreamento").setMessage(info)
      .setPositiveButton("OK",null).show();
  }
  private void openAudioTest(){
    new AlertDialog.Builder(this).setTitle("Teste opcional de microfone")
      .setMessage("O teste dura 5 segundos. Requer consentimento de TODOS os ocupantes e carro estacionado. O áudio temporário é apagado automaticamente. Continuar?")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Abrir teste",(d,w)->startActivity(new Intent(this,AudioTestActivity.class)))
      .show();
  }
  private void exportCombined(){
    android.content.SharedPreferences p=getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE);
    if(p.getBoolean(TrackerService.RUNNING,false)){
      new AlertDialog.Builder(this).setTitle("Encerre a coleta primeiro")
        .setMessage("O serviço de GPS ainda está registrando. Aperte PARAR TESTE GPS AGORA antes de exportar.")
        .setPositiveButton("OK",null).show();
      return;
    }
    StringBuilder all=new StringBuilder(report.toString());
    all.append("\n\n━━ TESTE GPS CONTÍNUO v0.6 ━━\n");
    all.append("Estado: ").append(p.getString(TrackerService.LAST_STATUS,"Nunca executado")).append("\n");
    all.append("Pontos GPS registrados: ").append(p.getInt(TrackerService.LAST_COUNT,0)).append("\n");
    String path=p.getString(TrackerService.LAST_FILE,"");
    if(path.isEmpty())all.append("Nenhum CSV de rastreamento registrado.\n");
    else {
      File file=new File(path);
      if(!file.exists())all.append("Arquivo anterior não encontrado na pasta privada do aplicativo.\n");
      else if(file.length()>1024*1024)all.append("CSV maior que 1 MB: arquivo não incluído por segurança.\n");
      else try(InputStream input=new FileInputStream(file);
          ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
        byte[] data=new byte[8192];
        int n;while((n=input.read(data))!=-1)buffer.write(data,0,n);
        all.append("\n━━ CSV GPS E MOVIMENTO (COORDENADAS PRECISAS) ━━\n");
        all.append(buffer.toString("UTF-8")).append("\n");
      }catch(Throwable e){all.append("Erro lendo CSV: ").append(safeError(e)).append("\n");}
    }
    all.append("\n━━ RESULTADO DO TESTE REAL DE MICROFONE ━━\n");
    all.append(getSharedPreferences("andreao_audio_test",MODE_PRIVATE)
      .getString("last_result","Teste real ainda não executado.")).append("\n");
    all.append("\nPRIVACIDADE: ESTE RELATÓRIO PODE CONTER COORDENADAS EXATAS. REVISE ANTES DE ENVIAR.\n");
    saveTextFile("andreao_fleet_completo_v06_",all.toString());
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
  private void showCompactSummary(){
    String[] sections={"SISTEMA","BATERIA ANDROID","SENSORES","CÂMERAS","GPS","MICROFONE","REDE E ARMAZENAMENTO","VEÍCULO BYD"};
    StringBuilder b=new StringBuilder("Resumo dos testes executados:\n\n");
    String text=report.toString();
    for(String section:sections){
      String marker="━━ "+section+" ━━";
      int at=text.lastIndexOf(marker);
      if(at<0){b.append(section).append(": não executado\n");continue;}
      int start=text.indexOf('\n',at+marker.length());
      if(start<0){b.append(section).append(": executado\n");continue;}
      int next=text.indexOf('\n',start+1);
      if(next<0)next=text.length();
      String line=text.substring(start+1,next).trim();
      b.append(section).append(": ").append(line.length()>110?line.substring(0,110)+"…":line).append("\n");
    }
    new AlertDialog.Builder(this).setTitle("Resumo ANDREAO Fleet v0.4")
      .setMessage(b.toString()).setPositiveButton("Fechar",null).show();
  }
  private void exportReport(){saveTextFile("andreao_fleet_diagnostico_v06_",report.toString());}
  private void saveTextFile(String prefix,String content){
    String filename=prefix
      +new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date())+".txt";
    byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
    String destination="";
    String firstError=null;
    if(Build.VERSION.SDK_INT>=29){
      Uri uri=null;
      try{
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new java.io.IOException("Downloads não disponível");
        try(OutputStream stream=getContentResolver().openOutputStream(uri)){
          if(stream==null)throw new java.io.IOException("Saída indisponível");
          stream.write(bytes);
        }
        ContentValues ready=new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING,0);
        getContentResolver().update(uri,ready,null,null);
        destination="Armazenamento interno / Download / "+filename;
      }catch(Throwable e){
        firstError=safeError(e);
        if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Throwable ignored){}
      }
    }
    if(destination.isEmpty()){
      try{
        java.io.File dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if(dir==null)dir=new java.io.File(getFilesDir(),"reports");
        if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Pasta indisponível");
        java.io.File file=new java.io.File(dir,filename);
        try(OutputStream stream=new java.io.FileOutputStream(file)){stream.write(bytes);}
        destination=file.getAbsolutePath();
      }catch(Throwable e){
        String errors="Não foi possível salvar.\n"
          +"Downloads: "+(firstError==null?"não compatível com o Android atual":firstError)
          +"\nPasta alternativa: "+safeError(e)+"\nUse Copiar relatório ou o Resumo curto.";
        append("EXPORTAÇÃO",errors);
        new AlertDialog.Builder(this).setTitle("Falha ao salvar")
          .setMessage(errors).setPositiveButton("OK",null).show();
        return;
      }
    }
    String result="Relatório salvo com sucesso!\n\nLocal:\n"+destination
      +"\n\nAbra o Gerenciador de Arquivos e procure em Download/Downloads."
      +"\nSe foi usada a pasta alternativa, procure por Android/data/br.com.andreao.fleetprobe/files/Documents.";
    append("EXPORTAÇÃO","Arquivo salvo: "+destination);
    new AlertDialog.Builder(this).setTitle("Relatório TXT salvo")
      .setMessage(result).setPositiveButton("OK",null).show();
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req==OEM_FRAMES){
      if(result==RESULT_OK&&data!=null){
        String test=data.getStringExtra(OemFrameProbeActivity.EXTRA_REPORT);
        append("ENSAIO OEM REAL · FRAMES",test==null?"Sem resultado retornado":test);
      }else append("ENSAIO OEM REAL · FRAMES","Teste cancelado ou processo OEM encerrado por segurança (nenhum resultado de vídeo recuperado).");
      return;
    }
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
    if(req==REQUEST_ALL){performAllChecks();return;}
    if(req==REQUEST_TRACK){if(allowed)startTracking();else append("GPS CONTÍNUO","Permissão recusada. Coleta não iniciada.");return;}
    if(!allowed){append("PERMISSÃO","Permissão não concedida; teste individual não executado.");return;}
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