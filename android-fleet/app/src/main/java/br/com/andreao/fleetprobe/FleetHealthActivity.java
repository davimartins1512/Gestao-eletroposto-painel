package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/**
 * Read-only local operations screen. No OEM camera activation, no vehicle
 * controls, no spoofed BMS/12V readings, no network or background recording.
 */
public final class FleetHealthActivity extends Activity {
  private TextView report;
  private SharedPreferences prefs;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    prefs=getSharedPreferences(StoragePolicy.PREFS,MODE_PRIVATE);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(26,22,26,24);
    TextView title=new TextView(this);
    title.setText("ANDREAO Fleet · situação do gravador");
    title.setTextSize(22);
    root.addView(title);
    TextView notice=new TextView(this);
    notice.setText("Somente diagnóstico LOCAL. Não abre câmeras, não liga gravação, não transmite vídeos. "+
      "O gravador OEM ainda é manual, apenas com veículo estacionado em P e freio.");
    notice.setPadding(0,12,0,12);
    root.addView(notice);
    report=new TextView(this);
    report.setTextIsSelectable(true);
    report.setTextSize(14);
    report.setPadding(0,14,0,18);
    root.addView(report);
    button(root,"ATUALIZAR ESTADO E USO DA MEMÓRIA",this::refresh);
    button(root,"ABRIR GALERIA DE CLIPES",()->
      startActivity(new Intent(this,OemRecordingLibraryActivity.class)));
    button(root,"CONTROLE DE DADOS · REDE LOCAL SEM UPLOAD",()->
      startActivity(new Intent(this,FleetDataControlActivity.class)));
    button(root,"AJUSTAR QUALIDADE E LIMITES",()->
      startActivity(new Intent(this,StoragePreferencesActivity.class)));
    button(root,"VER ÚLTIMO RELATÓRIO LOCAL",()->{
      String message=OemSessionJournal.latest(getApplicationContext());
      new AlertDialog.Builder(this).setTitle("Histórico do gravador")
        .setMessage(message).setPositiveButton("Fechar",null).show();
    });
    button(root,"VER FASE DO ÚLTIMO ENSAIO OEM",()->{
      new AlertDialog.Builder(this).setTitle("Último estado OEM")
        .setMessage(OemProbeCheckpoint.read(getApplicationContext()))
        .setPositiveButton("Fechar",null).show();
    });
    scroll.addView(root);
    setContentView(scroll);
    refresh();
  }
  private void button(LinearLayout root,String name,Runnable callback){
    Button b=new Button(this);
    b.setText(name);b.setAllCaps(false);
    b.setOnClickListener(v->callback.run());root.addView(b);
  }
  private void refresh(){
    report.setText("Calculando uso do armazenamento local...");
    new Thread(()->{
      String details;
      try {
        List<OemRecordingLibrary.Entry> recordings=OemRecordingLibrary.list(getApplicationContext());
        long bytes=0,protectedBytes=0;int protectedCount=0;
        for(OemRecordingLibrary.Entry item:recordings){
          bytes+=Math.max(0,item.sizeBytes);
          if(item.protectedEvent){
            protectedBytes+=Math.max(0,item.sizeBytes);protectedCount++;
          }
        }
        StoragePolicy.Candidate selected=StoragePolicy.select(getApplicationContext(),prefs);
        float quota=prefs.getFloat("max_storage_gb",FleetStorageMath.DEFAULT_QUOTA_GB);
        float reserve=prefs.getFloat("reserve_free_gb",2f);
        int retention=(int)prefs.getFloat("retention_hours",24f);
        int segment=(int)prefs.getFloat("segment_seconds",90f);
        float bitrate=prefs.getFloat("bitrate_mbps",4f);
        int fps=(int)prefs.getFloat("fps",20f);
        double forecast=FleetStorageMath.expectedGiB(24,bitrate,false);
        double supported=FleetStorageMath.hoursInQuota(quota,bitrate,false);
        StringBuilder result=new StringBuilder();
        result.append("GRAVAÇÕES FINALIZADAS\n");
        result.append("Arquivos locais: ").append(recordings.size()).append('\n');
        result.append("Uso em pastas do Fleet: ").append(String.format(Locale.US,"%.2f GiB",bytes/FleetStorageMath.GIB)).append('\n');
        result.append("Ocorrências protegidas: ").append(protectedCount)
          .append(" · ").append(String.format(Locale.US,"%.2f GiB",protectedBytes/FleetStorageMath.GIB)).append("\n\n");
        result.append("QUALIDADE E RETENÇÃO CONFIGURADAS\n");
        result.append("Clipes de ").append(segment).append("s, bitrate ").append(bitrate)
          .append(" Mbps, até ").append(fps).append(" FPS\n");
        result.append("Retenção máxima por idade: ").append(retention).append(" horas\n");
        result.append("Teto de uso: ").append(quota>0?quota+" GiB":"SEM TETO — ATENÇÃO") 
          .append("; reserva livre: ").append(reserve).append(" GiB\n");
        result.append("Projeção teórica de 24h: ").append(String.format(Locale.US,"%.1f GiB",forecast)).append('\n');
        result.append("Com o teto, duração aproximada: ")
          .append(quota>0?String.format(Locale.US,"%.1f horas",supported):"limitada pelo armazenamento físico").append('\n');
        result.append("NÃO significa garantia de 24h: ocorrências e espaço livre reduzem o histórico.\n\n");
        result.append("DESTINO E SEGURANÇA\n");
        if(selected==null)result.append("Sem destino seguro para iniciar nova gravação.\n");
        else result.append(selected.name).append(" · livre ")
          .append(String.format(Locale.US,"%.1f GiB",selected.freeBytes/FleetStorageMath.GIB))
          .append('\n')
          .append("Pode iniciar próximo clipe: ")
          .append(OemMediaRetention.enoughSpace(selected.folder,prefs)?"SIM, sujeito ao teste OEM manual":"NÃO, espaço/teto insuficiente").append('\n');
        result.append("Câmera OEM: comprovada em ensaio manual com Dolphin Mini, NÃO integrada a streaming.\n");
        result.append("READY / acionamento automático: NÃO VALIDADO.\n");
        result.append("SOC/SOH BMS e bateria 12V: SEM FONTE AUTENTICADA.\n");
        result.append("Áudio: somente opt-in por sessão; qualidade/sincronia ainda não validadas.\n");
        result.append("Câmera ao vivo remota: NÃO INTEGRADA.\n");
        details=result.toString();
      }catch(Throwable e){details="Diagnóstico indisponível: "+e.getClass().getSimpleName();}
      final String message=details;
      runOnUiThread(()->report.setText(message));
    },"fleet-health-local").start();
  }
}
