package br.com.andreao.fleetprobe;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Small local, app-private diagnostic journal of completed MANUAL sessions.
 *
 * Does not store raw media or cabin audio, does not upload, and does not make
 * cryptographic tamper-evidence claims. Only the last 30 diagnostic reports
 * are retained to bound storage. Keeps post-crash context if the main screen
 * was closed before exporting its TXT (completed sessions only).
 */
public final class OemSessionJournal {
  private OemSessionJournal(){}
  private static final String FOLDER="fleet_media_audit";
  private static final String PREFIX="session_";
  private static final int KEEP=30;
  private static final int MAX_TEXT_BYTES=32768;

  private static File folder(Context context) {
    return new File(context.getFilesDir(),FOLDER);
  }
  private static boolean valid(File file){
    String n=file.getName();
    return file.isFile()&&n.startsWith(PREFIX)&&n.endsWith(".txt")
      &&!n.contains("..")&&!n.contains("/")&&!n.contains("\\");
  }
  private static File[] sessions(File folder) {
    File[] found=folder.listFiles(f->valid(f));
    if(found==null)return new File[0];
    Arrays.sort(found,Comparator.comparingLong(File::lastModified).reversed());
    return found;
  }
  public static String write(Context context,String report){
    if(report==null)return "Nenhum diagnóstico disponível.";
    File dir=folder(context);
    if(!dir.isDirectory()&&!dir.mkdirs())return "Falha criando pasta do histórico.";
    String suffix=String.format(Locale.US,"%d_%d",System.currentTimeMillis(),
      android.os.Process.myPid());
    File temp=new File(dir,PREFIX+suffix+".tmp");
    File finalFile=new File(dir,PREFIX+suffix+".txt");
    byte[] bytes=report.getBytes(StandardCharsets.UTF_8);
    if(bytes.length>MAX_TEXT_BYTES)
      return "Diagnóstico ultrapassa limite de 32 KB; mantido apenas na tela.";
    try{
      try(FileOutputStream output=new FileOutputStream(temp)){
        output.write(bytes);
        output.getFD().sync();
      }
      if(!temp.renameTo(finalFile))
        return "Falha confirmando arquivo de diagnóstico; mantido na tela.";
      File[] all=sessions(dir);
      for(int i=KEEP;i<all.length;i++){
        // Delete ONLY old app-private session report text, not media or incidents.
        if(!all[i].delete())return "Diagnóstico salvo; alguns relatórios antigos permaneceram.";
      }
      return "Diagnóstico salvo localmente: "+finalFile.getName();
    }catch(Exception e){
      return "Não foi possível salvar diagnóstico local: "+e.getClass().getSimpleName();
    }finally{
      if(temp.exists())temp.delete();
    }
  }
  public static String latest(Context context){
    File[] found=sessions(folder(context));
    if(found.length==0)return "Ainda não há sessão concluída salva.";
    File latest=found[0];
    if(latest.length()>MAX_TEXT_BYTES)return "Relatório fora do limite de leitura.";
    try(FileInputStream input=new FileInputStream(latest);
        ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] chunk=new byte[4096];
      int count;
      while((count=input.read(chunk))!=-1){
        if(out.size()+count>MAX_TEXT_BYTES)return "Relatório fora do limite de leitura.";
        out.write(chunk,0,count);
      }
      return "RELATÓRIO "+latest.getName()+"\n\n"+
        new String(out.toByteArray(),StandardCharsets.UTF_8);
    }catch(Exception e){
      return "Não foi possível ler o relatório: "+e.getClass().getSimpleName();
    }
  }
}