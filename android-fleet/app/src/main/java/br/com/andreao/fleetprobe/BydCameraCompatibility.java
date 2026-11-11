package br.com.andreao.fleetprobe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Read-only discovery of DiLink camera integration surfaces on a stationary car.
 *
 * NO camera open, preview, surface attachment, binder calls, audio capture, root,
 * ADB, shell, control of reverse/AVM app, or native library loading.
 *
 * This does not bypass vendor access controls or imply camera frame availability.
 */
public final class BydCameraCompatibility {
  private BydCameraCompatibility() {}

  // BYD DiLink 3/4 API names documented in open-source OverDrive (MIT):
  // https://github.com/yash-srivastava/Overdrive-release
  private static final String[] CAMERA_CLASSES = {
    "android.hardware.AVMCamera",
    "android.hardware.BmmCameraInfo",
    "android.hardware.IBYDCameraService",
    "android.hardware.IBYDCameraService$Stub",
    "android.hardware.IBYDCameraUser"
  };
  private static final String[] SAFE_METHOD_NAMES = {
    "open", "close", "startPreview", "stopPreview", "addPreviewSurface",
    "removePreviewSurface", "getCameraId", "setPreviewCallback",
    "enablePreviewCallback", "setCameraFps", "getCurrentCameraUser"
  };
  private static final String[] OEM_PACKAGES = {
    "com.byd.apa", "com.byd.avm", "com.ts.avm"
  };
  private static final String[] FILE_HINTS = {
    "/system/framework/bmmcamera.jar",
    "/system/framework/framework.jar",
    "/vendor/lib64/libais_client.so"
  };
  private static String classStatus(String name) {
    try {
      Class<?> c = Class.forName(name, false, BydCameraCompatibility.class.getClassLoader());
      StringBuilder methods = new StringBuilder();
      for (Method m : c.getDeclaredMethods()) {
        if (!Arrays.asList(SAFE_METHOD_NAMES).contains(m.getName())) continue;
        if (methods.length()>0) methods.append(", ");
        methods.append(m.getName()).append('(').append(m.getParameterTypes().length).append(" args)");
      }
      return "encontrada" + (methods.length() > 0 ? "; métodos identificados: "+methods : "; sem métodos conhecidos expostos");
    } catch (ClassNotFoundException e) {
      return "não encontrada pelo classloader do aplicativo";
    } catch (SecurityException e) {
      return "metadados bloqueados pelo sistema (SecurityException)";
    } catch (Throwable e) {
      return "consulta bloqueada ou incompatível (" + e.getClass().getSimpleName() + ")";
    }
  }
  public static String summarize(Context context) {
    StringBuilder out = new StringBuilder(1300);
    out.append("ANDREAO Fleet · compatibilidade de câmeras OEM BYD\n");
    out.append("Android ").append(Build.VERSION.RELEASE).append(" (API ")
      .append(Build.VERSION.SDK_INT).append(")\n");
    out.append("Somente descoberta de interfaces. Nenhum vídeo ou áudio capturado.\n");
    out.append("Nenhuma câmera aberta, nenhum comando ao carro ou à câmera de ré.\n\n");
    out.append("CLASSES DA PLATAFORMA\n");
    for (String key:CAMERA_CLASSES)
      out.append(key).append(": ").append(classStatus(key)).append("\n");

    out.append("\nPACOTES DE VISUALIZAÇÃO BYD (disponibilidade, não acesso)\n");
    PackageManager pm=context.getPackageManager();
    for(String key:OEM_PACKAGES) {
      try {
        pm.getPackageInfo(key,0);
        out.append(key).append(": instalado\n");
      } catch (PackageManager.NameNotFoundException e) {
        out.append(key).append(": ausente ou não visível\n");
      } catch (Throwable e) {
        out.append(key).append(": consulta indisponível\n");
      }
    }
    out.append("\nBIBLIOTECAS CONHECIDAS (existência, não permissão)\n");
    for (String path : FILE_HINTS) {
      try {
        out.append(path).append(": ")
          .append(new File(path).isFile() ? "arquivo presente" : "não visível").append("\n");
      } catch (Throwable e) { out.append(path).append(": erro de leitura de metadados\n"); }
    }
    out.append("\nCAMERA2 DO ANDROID\n");
    try {
      CameraManager m=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
      if (m == null) out.append("Serviço indisponível\n");
      else out.append("IDs disponíveis: ").append(m.getCameraIdList().length).append("\n");
    } catch (SecurityException denied) {
      out.append("Sem permissão, apenas a consulta de Camera2 foi negada\n");
    } catch (Throwable e) {
      out.append("Camera2 indisponível: ").append(e.getClass().getSimpleName()).append("\n");
    }

    out.append("\nINTERPRETAÇÃO\n");
    out.append("Encontrar AVMCamera/BmmCameraInfo indica POSSÍVEL caminho de integração.");
    out.append(" Não prova abertura de câmera nem recepção de frames.");
    out.append("\nCâmeras de ré/360° têm prioridade operacional. Um teste futuro de frames");
    out.append(" exige controle de concorrência, carro parado, validação física e consentimento apropriado.");
    out.append("\nZero IDs Camera2 não invalida as câmeras originais do veículo.");
    out.append("\nMicrofone só poderá ser gravado com informação prévia aos ocupantes.");
    return out.toString();
  }
}
