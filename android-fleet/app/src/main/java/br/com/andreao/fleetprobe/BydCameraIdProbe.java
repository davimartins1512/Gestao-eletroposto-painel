package br.com.andreao.fleetprobe;

import android.content.Context;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Camera discovery for BYD DiLink via the framework jar confirmed on the Mini.
 * Read-only: calls BmmCameraInfo.getCameraId(String) to resolve vendor tags,
 * but NEVER opens AVMCamera, starts a preview, allocates a Surface, or records.
 *
 * This is NOT proof that a third-party app may open a camera or read frames.
 */
public final class BydCameraIdProbe {
  private BydCameraIdProbe(){}
  private static final String[] TAGS = {
    "pano_h","pano_l","apa","byd_apa",
    "front","rear","rvs","rf","dms","face",
    "d954_h_m","d954_h_s","d954_l_m","d954_l_s"
  };
  private static String args(Class<?>[] types){
    StringBuilder s=new StringBuilder("(");
    for(int i=0;i<types.length;i++){
      if(i!=0)s.append(", ");
      s.append(types[i].getSimpleName());
    }
    return s.append(')').toString();
  }
  private static void methodShape(StringBuilder out,Class<?> c,String name){
    boolean found=false;
    try{
      for(Method m:c.getDeclaredMethods()){
        if(!name.equals(m.getName()))continue;
        out.append(name).append(args(m.getParameterTypes()))
          .append(" -> ").append(m.getReturnType().getSimpleName())
          .append(Modifier.isStatic(m.getModifiers())?" [estático]":" [instância]")
          .append('\n');
        found=true;
      }
    }catch(Throwable t){out.append(name).append(": metadados bloqueados ")
      .append(t.getClass().getSimpleName()).append('\n');}
    if(!found)out.append(name).append(": não identificado\n");
  }

  public static String inspect(Context context){
    StringBuilder out=new StringBuilder("BYD DILINK · DESCOBERTA DE CAMERAS / LEITURA DE IDS\n");
    out.append("NÃO abre câmeras, NÃO aciona câmera de ré e NÃO grava áudio/vídeo.\n");
    File jar=new File("/system/framework/bmmcamera.jar");
    if(!jar.isFile())return out.append("bmmcamera.jar não disponível.").toString();
    try {
      ClassLoader loader=new PathClassLoader(jar.getAbsolutePath(),context.getClassLoader());
      Class<?> info=Class.forName("android.hardware.BmmCameraInfo",false,loader);
      Method lookup=info.getDeclaredMethod("getCameraId",String.class);
      lookup.setAccessible(true);
      if(!Modifier.isStatic(lookup.getModifiers())){
        out.append("getCameraId não é estático nesta versão: consulta cancelada para evitar instanciar serviço OEM.\n");
      }else{
        out.append("IDs consultados por nome (valores menores que 0 significam não disponível):\n");
        int available=0;
        for(String tag:TAGS){
          try{
            Object value=lookup.invoke(null,tag);
            if(value instanceof Number){
              int id=((Number)value).intValue();
              if(id>=0){out.append(tag).append(" = ").append(id).append('\n');available++;}
              else out.append(tag).append(" = indisponível (").append(id).append(")\n");
            }else out.append(tag).append(" = retorno inesperado\n");
          }catch(Throwable error){
            Throwable t=error instanceof java.lang.reflect.InvocationTargetException
              &&error.getCause()!=null?error.getCause():error;
            out.append(tag).append(" = consulta negada: ")
              .append(t.getClass().getSimpleName()).append('\n');
          }
        }
        out.append("Tags com ID válido: ").append(available).append("\n");
      }
      Class<?> camera=Class.forName("android.hardware.AVMCamera",false,loader);
      out.append("\nASSINATURAS DA CLASSE AVMCamera (não invocadas)\n");
      try{
        for(Constructor<?> c:camera.getDeclaredConstructors())
          out.append("constructor").append(args(c.getParameterTypes())).append('\n');
      }catch(Throwable e){out.append("Construtores não disponíveis\n");}
      for(String name:new String[]{"open","close","startPreview","stopPreview",
        "setPreviewCallback","enablePreviewCallback","addPreviewSurface"})
        methodShape(out,camera,name);
      try {
        Class<?>[] nested=camera.getDeclaredClasses();
        for(Class<?> type:nested){
          if(type.getName().contains("IPreviewCallback")){
            out.append("Callback OEM: ").append(type.getName()).append('\n');
            for(Method m:type.getDeclaredMethods())
              out.append("  ").append(m.getName()).append(args(m.getParameterTypes()))
                .append(" -> ").append(m.getReturnType().getSimpleName()).append('\n');
          }
        }
      }catch(Throwable e){out.append("Metadados do callback indisponíveis: ")
        .append(e.getClass().getSimpleName()).append('\n');}
    }catch(Throwable e){
      Throwable cause=e instanceof java.lang.reflect.InvocationTargetException
        &&e.getCause()!=null?e.getCause():e;
      out.append("Falha consultando metadados OEM: ")
        .append(cause.getClass().getSimpleName()).append('\n');
    }
    out.append("\nATENÇÃO: obter IDs e assinaturas não demonstra acesso aos frames.");
    out.append("\nPróxima etapa: teste de abertura manual em carro parado, com controle de conflito OEM, timeout e fechamento seguro.");
    return out.toString();
  }
}
