package br.com.andreao.fleetprobe;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pilot local MP4 encoder. Consumes AVMCamera's NV21 preview callback, converts
 * to a smaller YUV420SP (NV12) surface via bounded CPU queue, encodes H.264,
 * and optionally encodes microphone as AAC in the SAME MP4.
 *
 * NO background/hidden microphone. Audio is opt-in, with on-screen REC indicator
 * and advance disclosure to all occupants. Does not access network.
 *
 * The OEM pixel-format assumption must be verified by reviewing a real snapshot.
 * Unsupported encoder formats produce a clear failure, never a fake success.
 */
public final class OemAvRecorder implements AutoCloseable {
  private static final String V_MIME="video/avc", A_MIME="audio/mp4a-latm";
  private static final int AUDIO_RATE=16000;
  private static final int AUDIO_CHANNELS=1;
  private static final int MAX_INPUT_BYTES=16*1024*1024;
  private static final int BUFFER_POOL_SIZE=3;
  private final Context context;
  private final SharedPreferences prefs;
  private final boolean withAudio;
  private final File directory;
  private final String title;
  private final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(2);
  private final ArrayBlockingQueue<byte[]> pool=new ArrayBlockingQueue<>(BUFFER_POOL_SIZE);
  private final AtomicInteger captured=new AtomicInteger();
  private final AtomicInteger dropped=new AtomicInteger();
  private final AtomicInteger encoded=new AtomicInteger();
  private final AtomicInteger audioChunks=new AtomicInteger();
  private final AtomicLong lastOfferMs=new AtomicLong(0);
  private final Object muxLock=new Object();
  private volatile boolean accepting=true;
  private volatile boolean finished=false;
  private volatile String error="";
  private volatile String fileDescription="Não gerado";
  private volatile boolean muxStarted=false;
  private volatile long baseNanos;
  private int vTrack=-1,aTrack=-1;
  private int targetW,targetH,sourceW,sourceH;
  private final int desiredFps,bitrate;
  private MediaCodec videoEncoder,audioEncoder;
  private MediaMuxer muxer;
  private AudioRecord recorder;
  private Thread worker,audioWorker;
  private File partial,complete;

  private static final class Frame {
    final byte[] data;
    final int width,height;
    final long nanos;
    Frame(byte[] data,int width,int height,long nanos){
      this.data=data;this.width=width;this.height=height;this.nanos=nanos;
    }
  }

