package br.com.andreao.fleetprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Regras de selecao segura da pasta de midia. Nenhuma camera e iniciada. */
public final class StoragePolicy {
  public static final String PREFS="andreao_fleet_media_options";
  private StoragePolicy(){}

  public static final class Candidate {
    public final File folder;
    public final String name;
    public final boolean removable;
    public final long freeBytes;
    public final String error;
    Candidate(File folder,String name,boolean removable,long freeBytes,String error){
      this.folder=folder;this.name=name;this.removable=removable;
      this.freeBytes=freeBytes;this.error=error;
    }
    public boolean writable(){return error==null;}
  }

  public static List<Candidate> inventory(Context context,boolean checkWrite){
    List<Candidate> found=new ArrayList<>();
    File privateDir=new File(context.getFilesDir(),"andreao_fleet_media");
    found.add(checkWrite?check(privateDir,"Memória interna protegida",false):
      new Candidate(privateDir,"Memória interna protegida",false,privateDir.getUsableSpace(),null));
    File[] volumes=context.getExternalFilesDirs("andreao_fleet_media");
    if(volumes==null)return found;
    for(File folder:volumes){
      if(folder==null)continue;
      boolean removable;
      try{removable=Environment.isExternalStorageRemovable(folder);}
      catch(Throwable e){removable=false;}
      String name=removable?"USB / cartão removível":"Armazenamento compartilhado Android";
      try{
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(folder))){
          found.add(new Candidate(folder,name,removable,0,"Volume não está montado em leitura e escrita"));
          continue;
        }
        found.add(checkWrite?check(folder,name,removable):
          new Candidate(folder,name,removable,folder.getUsableSpace(),null));
      }catch(Throwable e){found.add(new Candidate(folder,name,removable,0,e.getClass().getSimpleName()));}
    }
    return found;
  }

  private static Candidate check(File folder,String name,boolean removable){
    if(!folder.isDirectory()&&!folder.mkdirs())
      return new Candidate(folder,name,removable,0,"Pasta do aplicativo não acessível");
    long available=folder.getUsableSpace();
    File temporary=null;
    try {
      temporary=File.createTempFile(".fleet_write_test_",".tmp",folder);
      try(FileOutputStream output=new FileOutputStream(temporary)){
        output.write(new byte[]{65,78,68,82,69,65,79});
        output.flush();
      }
      return new Candidate(folder,name,removable,available,null);
    }catch(Exception e){
      return new Candidate(folder,name,removable,available,"Teste de escrita falhou: "+e.getClass().getSimpleName());
    }finally{if(temporary!=null&&!temporary.delete())temporary.deleteOnExit();}
  }

  /**
   * Reavaliar no começo de cada segmento quando o gravador existir.
   * Teste so permite gravação em pasta especifica do app.
   */
  public static Candidate select(Context context,SharedPreferences config){
    String target=config.getString("storage_target","auto");
    boolean autoswitch=config.getBoolean("removable_autoswitch",true);
    boolean fallback=config.getBoolean("fallback_internal",true);
    long reserve=(long)(config.getFloat("reserve_free_gb",2f)*1024*1024*1024L);
    List<Candidate> all=inventory(context,true);
    Candidate internal=null,removable=null;
    for(Candidate c:all){
      if(!c.writable()||c.freeBytes<reserve)continue;
      if(c.removable&&removable==null)removable=c;
      if(c.folder.equals(new File(context.getFilesDir(),"andreao_fleet_media")))internal=c;
    }
    if("internal".equals(target))return internal;
    if("removable".equals(target))return removable!=null?removable:fallback?internal:null;
    // Auto: removable only when autoswitch is enabled.
    return autoswitch&&removable!=null?removable:internal;
  }

  public static String describe(Context context,SharedPreferences config){
    StringBuilder result=new StringBuilder("Armazenamento Android disponível para ESTE aplicativo:\n");
    for(Candidate c:inventory(context,true)){
      result.append("\n").append(c.name).append(c.removable?" (removível)":"")
        .append("\nPasta: ").append(c.folder.getAbsolutePath())
        .append("\nEspaço livre: ").append(c.freeBytes/(1024*1024)).append(" MB")
        .append("\nEscrita: ").append(c.writable()?"confirmada":c.error).append("\n");
    }
    Candidate chosen=select(context,config);
    result.append("\nDestino selecionável com as regras atuais: ")
      .append(chosen==null?"NENHUM disponível (não iniciar gravação)":
        chosen.name+" · "+chosen.folder.getAbsolutePath());
    result.append("\nAVISO: pendrive no gerenciador BYD não garante permissão Android para salvar.");
    result.append("\nNão grava vídeo. Nunca acessa nem remove arquivos de outro aplicativo.");
    return result.toString();
  }
}
