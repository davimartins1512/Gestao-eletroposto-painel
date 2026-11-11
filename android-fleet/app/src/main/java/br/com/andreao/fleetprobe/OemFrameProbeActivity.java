package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import java.util.Arrays;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual, short and isolated OEM camera frame receipt test.
 * NEVER captures microphone or writes/transmits video frames.
 * Only pano_h returned valid ID on tested Dolphin Mini. Never sweep unknown IDs.
 *
 * Executed in :oem_camera_probe process; if a vendor call hangs, a 15-second
 * watchdog terminates ONLY the probe process, not the launcher/dashboard.
 * Can still affect vehicle camera availability during its brief run:
 * user MUST confirm P + parking brake and stop if native surround view is used.
 */
public final class OemFrameProbeActivity extends Activity {
  public static final String EXTRA_REPORT="oem_frame_probe_report";
  private static final int CAMERA_PERMISSION=77;
  private static final int MAX_PREVIEW_MS=3500;
  private static final int MAX_PILOT_MS=15000;
  private static final int WATCHDOG_MS=45000;
  private final Handler main=new Handler(Looper.getMainLooper());
  private volatile long startedElapsedMs=0;
  private final Runnable sessionTicker=new Runnable(){
    @Override public void run(){
      if(!active.get()||completed.get())return;
      long seconds=Math.max(0,(android.os.SystemClock.elapsedRealtime()-startedElapsedMs)/1000L);
      int savedClips=clipSession==null?0:clipSession.completedFiles().size();
      String indicator=(mode==2||mode==4||mode==6)
        ?"🔴 GRAVANDO ÁUDIO E VÍDEO (OCUPANTES INFORMADOS)"
        :"🔴 CAPTURA MANUAL DE VÍDEO";
      show(indicator+"\nTempo decorrido: "+seconds+" s; quadros recebidos: "+
        frames.get()+"; clipes finalizados: "+savedClips+
        "\nCarro estacionado em P; não dirigir nem manobrar durante o ensaio."+
        "\nUse INTERROMPER TESTE para encerrar.");
      main.postDelayed(this,2000);
    }
  };
  private final AtomicBoolean active=new AtomicBoolean(false);
  private final AtomicBoolean abort=new AtomicBoolean(false);
  private final AtomicBoolean completed=new AtomicBoolean(false);
  private final AtomicInteger frames=new AtomicInteger(0);
  private final AtomicInteger validBytes=new AtomicInteger(0);
  private volatile int firstFrameBytes=0;
  private volatile int lastFrameBytes=0;
  private volatile Thread worker;
  private TextView output;
  private Button startButton,videoButton,audioVideoButton,clipVideoButton,clipAudioButton,longVideoButton,longAudioButton,finishButton,stopButton,exportButton,protectButton;
  private volatile int mode=0; // 0=count only, 1=snapshot+MP4, 2=snapshot+MP4+opt-in microphone
  private volatile int sampleWidth=0,sampleHeight=0,sampleSize=0;
  private volatile byte[] sampleImage;
  private volatile OemAvRecorder mediaRecorder;
  private volatile OemClipSession clipSession;
  private volatile String mediaSetupError="";
  private String currentReport="Nenhum teste iniciado.";

