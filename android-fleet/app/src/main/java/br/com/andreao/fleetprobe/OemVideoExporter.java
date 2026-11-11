package br.com.andreao.fleetprobe;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/** Export on explicit user action only; no background upload, no arbitrary file paths. */
public final class OemVideoExporter {
  private OemVideoExporter(){}
  public static String copyToMovies(Context context,File source) {
    if(source==null||!source.isFile())return "Nenhum MP4 finalizado para exportar.";
    String filename=source.getName();
    if(!filename.startsWith("andreao_fleet_cam_")||!filename.endsWith(".mp4"))
      return "Este arquivo não pertence ao gravador ANDREAO Fleet.";
    if(source.length()<2048||source.length()>256L*1024*1024)
      return "Arquivo vazio ou maior que o limite de exportação de teste (256MB).";
    boolean approved=false;
    try {
      String full=source.getCanonicalPath();
      for(StoragePolicy.Candidate c:StoragePolicy.inventory(context,false)){
        if(c.folder==null)continue;
        String root=c.folder.getCanonicalPath()+File.separator;
        if(full.startsWith(root))approved=true;
      }
      if(!approved)return "Caminho fora das pastas de gravação permitidas.";
    }catch(Exception e){return "Não foi possível validar o caminho do arquivo.";}
    Uri output=null;
    try{
      ContentValues values=new ContentValues();
      values.put(MediaStore.Video.Media.DISPLAY_NAME,filename);
      values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
      if(Build.VERSION.SDK_INT>=29){
        values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ANDREAO_Fleet");
        values.put(MediaStore.Video.Media.IS_PENDING,1);
      }
      output=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
      if(output==null)return "O armazenamento compartilhado recusou o arquivo.";
      try(FileInputStream input=new FileInputStream(source);
          OutputStream out=context.getContentResolver().openOutputStream(output)){
        if(out==null)throw new IllegalStateException("Não foi possível abrir o destino");
        byte[] chunk=new byte[65536];int n;
        while((n=input.read(chunk))!=-1)out.write(chunk,0,n);
        out.flush();
      }
      if(Build.VERSION.SDK_INT>=29){
        ContentValues published=new ContentValues();
        published.put(MediaStore.Video.Media.IS_PENDING,0);
        context.getContentResolver().update(output,published,null,null);
      }
      return "Cópia exportada para Filmes/Movies/ANDREAO_Fleet/"+filename+
        " · MP4 original continua no armazenamento configurado. "+
        "Atenção: mídia exportada passa a ser visível para outros aplicativos com acesso à galeria.";
    }catch(Throwable ex){
      if(output!=null)try{context.getContentResolver().delete(output,null,null);}catch(Throwable ignored){}
      return "Erro ao exportar: "+ex.getClass().getSimpleName();
    }
  }
}