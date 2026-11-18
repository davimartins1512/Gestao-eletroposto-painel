package br.com.andreao.fleetprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A bounded, cross-process checkpoint for manual OEM tests. The camera probe
 * runs in :oem_camera_probe; on a process crash or watchdog termination, the
 * launcher can still show the last persisted state instead of mislabeling
 * a playable MP4 as a failed recording.
 *
 * Metadata only. No frames, locations, microphones, device IDs or uploads.
 */
public final class OemProbeCheckpoint {
  private OemProbeCheckpoint(){}
  private static final int MAX_BYTES=2048;
  private static AtomicFile file(Context context){
    return new AtomicFile(new File(context.getFilesDir(),"fleet_oem_last_checkpoint.txt"));
  }
  public static void save(Context context,String phase,int mode,long seconds,int frames,int clips){
    String timestamp=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());
    String value="ANDREAO Fleet · último estado gravado no armazenamento local\n"+
      "Data: "+timestamp+"\n"+
      "Fase: "+phase+"\n"+
      "Modo: "+mode+"\n"+
      "Tempo de teste: "+Math.max(0,seconds)+" s\n"+
      "Quadros vistos: "+Math.max(0,frames)+"\n"+
      "MP4 concluídos na sessão: "+Math.max(0,clips)+"\n"+
      "Este diagnóstico NÃO substitui a verificação dos arquivos pela galeria.\n";
    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
    if(bytes.length>MAX_BYTES)return;
    AtomicFile atomic=file(context);
    FileOutputStream stream=null;
    try{
      stream=atomic.startWrite();
      stream.write(bytes);
      atomic.finishWrite(stream);
    }catch(Exception e){
      if(stream!=null)atomic.failWrite(stream);
    }
  }
  public static String read(Context context){
    AtomicFile atomic=file(context);
    try(FileInputStream stream=atomic.openRead()){
      byte[] bytes=new byte[MAX_BYTES+1];
      int total=0,n;
      while(total<bytes.length&&(n=stream.read(bytes,total,bytes.length-total))>0)total+=n;
      if(total>MAX_BYTES)return "Estado anterior acima do limite permitido.";
      return new String(bytes,0,total,StandardCharsets.UTF_8);
    }catch(Exception e){
      return "Não há estado salvo de uma sessão OEM anterior.";
    }
  }
}
