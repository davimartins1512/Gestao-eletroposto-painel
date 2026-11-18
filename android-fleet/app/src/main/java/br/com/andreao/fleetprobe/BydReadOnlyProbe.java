package br.com.andreao.fleetprobe;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Consulta passiva, apenas de leitura, de um provedor conhecido do DiLink.
 * A documentacao comunitaria confirmou consulta por shell em OUTRO firmware.
 * Acesso por APK comum e compatibilidade Dolphin Mini sao DESCONHECIDOS.
 * NUNCA escreve no provedor, nem consulta CAN/BMS.
 */
public final class BydReadOnlyProbe {
  private BydReadOnlyProbe() {}
  private static final Uri CAR_STATUS=Uri.parse("content://com.byd.carStatusProvider/car_status");
  // Valores a exportar: apenas resumo de manutencao, ocorrencias e tamanho de
  // serie eletrica. Nada de IDs do dispositivo, nomes, rotas ou consumo bruto.
  private static final String[] ALLOWED={
    "car_status_maintenance_mile","car_status_maintenance_time",
    "car_status_issue_num","travel_points_elec"
  };
  public static String snapshot(Context context) {
    StringBuilder b=new StringBuilder("Provedor BYD CarStatus (SOMENTE LEITURA).\n");
    b.append("Consulta de pesquisa; acesso por APK normal ainda nao validado no Dolphin Mini.\n");
    try (Cursor c=context.getContentResolver().query(CAR_STATUS,null,null,null,null)) {
      if(c==null)return b.append("Consulta retornou null; indisponivel ou bloqueado.").toString();
      b.append("Linhas retornadas: ").append(c.getCount()).append("\n");
      if(!c.moveToFirst())return b.append("Sem linhas; nao prova que os dados nao existam.").toString();
      for(String key:ALLOWED){
        int index=c.getColumnIndex(key);
        if(index<0){b.append(key).append(": coluna ausente\n");continue;}
        if(c.isNull(index)){b.append(key).append(": sem valor\n");continue;}
        if(key.equals("travel_points_elec")){
          String value=c.getString(index);
          if(value==null || value.isEmpty())b.append(key).append(": vazio\n");
          else{
            int segments=1;
            for(int i=0;i<value.length()&&i<100000;i++)if(value.charAt(i)=='#')segments++;
            b.append(key).append(": serie encontrada, aprox. ").append(segments)
              .append(" campos; valor bruto NAO exportado. Sem conversao para kWh.\n");
          }
        } else {
          String value=c.getString(index);
          if(value==null)value="";
          // Protege contra campos OEM inesperados com dados extensos.
          if(!value.matches("[0-9-]{1,15}")){
            b.append(key).append(": valor nao numerico, ocultado\n");
          }else b.append(key).append(": ").append(value).append("\n");
        }
      }
    } catch(SecurityException denied){
      b.append("ACESSO NEGADO pelo Android/firmware. Nao foi tentada elevacao de privilegios.");
    } catch(Throwable error){
      b.append("Falha nao invasiva: ").append(error.getClass().getSimpleName());
    }
    b.append("\nEste provedor NAO confirma SOC ou SOH de tracao, nem tensao da bateria 12V.");
    return b.toString();
  }
}
