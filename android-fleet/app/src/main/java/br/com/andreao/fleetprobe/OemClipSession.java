package br.com.andreao.fleetprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental contiguous CAMERA session split into independent MP4 files.
 * Keeps the BYD AVMCamera owned by OemFrameProbeActivity OPEN between segments.
 *
 * Warning: the codec/muxer must be restarted at segment boundaries. A short
 * video or audio discontinuity remains possible until verified on the car.
 * This pilot logs gaps; it does NOT claim evidence-grade continuous capture.
 */
public final class OemClipSession {
  private final Context context;
  private final SharedPreferences prefs;
  private final File directory;
  private final boolean audio;
  private final int clipSeconds;
  private final AtomicBoolean running=new AtomicBoolean(false);
  private final AtomicInteger rotations=new AtomicInteger(0);
  private final AtomicInteger callbackGaps=new AtomicInteger(0);
  private final AtomicLong lastCallbackNs=new AtomicLong(0);
  private final List<OemAvRecorder> closed=new ArrayList<>();
  private final List<File> completed=new ArrayList<>();
  private volatile OemAvRecorder current;
  private volatile String failure="";
  private volatile boolean finished=false;
  private volatile int audioSwitches=0;
  private volatile int lostWhileSwitch=0;
  private Thread coordinator;

  public OemClipSession(Context context,SharedPreferences prefs,File directory,boolean audio) {
    this.context=context.getApplicationContext();
    this.prefs=prefs;
    this.directory=directory;
    this.audio=audio;
    int selected=(int)prefs.getFloat("segment_seconds",90f);
    this.clipSeconds=Math.max(10,Math.min(600,selected));
  }
  public int clipSeconds(){return clipSeconds;}
  public long suggestedPilotMs(){
    // One rotation with default 90-second segments; capped for parked pilot.
    return Math.min(135000L,Math.max(45000L,(clipSeconds+20L)*1000L));
  }
  public synchronized void start() {
    if(!running.compareAndSet(false,true))throw new IllegalStateException("Sessão já iniciada");
    if(!OemMediaRetention.enoughSpace(directory,prefs)){
      running.set(false);
      throw new IllegalStateException("Pouco espaço livre para clipes de teste");
    }
    current=createRecorder();
    current.start();
    coordinator=new Thread(this::loop,"fleet-clip-rotator");
    coordinator.start();
  }
  private OemAvRecorder createRecorder(){
    return new OemAvRecorder(context,prefs,directory,audio);
  }
  public void accept(byte[] buffer,int width,int height,int size,long nanos){
    if(!running.get())return;
    long prev=lastCallbackNs.getAndSet(nanos);
    if(prev>0&&nanos>prev && nanos-prev>500000000L)callbackGaps.incrementAndGet();
    OemAvRecorder r=current;
    if(r!=null)r.accept(buffer,width,height,size,nanos);
    else lostWhileSwitch++;
  }
  private void loop(){
    long next=SystemClock.elapsedRealtime()+clipSeconds*1000L;
    try{
      while(running.get()){
        long remaining=next-SystemClock.elapsedRealtime();
        if(remaining>0){
          Thread.sleep(Math.min(200,remaining));
          continue;
        }
        if(!running.get())break;
        rotate();
        next=SystemClock.elapsedRealtime()+clipSeconds*1000L;
      }
    }catch(Throwable e){
      failure="Coordenador: "+e.getClass().getSimpleName();
    }finally{
      running.set(false);
      OemAvRecorder last=current;
      current=null;
      if(last!=null){
        last.stop();
        finishRecorder(last);
      }
      finished=true;
    }
  }
  private void rotate(){
    OemAvRecorder previous=current;
    if(previous==null)return;
    if(audio){
      // AudioRecord on Android 10 may be exclusive; never open two microphones
      // simultaneously. There CAN be an audio gap, recorded in diagnostics.
      current=null;
      previous.stop();
      finishRecorder(previous);
      audioSwitches++;
      if(!previous.finished()){
        failure="Microfone/encoder anterior não finalizou; nova sessão recusada para evitar conflito";
        running.set(false);return;
      }
      if(!running.get())return;
      OemAvRecorder next=createRecorder();
      next.start();
      current=next;
    }else{
      // Video-only: new codec is prepared in its own worker while the old
      // worker is flushing. Camera object itself is never reopened here.
      OemAvRecorder next=createRecorder();
      next.start();
      current=next;
      previous.stop();
      finishRecorder(previous);
    }
    rotations.incrementAndGet();
  }
  private void finishRecorder(OemAvRecorder r){
    try{r.join(8000);}
    catch(InterruptedException e){Thread.currentThread().interrupt();}
    synchronized(closed){
      closed.add(r);
      File saved=r.finishedFile();
      if(saved!=null)completed.add(saved);
      else if(failure.isEmpty())failure="Um clipe não concluiu: "+r.report();
    }
    if(!r.finished()){
      failure="Timeout fechando clipe anterior; não comprova continuidade";
      running.set(false);
    }
  }
  public void stop(){
    running.set(false);
  }
  public void stopAndWait(long waitMs){
    stop();
    Thread c=coordinator;
    if(c!=null){
      try{c.join(Math.max(1000,waitMs));}
      catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
  }
  public boolean finished(){return finished;}
  public List<File> completedFiles(){
    synchronized(closed){return new ArrayList<>(completed);}
  }
  public String report(){
    StringBuilder result=new StringBuilder("Clipes contínuos (piloto BYD)\n");
    result.append("Duração configurada por clipe: ").append(clipSeconds).append(" s\n");
    result.append("Rotação de arquivo, sem fechar câmera: ").append(rotations.get()).append(" vezes\n");
    result.append("Intervalos entre callbacks >500ms: ").append(callbackGaps.get()).append('\n');
    result.append("Callbacks sem gravador durante troca: ").append(lostWhileSwitch).append('\n');
    if(audio){
      result.append("Trocas com reinicialização de áudio: ").append(audioSwitches).append('\n');
      result.append("ATENÇÃO: lacuna de áudio na troca ainda não eliminada.\n");
    }
    synchronized(closed){
      result.append("MP4 finalizados: ").append(completed.size()).append('\n');
      for(int i=0;i<closed.size();i++){
        result.append("Clipes ").append(i+1).append(": ")
          .append(closed.get(i).report()).append('\n');
      }
    }
    if(!failure.isEmpty())result.append("FALHA: ").append(failure).append('\n');
    result.append("Não classificar como gravação contínua confiável até avaliar cada MP4 e os limites de rotação no carro real.");
    return result.toString();
  }
}