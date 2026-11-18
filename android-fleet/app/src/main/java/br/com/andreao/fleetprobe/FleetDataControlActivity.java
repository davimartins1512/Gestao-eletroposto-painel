package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.File;
import java.util.Locale;
import android.widget.TextView;

/**
 * Data control dashboard. This is intentionally READ-ONLY wrt network traffic:
 * no INTERNET permission, transfer clients, timers, remote uploads or phone IDs.
 *
 * A verified Wi-Fi hotspot can be used later by an authenticated transfer client
 * only after explicit approval of each clip; Wi-Fi alone does not prove a
 * phone hotspot or that its data is free. The BYD app is out of our control.
 */
public final class FleetDataControlActivity extends Activity {
  private TextView status;
  private TextView selection;
  private volatile boolean exporting=false;
  @Override public void onCreate(Bundle savedInstanceState){
    super.onCreate(savedInstanceState);
    ScrollView scroll=new ScrollView(this);
    LinearLayout box=new LinearLayout(this);
    box.setPadding(28,22,28,22);
    box.setOrientation(LinearLayout.VERTICAL);
    TextView title=new TextView(this);
    title.setText("ANDREAO Fleet · controle de dados");
    title.setTextSize(22);box.addView(title);
    TextView intro=new TextView(this);
    intro.setText("REGRA PRINCIPAL: gravações, histórico e GPS local não exigem internet.\n"+
      "Dados celulares da multimídia para este app: BLOQUEADOS por projeto "+
      "(APK sem permissão INTERNET).\n"+
      "Nenhum vídeo, áudio, rota ou relatório é enviado automaticamente.");
    intro.setPadding(0,16,0,18);box.addView(intro);
    selection=new TextView(this);
    selection.setTextIsSelectable(true);
    selection.setPadding(0,6,0,16);
    box.addView(selection);
    showSelection();
    button(box,"EXPORTAR CÓPIA DO VÍDEO ESCOLHIDO (OFFLINE)",this::exportSelected);
    status=new TextView(this);status.setTextIsSelectable(true);status.setTextSize(14);
    status.setPadding(0,10,0,16);box.addView(status);
    button(box,"ATUALIZAR CONEXÃO E CONSUMO DO PRÓPRIO APP",this::refresh);
    button(box,"ABRIR CONFIGURAÇÕES WI-FI DO CARRO",()->{
      try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
      catch(Throwable e){status.setText("Esta multimídia não abriu as configurações Wi-Fi.");}
    });
    button(box,"ABRIR GALERIA E ESCOLHER UM MP4 PARA EXPORTAR",()->
      startActivity(new Intent(this,OemRecordingLibraryActivity.class)));
    button(box,"ENTENDER COMO TRANSFERIR SEM O CHIP DO CARRO",()->
      new AlertDialog.Builder(this)
        .setTitle("Transferir vídeo sem consumir a franquia BYD")
        .setMessage("1. O vídeo permanece na memória do carro.\n"+
          "2. Selecione apenas o clipe necessário e exporte uma CÓPIA para a galeria.\n"+
          "3. Transfira a cópia ao telefone por USB ou outro meio local compatível; "+
          "o envio para a nuvem pode ser feito NO PRÓPRIO TELEFONE.\n\n"+
          "Roteador do telefone: conectá-lo ao carro cria uma rede Wi-Fi, mas "+
          "isso NÃO transfere arquivos por si só. Um futuro envio direto "+
          "do carro precisará de cliente HTTPS autenticado que obrigue "+
          "a conexão Wi-Fi e interrompa se cair para celular. Ainda NÃO foi implementado.\n\n"+
          "Atenção: a franquia do telefone também pode ser limitada. "+
          "Nosso app não mede nem controla dados de outros apps ou da BYD.")
        .setPositiveButton("Entendi",null).show());
    TextView note=new TextView(this);
    note.setText("Para o envio futuro: aprovação explícita por vídeo, Wi-Fi autenticado, "+
      "orçamento de bytes, limite mensal, pausa imediata se o Wi-Fi cair, "+
      "nenhum upload automático. Exibição de Wi-Fi NÃO é confirmação de "+
      "roteador do telefone.");
    note.setPadding(0,20,0,12);box.addView(note);
    scroll.addView(box);setContentView(scroll);
    refresh();
  }
  private void button(LinearLayout box,String caption,Runnable callback){
    Button b=new Button(this);
    b.setText(caption);b.setAllCaps(false);
    b.setOnClickListener(v->callback.run());box.addView(b);
  }
  private File selected(){
    String path=getIntent().getStringExtra("selected_local_clip");
    return OemRecordingLibrary.resolve(getApplicationContext(),path);
  }
  private void showSelection(){
    File clip=selected();
    selection.setText(clip==null?
      "Nenhum vídeo selecionado. Abra a galeria para escolher um clipe específico.":
      "CLIP SELECIONADO: "+clip.getName()+
      "\nTamanho da cópia: "+String.format(Locale.US,"%.1f MB",clip.length()/1000000d)+
      "\nExportar é uma CÓPIA LOCAL; não transfere ao telefone nem usa dados de rede.");
  }
  private void exportSelected(){
    File f=selected();
    if(f==null){new AlertDialog.Builder(this).setMessage("Selecione um vídeo no botão 21.").setPositiveButton("OK",null).show();return;}
    if(exporting)return;
    exporting=true;
    selection.setText("Copiando o MP4 selecionado para a galeria local... sem usar internet.");
    new Thread(()->{
      String result=OemVideoExporter.copyToMovies(getApplicationContext(),f);
      runOnUiThread(()->{
        exporting=false;
        showSelection();
        new AlertDialog.Builder(this).setTitle("Exportação offline")
          .setMessage(result+"\n\nO envio ao celular ainda precisa de uma transferência local "+
            "ou de um cliente autenticado Wi-Fi no futuro; conectar ao hotspot sozinho NÃO envia.")
          .setPositiveButton("Fechar",null).show();
      });
    },"fleet-export-phone-clip").start();
  }
  private void refresh(){
    status.setText("Lendo apenas os indicadores de rede do Android...");
    new Thread(()->{
      FleetNetworkStatus.Status snapshot=FleetNetworkStatus.read(getApplicationContext());
      runOnUiThread(()->status.setText(snapshot.describe()));
    },"fleet-data-readonly").start();
  }
  @Override protected void onResume(){
    super.onResume();
    if(status!=null)refresh();
  }
}