  @Override public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    ScrollView scroll=new ScrollView(this);
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(30,26,30,26);
    TextView heading=new TextView(this);
    heading.setText("ANDREAO Fleet · ensaio OEM de frames");
    heading.setTextSize(22);
    box.addView(heading);
    TextView notice=new TextView(this);
    notice.setText("TESTE CONTROLADO · Somente carro parado em P e freio acionado, sem dirigir/manobrar. "
      +"A câmera panorâmica OEM é compartilhada com sistemas BYD e Electro; interrompa ao menor sinal de conflito. "
      +"O modo de foto/MP4 grava até 15 segundos na memória interna ou USB permitida pelo Android. "
      +"ÁUDIO É OPCIONAL E PRECISA SER COMUNICADO A TODOS OS OCUPANTES; QUANDO LIGADO, O APP EXIBE GRAVANDO ÁUDIO. "
      +"Nenhum teste inicia automaticamente. Não há transmissão ou captação em segundo plano.");
    notice.setPadding(0,14,0,20);
    box.addView(notice);
    startButton=button(box,"TESTE 3,5s · APENAS CONTAR FRAMES",()->confirm(0));
    videoButton=button(box,"PILOTO 15s · FOTO + VÍDEO MP4 (SEM ÁUDIO)",()->confirm(1));
    audioVideoButton=button(box,"PILOTO 15s · FOTO + VÍDEO MP4 + ÁUDIO (AVISO AOS OCUPANTES)",()->confirm(2));
    clipVideoButton=button(box,"TESTAR CLIPES SEQUENCIAIS · MP4 SEM ÁUDIO",()->confirm(3));
    clipAudioButton=button(box,"TESTAR CLIPES SEQUENCIAIS · MP4 + ÁUDIO INFORMADO",()->confirm(4));
    longVideoButton=button(box,"ENSAIO PROLONGADO · CLIPES MP4 (DURAÇÃO AJUSTÁVEL)",()->confirm(5));
    longAudioButton=button(box,"ENSAIO PROLONGADO · MP4 + ÁUDIO INFORMADO",()->confirm(6));
    stopButton=button(box,"INTERROMPER TESTE",()->stop());
    stopButton.setEnabled(false);
    finishButton=button(box,"VOLTAR E ADICIONAR RESULTADO AO TXT",()->finishWithResult());
    exportButton=button(box,"EXPORTAR MP4 PARA GALERIA / MOVIES",()->exportVideo());
    exportButton.setEnabled(false);
    protectButton=button(box,"PROTEGER CLIPE DE OCORRÊNCIA (NÃO SOBRESCREVER)",()->protectVideo());
    protectButton.setEnabled(false);
    output=new TextView(this);
    output.setTextIsSelectable(true);
    output.setTextSize(15);
    output.setPadding(0,25,0,20);
    output.setText(currentReport);
    box.addView(output);
    scroll.addView(box);
    setContentView(scroll);
  }
  private Button button(LinearLayout root,String text,Runnable action){
    Button b=new Button(this);
    b.setText(text);b.setAllCaps(false);
    b.setOnClickListener(v->action.run());
    root.addView(b);
    return b;
  }
  private void confirm(int selectedMode){
    if(active.get())return;
    mode=selectedMode;
    String audio=(selectedMode==2||selectedMode==4||selectedMode==6)?"O AUDIO DA CABINE SERA GRAVADO. Confirme que informou TODOS os ocupantes e obteve as autorizações necessárias. A captura será identificada na tela. ":"";
    String video=selectedMode==0?"Só conta frames, sem salvar foto ou áudio. ":
      selectedMode>=3?"Grava clipes MP4 sucessivos sem fechar a câmera entre arquivos. Duração configurada no botão 17; ensaio manual limitado. Pode haver lacunas na troca, principalmente de áudio. ":"Grava um JPEG e MP4 de teste local por até 15 segundos. ";
    new AlertDialog.Builder(this).setTitle("Confirmar gravação de teste na BYD")
      .setMessage(audio+video+"Você confirma carro completamente parado em P, freio acionado, "
        +"sem direção/manobra durante o teste, e interrupção imediata se as câmeras originais ficarem instáveis?")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Confirmo: iniciar", (d,which)->prepare())
      .show();
  }
  private void prepare(){
    boolean cam=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
    boolean mic=(mode!=2&&mode!=4&&mode!=6) || checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    if(!cam||!mic){
      if(mode==2||mode==4||mode==6)requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},CAMERA_PERMISSION);
      else requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
      return;
    }
    startProbe();
  }
  @Override public void onRequestPermissionsResult(int request,String[] perms,int[] results){
    super.onRequestPermissionsResult(request,perms,results);
    if(request!=CAMERA_PERMISSION)return;
    if(results.length==0){show("Permissões não retornaram. Teste cancelado.");return;}
    for(int code:results){if(code!=PackageManager.PERMISSION_GRANTED){
      show("Permissão de câmera ou microfone negada; teste cancelado.");return;
    }}
    startProbe();
  }
  private void startProbe(){
    if(!active.compareAndSet(false,true))return;
    abort.set(false);completed.set(false);frames.set(0);validBytes.set(0);
    startedElapsedMs=android.os.SystemClock.elapsedRealtime();
    firstFrameBytes=0;lastFrameBytes=0;sampleWidth=0;sampleHeight=0;sampleSize=0;
    sampleImage=null;mediaRecorder=null;clipSession=null;mediaSetupError="";
    startButton.setEnabled(false);videoButton.setEnabled(false);audioVideoButton.setEnabled(false);
    clipVideoButton.setEnabled(false);clipAudioButton.setEnabled(false);
    longVideoButton.setEnabled(false);longAudioButton.setEnabled(false);
    stopButton.setEnabled(true);finishButton.setEnabled(false);exportButton.setEnabled(false);protectButton.setEnabled(false);
    long watchdog=(mode>=3?pilotDurationMs()+40000L:WATCHDOG_MS);
    show(((mode==2||mode==4||mode==6)?"🔴 GRAVANDO ÁUDIO E VÍDEO · OCUPANTES INFORMADOS\n":"")+
      "Ensaio manual em processo separado. Watchdog de "+(watchdog/1000L)+" segundos se o HAL travar.");
    main.postDelayed(()->{
      if(active.get() && !completed.get()){
        abort.set(true);
        // A chamada OEM pode bloquear dentro do HAL; interromper Thread não é garantia.
        // Isolar o ensaio permite encerrar o processo do TESTE, sem matar o painel.
        android.os.Process.killProcess(android.os.Process.myPid());
      }
    },watchdog);
    main.postDelayed(sessionTicker,2000);
    worker=new Thread(this::runProbe,"fleet-oem-single-camera-probe");
    worker.start();
  }
  private long pilotDurationMs(){
    if(mode<3)return MAX_PILOT_MS;
    SharedPreferences prefs=getSharedPreferences(StoragePolicy.PREFS,MODE_PRIVATE);
    if(mode==5||mode==6){
      int minutes=(int)prefs.getFloat("test_duration_minutes",5f);
      return Math.max(2,Math.min(30,minutes))*60000L;
    }
    int segment=(int)prefs.getFloat("segment_seconds",90f);
    segment=Math.max(10,Math.min(600,segment));
    return Math.min(135000L,Math.max(45000L,(segment+20L)*1000L));
  }
  private static String rootCause(Throwable e){
    if(e instanceof InvocationTargetException && ((InvocationTargetException)e).getCause()!=null)
      e=((InvocationTargetException)e).getCause();
    String note=e.getMessage();
    return e.getClass().getSimpleName()+(note==null?"":": "+note.substring(0,Math.min(150,note.length())));
  }
  private void runProbe(){
    Object camera=null;
    Method close=null,stopPreview=null;
    boolean cameraOpened=false,previewStarted=false,started=false;
    StringBuilder log=new StringBuilder("TESTE OEM DE FRAMES / ID 0 (pano_h)\n");
    log.append("Solicitado manualmente, somente carro parado P e freio.\n");
    log.append(mode==0?"Somente contagem de frames, sem foto ou áudio.\n":
      "Piloto foto JPEG + MP4, armazenamento local e limites configuráveis.\n");
    log.append("Áudio solicitado: ").append(mode==2||mode==4||mode==6).append("; sem upload/rede.\n");
    try{
    if(mode>0){
      SharedPreferences p=getSharedPreferences(StoragePolicy.PREFS,MODE_PRIVATE);
      StoragePolicy.Candidate target=StoragePolicy.select(this,p);
      if(target==null){
        mediaSetupError="Armazenamento não gravável ou reserva de espaço insuficiente. Ajuste opções no botão 17.";
        log.append(mediaSetupError).append("\n");
        abort.set(true);
      }else{
        log.append("Destino de gravação: ").append(target.folder.getAbsolutePath()).append("\n");
        log.append(OemMediaRetention.clean(this,p,target.folder)).append("\n");
        if(mode>=3){
          clipSession=new OemClipSession(this,p,target.folder,mode==4||mode==6);
          clipSession.start();
          log.append("Duração do clipe: ").append(clipSession.clipSeconds()).append(" segundos; teste até ")
            .append(pilotDurationMs()/1000L).append(" segundos.\n");
        }else{
          mediaRecorder=new OemAvRecorder(this,p,target.folder,mode==2);
          mediaRecorder.start();
        }
      }
    }
      File jar=new File("/system/framework/bmmcamera.jar");
      if(!jar.isFile())throw new IllegalStateException("bmmcamera.jar não está visível");
      ClassLoader loader=new PathClassLoader(jar.getAbsolutePath(),getClassLoader());
      Class<?> info=Class.forName("android.hardware.BmmCameraInfo",false,loader);
      Method byTag=info.getDeclaredMethod("getCameraId",String.class);
      byTag.setAccessible(true);
      if(!Modifier.isStatic(byTag.getModifiers()))throw new IllegalStateException("Método getCameraId não é estático");
      Object id=byTag.invoke(null,"pano_h");
      if(!(id instanceof Number)||((Number)id).intValue()!=0)
        throw new IllegalStateException("ID pano_h alterado ou indisponível; teste cancelado");
      if(abort.get())throw new InterruptedException("Teste cancelado antes da abertura");
      Class<?> avm=Class.forName("android.hardware.AVMCamera",false,loader);
      Class<?> callback=Class.forName("android.hardware.AVMCamera$IPreviewCallback",false,loader);
      Constructor<?> ctor=avm.getDeclaredConstructor(int.class);
      ctor.setAccessible(true);
      camera=ctor.newInstance(0);
      close=avm.getDeclaredMethod("close");
      stopPreview=avm.getDeclaredMethod("stopPreview");
      close.setAccessible(true);stopPreview.setAccessible(true);
      if(abort.get())throw new InterruptedException("Cancelado após criação");
      Method open=avm.getDeclaredMethod("open");
      open.setAccessible(true);
      Object openStatus=open.invoke(camera);
      if(Boolean.FALSE.equals(openStatus))throw new IllegalStateException("AVMCamera.open() retornou false");
      cameraOpened=true;
      log.append("AVMCamera.open(): ").append(openStatus==null?"sem retorno":openStatus).append('\n');
      if(abort.get())throw new InterruptedException("Cancelado após abertura");

      Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),
        new Class<?>[]{callback},(object,method,args)->{
          if("onPreview".equals(method.getName())){
            int count=frames.incrementAndGet();
            if(args!=null && args.length>=6 && args[1] instanceof byte[]){
              byte[] data=(byte[])args[1];
              int bytes=data.length;
              if(bytes>0){
                validBytes.incrementAndGet();lastFrameBytes=bytes;
                if(count==1)firstFrameBytes=bytes;
              }
              if(mode>0 && !abort.get()){
                int width=args[2] instanceof Integer?(Integer)args[2]:0;
                int height=args[3] instanceof Integer?(Integer)args[3]:0;
                int bufferSize=args[5] instanceof Integer?(Integer)args[5]:bytes;
                int expected=OemSnapshotWriter.expectedNv21Bytes(width,height);
                if(expected>0 && bytes>=expected && bufferSize>=expected){
                  if(sampleImage==null){
                    sampleImage=Arrays.copyOf(data,expected);
                    sampleWidth=width;sampleHeight=height;sampleSize=bufferSize;
                  }
                  long nowNs=System.nanoTime();
                  OemAvRecorder r=mediaRecorder;
                  if(r!=null)r.accept(data,width,height,bufferSize,nowNs);
                  OemClipSession session=clipSession;
                  if(session!=null)session.accept(data,width,height,bufferSize,nowNs);
                }
              }
            }
            return null;
          }
          if("toString".equals(method.getName()))return "ANDREAO Fleet test callback";
          if("hashCode".equals(method.getName()))return System.identityHashCode(object);
          if("equals".equals(method.getName()))return object==args[0];
          return null;
        });
      Method setCallback=avm.getDeclaredMethod("setPreviewCallback",callback);
      setCallback.setAccessible(true);
      setCallback.invoke(camera,proxy);
      if(abort.get())throw new InterruptedException("Cancelado antes da prévia");

      // Reference implementation invokes startPreview before enablePreviewCallback.
      Method start=avm.getDeclaredMethod("startPreview");
      start.setAccessible(true);
      started=true;
      Object previewStatus=start.invoke(camera);
      previewStarted=!Boolean.FALSE.equals(previewStatus);
      log.append("AVMCamera.startPreview(): ").append(previewStatus).append('\n');
      if(!previewStarted)throw new IllegalStateException("startPreview retornou false");

      Method enable=avm.getDeclaredMethod("enablePreviewCallback",int.class);
      enable.setAccessible(true);
      Object enabled=enable.invoke(camera,0); // pano_h preview port zero
      log.append("enablePreviewCallback(0): ").append(enabled).append('\n');
      if(Boolean.FALSE.equals(enabled))log.append("AVISO: porta 0 recusada. Não testar outros IDs automaticamente.\n");

      long end=android.os.SystemClock.elapsedRealtime()+(mode==0?MAX_PREVIEW_MS:pilotDurationMs());
      while(!abort.get() && android.os.SystemClock.elapsedRealtime()<end){
        Thread.sleep(100);
      }
    }catch(Throwable t){
      log.append("Falha/encerramento: ").append(rootCause(t)).append('\n');
    }finally{
      log.append("Encerrando câmera OEM (finally)...\n");
      if(camera!=null){
        // Even if startPreview errored, request stop before close.
        if(stopPreview!=null && (started||cameraOpened)){
          try{stopPreview.invoke(camera);log.append("stopPreview: executado\n");}
          catch(Throwable e){log.append("stopPreview: "+rootCause(e)+'\n');}
        }
        if(close!=null){
          try{close.invoke(camera);log.append("close: executado\n");}
          catch(Throwable e){log.append("close: "+rootCause(e)+'\n');}
        }
      }
      if(clipSession!=null){
        clipSession.stopAndWait(16000);
        log.append("\nRESULTADO CLIPES\n").append(clipSession.report()).append("\n");
        if(!clipSession.finished())log.append("AVISO: rotação não finalizou, clipes precisam ser verificados.\n");
      }
      if(mediaRecorder!=null){
        mediaRecorder.stop();
        try{mediaRecorder.join(6500);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
        log.append("\nRESULTADO ENCODIFICADOR\n").append(mediaRecorder.report()).append("\n");
        if(!mediaRecorder.finished())log.append("AVISO: finalização pendente; arquivo não verificado.\n");
      }
      if(mode>0){
        if(sampleImage!=null){
          OemSnapshotWriter.Result jpg=OemSnapshotWriter.save(this,sampleImage,sampleWidth,sampleHeight,sampleSize);
          log.append("\nJPEG: ").append(jpg.message).append("\n");
        }else log.append("\nJPEG: nenhum buffer compatível para salvar.\n");
        if(!mediaSetupError.isEmpty())log.append("Falha de armazenamento: ").append(mediaSetupError).append("\n");
      }
      log.append("Callbacks recebidos: ").append(frames.get()).append('\n');
      log.append("Callbacks com bytes: ").append(validBytes.get()).append('\n');
      log.append("Primeiro buffer (bytes): ").append(firstFrameBytes).append('\n');
      log.append("Último buffer (bytes): ").append(lastFrameBytes).append('\n');
      log.append("Interrupção manual: ").append(abort.get()).append('\n');
      log.append(mode==0?"Nenhum quadro de imagem foi salvo ou transmitido.\n":
        "Foto/MP4 de ensaio local: verificar arquivos e conteúdo; nenhum dado transmitido.\n");
      log.append("Receber frames não comprova gravação contínua nem coexistência segura com câmera de ré.");
      final String result=log.toString();
      final String persisted=OemSessionJournal.write(getApplicationContext(),result);
      final String withJournal=result+"\n\nHISTÓRICO LOCAL: "+persisted;
      completed.set(true);
      active.set(false);
      main.removeCallbacks(sessionTicker);
      runOnUiThread(()->{
        currentReport=withJournal;
        show(withJournal);
        startButton.setEnabled(true);videoButton.setEnabled(true);audioVideoButton.setEnabled(true);
        clipVideoButton.setEnabled(true);clipAudioButton.setEnabled(true);
        longVideoButton.setEnabled(true);longAudioButton.setEnabled(true);
        stopButton.setEnabled(false);
        finishButton.setEnabled(true);
        exportButton.setEnabled((mediaRecorder!=null&&mediaRecorder.finishedFile()!=null)
          ||(clipSession!=null&&!clipSession.completedFiles().isEmpty()));
        protectButton.setEnabled(clipSession!=null&&!clipSession.completedFiles().isEmpty());
      });
    }
  }
  private void stop(){
    abort.set(true);
    main.removeCallbacks(sessionTicker);
    if(mediaRecorder!=null)mediaRecorder.stop();
    if(clipSession!=null)clipSession.stop();
    Thread t=worker;
    if(t!=null)t.interrupt();
    show("Interrupção solicitada. Esperando fechar a câmera; watchdog ativo.");
  }
  private void show(String text){
    if(output!=null)output.setText(text);
  }
  private void protectVideo(){
    OemClipSession session=clipSession;
    if(session==null){show("Use o modo de clipes sequenciais para preservar trechos.");return;}
    java.util.List<File> files=session.completedFiles();
    if(files.isEmpty()){show("Nenhum clipe finalizado foi encontrado.");return;}
    String[] labels=new String[files.size()];
    for(int i=0;i<files.size();i++)labels[i]=files.get(i).getName();
    new AlertDialog.Builder(this).setTitle("Preservar clipe para ocorrência")
      .setMessage("Esta ação exclui o clipe escolhido da reciclagem automática. Proteja apenas gravações necessárias e mantenha controle de acesso.")
      .setSingleChoiceItems(labels,-1,(d,which)->{
        String status=session.protectClip(which);
        currentReport+="\nPROTEÇÃO DE CLIPE\n"+status;
        show(currentReport);
        d.dismiss();
      })
      .setNegativeButton("Cancelar",null).show();
  }
  private void exportVideo(){
    if(clipSession!=null){
      java.util.List<File> files=clipSession.completedFiles();
      if(files.isEmpty()){show("Nenhum clipe íntegro foi finalizado.");return;}
      String[] choices=new String[files.size()];
      for(int i=0;i<files.size();i++)choices[i]=files.get(i).getName()+
        " · "+(files.get(i).length()/1048576L)+" MB";
      new AlertDialog.Builder(this).setTitle("Exportar somente o clipe escolhido")
        .setItems(choices,(dialog,selected)->exportOne(files.get(selected)))
        .setNegativeButton("Cancelar",null).show();
      return;
    }
    OemAvRecorder r=mediaRecorder;
    File saved=r==null?null:r.finishedFile();
    if(saved==null){show("Nenhum MP4 íntegro concluído para exportação.");return;}
    exportOne(saved);
  }
  private void exportOne(File selected){
    exportButton.setEnabled(false);
    new Thread(()->{
      final String message=OemVideoExporter.copyToMovies(getApplicationContext(),selected);
      runOnUiThread(()->{
        show(currentReport+"\n\nEXPORTAÇÃO MP4\n"+message);
        exportButton.setEnabled(true);
      });
    },"fleet-export-mp4").start();
  }
  private void finishWithResult(){
    if(active.get()){stop();return;}
    Intent data=new Intent();
    data.putExtra(EXTRA_REPORT,currentReport);
    setResult(RESULT_OK,data);
    finish();
  }
  @Override protected void onDestroy(){
    abort.set(true);
    if(mediaRecorder!=null)mediaRecorder.stop();
    if(clipSession!=null)clipSession.stop();
    Thread t=worker;
    if(t!=null)t.interrupt();
    super.onDestroy();
  }
}
