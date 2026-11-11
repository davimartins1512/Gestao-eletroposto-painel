package br.com.andreao.fleetprobe;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Saves exactly ONE user-initiated OEM test frame as a JPEG in public Pictures,
 * after the vehicle camera is closed. Never records audio or video.
 *
 * Assumption to be verified physically: AVM on this DiLink variant returns NV21.
 * Width/height come from the real onPreview callback and aren't hardcoded.
 * Uses only the expected NV21 bytes; ignores vendor trailing padding if present.
 */
public final class OemSnapshotWriter {
  private OemSnapshotWriter(){}
  private static final long MAX_FRAME_BYTES=16L*1024*1024;
  public static final class Result{
    public final String message;
    public final boolean saved;
    Result(String message,boolean saved){this.message=message;this.saved=saved;}
  }
  public static int expectedNv21Bytes(int width,int height){
    if(width<16||height<16||width>8192||height>4096||
        (width%2)!=0||(height%2)!=0) return -1;
    long n=(long)width*height*3L/2L;
    return n>MAX_FRAME_BYTES?-1:(int)n;
  }
  public static Result save(Context context,byte[] bytes,int w,int h,int claimedSize){
    int expected=expectedNv21Bytes(w,h);
    if(expected<0)return new Result("Dimensões OEM desconhecidas ou acima do limite: "+w+"x"+h,false);
    if(bytes==null||bytes.length<expected)
      return new Result("Buffer incompleto para NV21: "+(bytes==null?0:bytes.length)+"/"+expected,false);
    if(claimedSize>0&&claimedSize<expected)
      return new Result("Tamanho informado pelo HAL é menor que a imagem esperada: "+claimedSize,false);

    String filename="andreao_fleet_camera_pano_h_"+
      new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new java.util.Date())+".jpg";
    Uri savedUri=null;
    try{
      // Bounded copy. Some firmware sends 7 padding bytes beyond a planar frame;
      // format still needs the user to visually verify the image.
      byte[] nv21=bytes.length==expected?bytes:Arrays.copyOf(bytes,expected);
      YuvImage yuv=new YuvImage(nv21,ImageFormat.NV21,w,h,null);
      ByteArrayOutputStream encoded=new ByteArrayOutputStream(1024*1024);
      if(!yuv.compressToJpeg(new Rect(0,0,w,h),75,encoded))
        return new Result("O Android recusou a compressão NV21. Nenhuma foto salva.",false);
      byte[] jpeg=encoded.toByteArray();
      if(jpeg.length<512 || jpeg.length>16*1024*1024)
        return new Result("JPEG gerado fora dos limites: "+jpeg.length+" bytes.",false);

      ContentValues values=new ContentValues();
      values.put(MediaStore.Images.Media.DISPLAY_NAME,filename);
      values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
      if(Build.VERSION.SDK_INT>=29){
        values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/ANDREAO_Fleet");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
      }
      savedUri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
      if(savedUri==null)return new Result("MediaStore recusou criar arquivo JPEG.",false);
      try(OutputStream output=context.getContentResolver().openOutputStream(savedUri)){
        if(output==null)throw new IllegalStateException("Destino não abriu");
        output.write(jpeg);output.flush();
      }
      if(Build.VERSION.SDK_INT>=29){
        ContentValues published=new ContentValues();
        published.put(MediaStore.Images.Media.IS_PENDING,0);
        context.getContentResolver().update(savedUri,published,null,null);
      }
      return new Result("JPEG experimental salvo em Imagens/Pictures/ANDREAO_Fleet/"+filename+
          " ("+w+"x"+h+", "+jpeg.length+" bytes). "+
          "Confira visualmente se está correto antes de considerar formato validado. "+
          "Foto local; nenhum upload.",true);
    }catch(Throwable error){
      if(savedUri!=null){
        try{context.getContentResolver().delete(savedUri,null,null);}catch(Throwable ignored){}
      }
      return new Result("Falha salvando JPEG: "+error.getClass().getSimpleName()+
        ". Nenhum arquivo íntegro confirmado.",false);
    }
  }
}
