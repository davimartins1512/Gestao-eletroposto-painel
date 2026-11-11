package br.com.andreao.fleetprobe;

import android.media.MediaMetadataRetriever;
import java.io.File;

/** Best-effort Android metadata gate before exposing an MP4 as a completed clip. */
public final class OemMp4Verifier {
  private OemMp4Verifier(){}
  public static final class Result {
    public final boolean valid;
    public final String detail;
    public final long durationMs;
    Result(boolean valid,String detail,long durationMs){
      this.valid=valid;this.detail=detail;this.durationMs=durationMs;
    }
  }
  private static long parseLong(String value){
    try{return Long.parseLong(value);}
    catch(Exception ex){return -1L;}
  }
  public static Result verify(File file,boolean requiresAudio){
    if(file==null||!file.isFile()||file.length()<2048)
      return new Result(false,"Arquivo ausente ou pequeno demais",0);
    MediaMetadataRetriever reader=new MediaMetadataRetriever();
    try{
      reader.setDataSource(file.getAbsolutePath());
      long ms=parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      long w=parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
      long h=parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
      String video=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
      String audio=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
      boolean valid=ms>0 && w>0 && h>0 && "yes".equalsIgnoreCase(video)
        && (!requiresAudio || "yes".equalsIgnoreCase(audio));
      String description="duração="+ms+"ms, vídeo="+w+"x"+h+
        ", faixa de vídeo="+video+", faixa de áudio="+audio;
      if(!valid)description="MP4 não aprovado: "+description;
      return new Result(valid,description,ms);
    }catch(Throwable e){
      return new Result(false,"MP4 ainda não verificável pelo Android: "+e.getClass().getSimpleName(),0);
    }finally{
      try{reader.release();}catch(Throwable ignored){}
    }
  }
}
