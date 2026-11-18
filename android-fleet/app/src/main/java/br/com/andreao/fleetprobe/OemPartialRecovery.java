package br.com.andreao.fleetprobe;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual recovery of OUR OWN already-finalized-but-unrenamed MP4 files.
 *
 * A crash in MediaMuxer.stop() usually leaves a nonplayable .partial file;
 * this must never be renamed merely because it exists. Only files that
 * Android's MediaMetadataRetriever recognizes with a valid video track and
 * duration are recovered. Invalid/unfinished files are left untouched for
 * review; no automatic deletion or attempt at blind byte repair.
 *
 * Do not run during an active recording; the UI exposes this separately
 * in the stopped/offline recording library screen.
 */
public final class OemPartialRecovery {
  private OemPartialRecovery(){}

  private static final String PREFIX="andreao_fleet_cam_";
  private static final String SUFFIX=".mp4.partial";
  private static final long MIN_AGE_MS=120000L;
  private static final int MAX_FILES=200;

  public static final class Report {
    public final int checked,recovered,invalid,tooRecent,errors;
    public final List<String> details;
    Report(int checked,int recovered,int invalid,int tooRecent,int errors,List<String> details){
      this.checked=checked;this.recovered=recovered;this.invalid=invalid;
      this.tooRecent=tooRecent;this.errors=errors;this.details=details;
    }
    public String summary(){
      return "Recuperação manual de MP4 temporários\n"
        +"Arquivos examinados: "+checked+"\n"
        +"MP4 válidos recuperados: "+recovered+"\n"
        +"Arquivos incompletos preservados: "+invalid+"\n"
        +"Arquivos recentes ignorados: "+tooRecent+"\n"
        +"Erros: "+errors+"\n"
        +"Não repara clipes quebrados nem prova a presença de áudio.\n"
        +String.join("\n",details);
    }
  }

  public static Report recover(Context context){
    int checked=0,recovered=0,invalid=0,recent=0,errors=0;
    List<String> details=new ArrayList<>();
    long now=System.currentTimeMillis();
    for(StoragePolicy.Candidate location:StoragePolicy.inventory(context,false)){
      File folder=location.folder;
      if(folder==null||!folder.isDirectory())continue;
      File[] files=folder.listFiles();
      if(files==null){errors++;details.add(location.name+": sem acesso à pasta");continue;}
      for(File input:files){
        if(checked>=MAX_FILES)break;
        String name=input.getName();
        if(!input.isFile()||!name.startsWith(PREFIX)||!name.endsWith(SUFFIX)
          ||name.contains("..")||name.contains("/")||name.contains("\\"))continue;
        try{
          String root=folder.getCanonicalPath()+File.separator;
          if(!input.getCanonicalPath().startsWith(root))continue;
          checked++;
          long age=now-input.lastModified();
          if(age<MIN_AGE_MS){
            recent++;
            details.add(name+": recente, deixado intacto");
            continue;
          }
          File complete=new File(folder,name.substring(0,name.length()-".partial".length()));
          if(complete.exists()){
            errors++;
            details.add(name+": destino já existe, não sobrescrevido");
            continue;
          }
          OemMp4Verifier.Result check=OemMp4Verifier.verify(input,false);
          if(!check.valid){
            invalid++;
            details.add(name+": incompleto; preservado ("+check.detail+")");
            continue;
          }
          if(input.renameTo(complete) && complete.isFile()){
            recovered++;
            details.add(name+": recuperado, "+check.durationMs+" ms; verificar áudio manualmente");
          }else{
            errors++;
            details.add(name+": não foi possível concluir a renomeação");
          }
        }catch(IOException | SecurityException e){
          errors++;
          details.add(name+": acesso negado/inválido");
        }
      }
    }
    return new Report(checked,recovered,invalid,recent,errors,details);
  }
}