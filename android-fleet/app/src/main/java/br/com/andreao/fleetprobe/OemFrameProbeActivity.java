package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
  private static final int WATCHDOG_MS=15000;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final AtomicBoolean active=new AtomicBoolean(false);
  private final AtomicBoolean abort=new AtomicBoolean(false);
  private final AtomicBoolean completed=new AtomicBoolean(false);
  private final AtomicInteger frames=new AtomicInteger(0);
  private final AtomicInteger validBytes=new AtomicInteger(0);
  private volatile int firstFrameBytes=0;
  private volatile int lastFrameBytes=0;
  private volatile Thread worker;
  private TextView output;
  private Button startButton,finishButton,stopButton;
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
    notice.setText("EXPERIMENTAL. Somente com carro ESTACIONADO em P e freio acionado, sem manobras. "
      +"O teste acessa a câmera panorâmica de ID 0 por até 3,5 segundos; pode disputar acesso com "
      +"a câmera 360° e o Electro. NÃO dirigir ou engatar marcha durante o teste. "
      +"Não registra imagem nem áudio; apenas conta quadros recebidos. "
      +"Se uma câmera original deixar de funcionar corretamente, interrompa o teste imediatamente "
      +"e não dependa deste APK para manobrar. Resultado ainda não aprovado para uso operacional.");
    notice.setPadding(0,14,0,20);
    box.addView(notice);
    startButton=button(box,"INICIAR ENSAIO DE FRAMES · P / FREIO CONFIRMADOS",()->confirm());
    stopButton=button(box,"INTERROMPER TESTE",()->stop());
    stopButton.setEnabled(false);
    finishButton=button(box,"VOLTAR E ADICIONAR RESULTADO AO TXT",()->finishWithResult());
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
  private void confirm(){
    if(active.get())return;
    new AlertDialog.Builder(this).setTitle("Confirmar teste controlado na BYD")
      .setMessage("Você confirma que o Dolphin Mini está parado em P, com freio de estacionamento, "
       +"que não vai dirigir nem manobrar e que pode interromper imediatamente caso note qualquer "
       +"interferência nas câmeras originais? Este teste abre temporariamente a câmera de ID 0; "
       +"não grava imagens nem áudio.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Confirmo: iniciar teste", (d,which)->prepare())
      .show();
  }
  private void prepare(){
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
      return;
    }
    startProbe();
  }
  @Override public void onRequestPermissionsResult(int request,String[] perms,int[] results){
    super.onRequestPermissionsResult(request,perms,results);
    if(request!=CAMERA_PERMISSION)return;
    if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED){
      show("Permissão CAMERA negada. Nada foi aberto.");
      return;
    }
    startProbe();
  }
  private void startProbe(){
    if(!active.compareAndSet(false,true))return;
    abort.set(false);completed.set(false);frames.set(0);validBytes.set(0);
    firstFrameBytes=0;lastFrameBytes=0;
    startButton.setEnabled(false);
    stopButton.setEnabled(true);
    finishButton.setEnabled(false);
    show("Iniciando teste manual em processo separado. O watchdog encerra este processo após 15 s se houver travamento.");
    main.postDelayed(()->{
      if(active.get() && !completed.get()){
        abort.set(true);
        // A chamada OEM pode bloquear dentro do HAL; interromper Thread não é garantia.
        // Isolar o ensaio permite encerrar o processo do TESTE, sem matar o painel.
        android.os.Process.killProcess(android.os.Process.myPid());
      }
    },WATCHDOG_MS);
    worker=new Thread(this::runProbe,"fleet-oem-single-camera-probe");
    worker.start();
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
    log.append("Sem captura de som, sem exportar imagem, sem rede, sem armazenamento de vídeo.\n");
    try{
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
            if(args!=null && args.length>=2 && args[1] instanceof byte[]){
              int bytes=((byte[])args[1]).length;
              if(bytes>0){
                validBytes.incrementAndGet();
                lastFrameBytes=bytes;
                if(count==1)firstFrameBytes=bytes;
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

      long end=android.os.SystemClock.elapsedRealtime()+MAX_PREVIEW_MS;
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
      log.append("Callbacks recebidos: ").append(frames.get()).append('\n');
      log.append("Callbacks com bytes: ").append(validBytes.get()).append('\n');
      log.append("Primeiro buffer (bytes): ").append(firstFrameBytes).append('\n');
      log.append("Último buffer (bytes): ").append(lastFrameBytes).append('\n');
      log.append("Interrupção manual: ").append(abort.get()).append('\n');
      log.append("Nenhum quadro de imagem foi salvo ou transmitido.\n");
      log.append("Receber frames não comprova gravação contínua nem coexistência segura com câmera de ré.");
      final String result=log.toString();
      completed.set(true);
      active.set(false);
      runOnUiThread(()->{
        currentReport=result;
        show(result);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        finishButton.setEnabled(true);
      });
    }
  }
  private void stop(){
    abort.set(true);
    Thread t=worker;
    if(t!=null)t.interrupt();
    show("Interrupção solicitada. Esperando fechar a câmera; watchdog ativo.");
  }
  private void show(String text){
    if(output!=null)output.setText(text);
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
    Thread t=worker;
    if(t!=null)t.interrupt();
    super.onDestroy();
  }
}
