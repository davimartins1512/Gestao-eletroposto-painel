package br.com.andreao.fleetprobe;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Offline private recording library, surviving Activity/process restarts.
 * Enumerates ONLY app-owned files in StoragePolicy-approved directories.
 * Does not touch Electro files, media outside this app, or remote services.
 */
public final class OemRecordingLibrary {
  private OemRecordingLibrary(){}
  private static final String PREFIX="andreao_fleet_cam_";
  public static final class Entry {
    public final File file;
    public final String storageLabel;
    public final boolean removable,protectedEvent;
    public final long sizeBytes,lastModified;
    Entry(File file,String storageLabel,boolean removable) {
      this.file=file;this.storageLabel=storageLabel;this.removable=removable;
      this.protectedEvent=file.getName().endsWith(".protected.mp4");
      this.sizeBytes=file.length();
      this.lastModified=file.lastModified();
    }
    public String title(){return file.getName();}
  }
  public static boolean validName(String name){
    return name!=null&&name.startsWith(PREFIX)&&name.endsWith(".mp4")
      &&!name.contains("/")&&!name.contains("\\")&&!name.contains("..");
  }
  public static List<Entry> list(Context context) {
    List<Entry> out=new ArrayList<>();
    for(StoragePolicy.Candidate candidate:StoragePolicy.inventory(context,false)){
      if(candidate.folder==null||!candidate.folder.isDirectory())continue;
      File[] files=candidate.folder.listFiles();
      if(files==null)continue;
      String root;
      try{root=candidate.folder.getCanonicalPath()+File.separator;}
      catch(IOException e){continue;}
      for(File file:files){
        if(!file.isFile()||!validName(file.getName())||file.length()<2048)continue;
        try{
          if(!file.getCanonicalPath().startsWith(root))continue;
        }catch(IOException e){continue;}
        out.add(new Entry(file,candidate.name,candidate.removable));
      }
    }
    out.sort(Comparator.comparingLong((Entry e)->e.lastModified).reversed());
    return out;
  }
  /** Validate again before acting: libraries cannot trust a stale UI entry. */
  public static File resolve(Context context,String absolutePath) {
    if(absolutePath==null||absolutePath.length()>4096)return null;
    try {
      File f=new File(absolutePath);
      if(!f.isFile()||!validName(f.getName()))return null;
      String canonical=f.getCanonicalPath();
      for(StoragePolicy.Candidate candidate:StoragePolicy.inventory(context,false)){
        if(candidate.folder==null)continue;
        String root=candidate.folder.getCanonicalPath()+File.separator;
        if(canonical.startsWith(root))return f;
      }
    }catch(Exception ignored){}
    return null;
  }
}