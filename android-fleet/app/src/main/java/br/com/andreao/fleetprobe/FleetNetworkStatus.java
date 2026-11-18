package br.com.andreao.fleetprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Process;
import java.util.Locale;

/** Read-only diagnostics. A different class hosts a manual LAN-only HTTP server. */
public final class FleetNetworkStatus {
  private FleetNetworkStatus(){}
  public static final class Status{
    public final boolean wifi,cellular,validated,metered,connected;
    public final long uidTxBytes,uidRxBytes;
    Status(boolean wifi,boolean cellular,boolean validated,boolean metered,
           boolean connected,long tx,long rx){
      this.wifi=wifi;this.cellular=cellular;this.validated=validated;
      this.metered=metered;this.connected=connected;
      this.uidTxBytes=tx;this.uidRxBytes=rx;
    }
    public String describe(){
      String connection=!connected?"Sem rede ativa":
        wifi?"Wi-Fi (origem e dono não identificáveis automaticamente)":
        cellular?"Rede celular — NÃO AUTORIZADA para vídeos":"Outra rede — não autorizada";
      return "REDE ATUAL: "+connection+
        "\nInternet validada pelo Android: "+(validated?"SIM":"NÃO")+
        "\nConexão tarifada/restrita (sinal do Android): "+(metered?"SIM":"NÃO")+
        "\nTX do próprio app desde o início do sistema: "+format(uidTxBytes)+
        "\nRX do próprio app desde o início do sistema: "+format(uidRxBytes)+
        "\nAtenção: métricas por UID incluem tráfego do próprio app desde o boot,"+
        " podem ser indisponíveis e NÃO medem o consumo de outros apps/BYD."+
        "\nO APK possui permissão INTERNET somente para servidor HTTP LOCAL manual; não envia dados para nuvem."+
        "\nWi-Fi também pode consumir franquia do telefone. Conectar não autoriza envio.";
    }
  }
  private static String format(long bytes){
    if(bytes<0)return "indisponível";
    return String.format(Locale.US,"%.2f MB",bytes/1000000d);
  }
  public static Status read(Context context){
    boolean wifi=false,cellular=false,validated=false,metered=true,connected=false;
    try{
      ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
      Network active=cm==null?null:cm.getActiveNetwork();
      NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
      if(caps!=null){
        connected=true;
        wifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        cellular=caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        validated=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        metered=!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
      }
    }catch(Throwable ignored){}
    long tx=TrafficStats.getUidTxBytes(Process.myUid());
    long rx=TrafficStats.getUidRxBytes(Process.myUid());
    return new Status(wifi,cellular,validated,metered,connected,tx,rx);
  }
}
