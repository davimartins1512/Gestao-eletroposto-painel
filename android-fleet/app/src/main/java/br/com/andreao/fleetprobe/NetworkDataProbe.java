package br.com.andreao.fleetprobe;

import android.net.TrafficStats;
import android.os.Process;
import java.util.Locale;

/** Contadores cumulativos Android, nao saldo/franquia de operadora. */
public final class NetworkDataProbe {
  private NetworkDataProbe(){}
  private static String mb(long bytes){
    return bytes<0 ? "Indisponivel" :
      String.format(Locale.US,"%.2f MB (%d bytes)",bytes/1048576d,bytes);
  }
  public static String snapshot(){
    StringBuilder b=new StringBuilder("Contadores Android de rede desde ultima reinicializacao (quando suportado).\n");
    try{
      b.append("Recebidos rede movel (sistema): ").append(mb(TrafficStats.getMobileRxBytes())).append('\n');
      b.append("Enviados rede movel (sistema): ").append(mb(TrafficStats.getMobileTxBytes())).append('\n');
      b.append("Recebidos pelo APK (todas as redes): ").append(mb(TrafficStats.getUidRxBytes(Process.myUid()))).append('\n');
      b.append("Enviados pelo APK (todas as redes): ").append(mb(TrafficStats.getUidTxBytes(Process.myUid()))).append('\n');
    }catch(Throwable e){b.append("Contadores indisponiveis: ").append(e.getClass().getSimpleName()).append('\n');}
    b.append("Esses totais podem reiniciar, ser indisponiveis ou incluir outros aplicativos. ");
    b.append("NAO sao quantidade de GB restante no chip. Saldo de dados exige API autorizada da operadora/modem.");
    return b.toString();
  }
}