  public OemAvRecorder(Context context,SharedPreferences prefs,File directory,
      boolean withAudio) {
    this.context=context.getApplicationContext();
    this.prefs=prefs;
    this.directory=directory;
    this.withAudio=withAudio;
    int fps=(int)prefs.getFloat("fps",20f);
    desiredFps=Math.max(5,Math.min(20,fps));
    float mbps=prefs.getFloat("bitrate_mbps",4f);
    bitrate=(int)(Math.max(.25,Math.min(16,mbps))*1000000);
    title=new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date());
  }

  /** Fast callback path: bounded queue, capped allocations, drop rather than block HAL. */
  public void accept(byte[] data,int width,int height,int size,long nanos) {
    if(!accepting||data==null||data.length<100||data.length>MAX_INPUT_BYTES
       ||OemSnapshotWriter.expectedNv21Bytes(width,height)<=0)return;
    long now=SystemClock.elapsedRealtime();
    long previous=lastOfferMs.get();
    if(now-previous<Math.max(50,1000/desiredFps))return;
    if(!lastOfferMs.compareAndSet(previous,now))return;
    if(queue.remainingCapacity()==0){dropped.incrementAndGet();return;}
    int copy=Math.min(data.length,size>0?size:data.length);
    int expected=OemSnapshotWriter.expectedNv21Bytes(width,height);
    if(copy<expected)return;
    byte[] cached=pool.poll();
    if(cached==null||cached.length<expected)cached=new byte[expected];
    System.arraycopy(data,0,cached,0,expected);
    if(!queue.offer(new Frame(cached,width,height,nanos))){
      pool.offer(cached);dropped.incrementAndGet();
    }else captured.incrementAndGet();
  }

  public void start(){
    worker=new Thread(this::loop,"fleet-avi-encoder");
    worker.start();
  }

  private void chooseSize(int w,int h){
    sourceW=w;sourceH=h;
    String quality=prefs.getString("video_quality","auto");
    int maxWidth="720p".equals(quality)?1280:"1080p".equals(quality)?1920:1280;
    int maxHeight=quality.equals("720p")?720:1080;
    double factor=Math.min(1d,Math.min(maxWidth/(double)w,maxHeight/(double)h));
    // Multiple-of-16 values work on the widest set of mobile H.264 encoders.
    targetW=Math.max(16,((int)Math.floor(w*factor)/16)*16);
    targetH=Math.max(16,((int)Math.floor(h*factor)/16)*16);
    if(targetW>1920||targetH>1080)throw new IllegalStateException("Dimensão acima do limite seguro do protótipo");
  }

  private int supportedColor(MediaCodec codec){
    MediaCodecInfo.CodecCapabilities caps=codec.getCodecInfo().getCapabilitiesForType(V_MIME);
    for(int c:caps.colorFormats)
      if(c==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)return c;
    // Not safe to send NV12 bytes to unknown flexible/native planar layouts.
    throw new IllegalStateException("Encoder não suporta entrada YUV420SemiPlanar; não gravar arquivo corrompido");
  }

  private void prepare(Frame first) throws Exception {
    chooseSize(first.width,first.height);
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Diretório de gravação inacessível");
    if(!OemMediaRetention.enoughSpace(directory,prefs))throw new IllegalStateException("Espaço reservado mínimo não disponível");
    complete=new File(directory,"andreao_fleet_cam_"+title+".mp4");
    partial=new File(directory,"andreao_fleet_cam_"+title+".mp4.partial");
    if(partial.exists())throw new IllegalStateException("Nome temporário em uso");
    videoEncoder=MediaCodec.createEncoderByType(V_MIME);
    MediaFormat video=MediaFormat.createVideoFormat(V_MIME,targetW,targetH);
    video.setInteger(MediaFormat.KEY_COLOR_FORMAT,supportedColor(videoEncoder));
    video.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
    video.setInteger(MediaFormat.KEY_FRAME_RATE,desiredFps);
    video.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
    videoEncoder.configure(video,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
    videoEncoder.start();
    muxer=new MediaMuxer(partial.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    baseNanos=System.nanoTime();
    if(withAudio){
      if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Permissão do microfone ausente");
      audioEncoder=MediaCodec.createEncoderByType(A_MIME);
      MediaFormat audio=MediaFormat.createAudioFormat(A_MIME,AUDIO_RATE,AUDIO_CHANNELS);
      audio.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      audio.setInteger(MediaFormat.KEY_BIT_RATE,32000);
      audio.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
      audioEncoder.configure(audio,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      audioEncoder.start();
      int minimum=AudioRecord.getMinBufferSize(AUDIO_RATE,
        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
      if(minimum<=0)throw new IllegalStateException("Microfone não suporta PCM 16 kHz");
      recorder=new AudioRecord(android.media.MediaRecorder.AudioSource.MIC,AUDIO_RATE,
        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,4096));
      if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)
        throw new IllegalStateException("AudioRecord não inicializado");
      recorder.startRecording();
      if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException("Microfone não iniciou");
      audioWorker=new Thread(this::audioLoop,"fleet-aac-encoder");
      audioWorker.start();
    }
  }

  private long ptsUs(){return Math.max(0L,(System.nanoTime()-baseNanos)/1000L);}

  private void scaleNv21ToNv12(ByteBuffer target,byte[] source,int w,int h) {
    // Preserve panorama aspect without trying to reinterpret vendor layouts.
    int yLength=sourceW*sourceH;
    for(int y=0;y<h;y++){
      int sy=Math.min(sourceH-1,y*sourceH/h);
      int row=sy*sourceW;
      for(int x=0;x<w;x++){
        int sx=Math.min(sourceW-1,x*sourceW/w);
        target.put(source[row+sx]);
      }
    }
    for(int y=0;y<h/2;y++){
      int sy=Math.min(sourceH/2-1,y*(sourceH/2)/(h/2));
      for(int x=0;x<w/2;x++){
        int sx=Math.min(sourceW/2-1,x*(sourceW/2)/(w/2));
        int from=yLength+sy*sourceW+sx*2;
        // NV21 VU to NV12 UV
        target.put(source[from+1]);
        target.put(source[from]);
      }
    }
  }

  private void submitVideo(Frame frame){
    if(frame.width!=sourceW||frame.height!=sourceH){
      dropped.incrementAndGet();return;
    }
    int id=videoEncoder.dequeueInputBuffer(30000);
    if(id<0){dropped.incrementAndGet();return;}
    ByteBuffer input=videoEncoder.getInputBuffer(id);
    int required=targetW*targetH*3/2;
    if(input==null||input.capacity()<required)
      throw new IllegalStateException("Input buffer H264 insuficiente");
    input.clear();
    scaleNv21ToNv12(input,frame.data,targetW,targetH);
    long p=Math.max(0,(frame.nanos-baseNanos)/1000L);
    videoEncoder.queueInputBuffer(id,0,required,p,0);
    encoded.incrementAndGet();
  }

  private void drain(MediaCodec codec,boolean video,boolean finalDrain){
    if(codec==null)return;
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    for(int i=0;i<(finalDrain?100:12);i++){
      int index=codec.dequeueOutputBuffer(info,finalDrain?10000:0);
      if(index==MediaCodec.INFO_TRY_AGAIN_LATER){
        if(!finalDrain)break;
        continue;
      }
      if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
        synchronized(muxLock){
          if(video)vTrack=muxer.addTrack(codec.getOutputFormat());
          else aTrack=muxer.addTrack(codec.getOutputFormat());
          if(!muxStarted&&vTrack>=0&&(!withAudio||aTrack>=0)){
            muxer.start();muxStarted=true;
          }
        }
      }else if(index>=0){
        ByteBuffer buf=codec.getOutputBuffer(index);
        synchronized(muxLock){
          if(buf!=null&&muxStarted&&info.size>0&&
            (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
            buf.position(info.offset);buf.limit(info.offset+info.size);
            muxer.writeSampleData(video?vTrack:aTrack,buf,info);
          }
        }
        codec.releaseOutputBuffer(index,false);
        if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;
      }
    }
  }

  private void audioLoop(){
    final byte[] pcm=new byte[4096];
    try{
      while(accepting && !Thread.currentThread().isInterrupted()){
        int count=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
        if(count<=0)continue;
        int idx=audioEncoder.dequeueInputBuffer(20000);
        if(idx<0){drain(audioEncoder,false,false);continue;}
        ByteBuffer in=audioEncoder.getInputBuffer(idx);
        if(in==null||in.capacity()<count)throw new IllegalStateException("Buffer AAC insuficiente");
        in.clear();in.put(pcm,0,count);
        audioEncoder.queueInputBuffer(idx,0,count,ptsUs(),0);
        audioChunks.incrementAndGet();
        drain(audioEncoder,false,false);
      }
      int idx=audioEncoder.dequeueInputBuffer(50000);
      if(idx>=0)audioEncoder.queueInputBuffer(idx,0,0,ptsUs(),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      drain(audioEncoder,false,true);
    }catch(Throwable e){error="Audio: "+e.getClass().getSimpleName()+": "+e.getMessage();}
  }

  private void loop(){
    try{
      Frame first=queue.poll(5,TimeUnit.SECONDS);
      if(first==null)throw new IllegalStateException("Nenhum frame recebido em 5s");
      prepare(first);
      Frame current=first;
      while(current!=null){
        try{submitVideo(current);drain(videoEncoder,true,false);}
        finally{pool.offer(current.data);}
        current=queue.poll(250,TimeUnit.MILLISECONDS);
        if(!accepting && current==null)break;
      }
      int idx=videoEncoder.dequeueInputBuffer(100000);
      if(idx>=0)videoEncoder.queueInputBuffer(idx,0,0,ptsUs(),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      drain(videoEncoder,true,true);
    }catch(Throwable e){
      error="Vídeo: "+e.getClass().getSimpleName()+": "+e.getMessage();
    }finally{
      accepting=false;
      if(recorder!=null)try{recorder.stop();}catch(Throwable ignored){}
      if(audioWorker!=null){
        try{audioWorker.join(2500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        if(audioWorker.isAlive())audioWorker.interrupt();
      }
      try{if(recorder!=null)recorder.release();}catch(Throwable ignored){}
      try{if(videoEncoder!=null){videoEncoder.stop();videoEncoder.release();}}catch(Throwable ignored){}
      try{if(audioEncoder!=null){audioEncoder.stop();audioEncoder.release();}}catch(Throwable ignored){}
      try{
        if(muxer!=null){
          if(muxStarted)muxer.stop();
          muxer.release();
        }
      }catch(Throwable e){error="Muxer: "+e.getClass().getSimpleName()+": "+e.getMessage();}
      if(partial!=null && partial.isFile()){
        if(muxStarted && error.isEmpty() && partial.length()>2048 && partial.renameTo(complete)){
          fileDescription=complete.getAbsolutePath()+" ("+(complete.length()/1024)+" KB, "
            +targetW+"x"+targetH+", áudio: "+(withAudio?"SIM":"NÃO")+")";
          OemMediaRetention.clean(context,prefs,directory);
        }else{
          if(!partial.delete())error+="; falha removendo arquivo temporário";
          if(error.isEmpty())error="Muxer não produziu MP4 íntegro; arquivo temporário removido";
        }
      }
      finished=true;
    }
  }

  public void stop(){
    accepting=false;
    if(recorder!=null)try{recorder.stop();}catch(Throwable ignored){}
  }
  public void join(long ms) throws InterruptedException {
    if(worker!=null)worker.join(ms);
  }
  public boolean finished(){return finished;}
  public File finishedFile(){return finished && error.isEmpty() && complete!=null && complete.isFile()?complete:null;}
  public String report(){
    return "Gravação MP4: "+fileDescription+"\n"+
      "Quadros entregues ao codificador: "+encoded.get()+"\n"+
      "Quadros oferecidos: "+captured.get()+"; descartados: "+dropped.get()+"\n"+
      "Amostras de áudio AAC: "+audioChunks.get()+"\n"+
      "Dimensões de saída: "+targetW+"x"+targetH+" @"+desiredFps+" FPS\n"+
      "Situação: "+(error.isEmpty()?(finished?"Concluído":"Em andamento"):error)+
      "\nArquivo final só é confirmado se o muxer finalizar e o .mp4 for renomeado.";
  }
  @Override public void close(){stop();}
}