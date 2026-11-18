package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
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
  private volatile FleetLanTransferServer lan;
  private TextView linkLabel;
  private CheckBox hotspotConsent;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Runnable monitor=new Runnable(){
    @Override public void run(){
      FleetLanTransferServer server=lan;
      if(linkLabel!=null&&server!=null){
        String link=server.url();
        linkLabel.setText(server.status()+(link.isEmpty()?"":"\n\nENDEREÇO LOCAL:\n"+link)
          +"\nBytes servidos pelo Wi-Fi: "+server.bytesSent());
      }
      if(server!=null&&server.active())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      handler.postDelayed(this,1000);
    }
  };
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
      "Nenhum vídeo, áudio, rota ou relatório sai automaticamente.\n"+
      "A permissão INTERNET existe APENAS para abrir um servidor local por tempo limitado no Wi-Fi autorizado. "+
      "Não existe cliente de upload para a nuvem, nem envio em segundo plano.");
    intro.setPadding(0,16,0,18);box.addView(intro);
    selection=new TextView(this);
    selection.setTextIsSelectable(true);
    selection.setPadding(0,6,0,16);
    box.addView(selection);
    showSelection();
    button(box,"EXPORTAR CÓPIA DO VÍDEO ESCOLHIDO (OFFLINE)",this::exportSelected);
    hotspotConsent=new CheckBox(this);
    hotspotConsent.setChecked(false);
    hotspotConsent.setText("Confirmo que o carro está conectado ao MEU roteador privado "+
      "protegido por senha, e autorizo baixar somente este MP4 no telefone.");
    box.addView(hotspotConsent);
    button(box,"DISPONIBILIZAR MP4 NO WI-FI DO CELULAR (10 MIN)",this::startLan);
    button(box,"COPIAR ENDEREÇO LOCAL",this::copyAddress);
    button(box,"ENCERRAR COMPARTILHAMENTO",this::stopLan);
    linkLabel=new TextView(this);
    linkLabel.setTextIsSelectable(true);
    linkLabel.setText("Servidor local desligado. Não existe upload automático.");
    linkLabel.setPadding(0,12,0,20);
    box.addView(linkLabel);
    status=new TextView(this);status.setTextIsSelectable(true);status.setTextSize(14);
    status.setPadding(0,10,0,16);box.addView(status);
    button(box,"ATUALIZAR CONEXÃO E CONSUMO DO PRÓPRIO APP",this::refresh);
    button(box,"ABRIR CONFIGURAÇÕES WI-FI DO CARRO",()->{
      try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
      catch(Throwable e){status.setText("Esta multimídia não abriu as configurações Wi-Fi.");}
    });
    button(box,"ABRIR GALERIA E ESCOLHER UM MP4 PARA EXPORTAR",()->
      startActivity(new Intent(this,OemRecordingLibraryActivity.class)));
    button(box,"COMO BAIXAR NO TELEFONE SEM USAR O CHIP BYD",()->
      new AlertDialog.Builder(this)
      .setTitle("Download direto: Wi-Fi local")
      .setMessage("1. Ligue o roteador do seu celular e conecte o Wi-Fi do carro a ele.\n"+
        "2. Na galeria do Fleet, escolha UM vídeo e toque PREPARAR PARA CELULAR.\n"+
        "3. Marque a autorização e toque DISPONIBILIZAR MP4.\n"+
        "4. Abra no navegador do próprio telefone o endereço que aparecer na tela do carro.\n"+
        "5. O telefone baixa o MP4 diretamente pela rede local, sem enviar ao servidor.\n\n"+
        "O link dura até 10 minutos e aceita uma transferência. NÃO é HTTPS: "+
        "use somente seu hotspot protegido por senha, jamais Wi-Fi público. "+
        "A conexão depende do telefone permitir contato entre hotspot e carro. "+
        "Nenhuma transferência começa sem sua ação.")
      .setPositiveButton("Entendi",null).show());
    TextView note=new TextView(this);
    note.setText("Transporte local direto para o navegador do celular: só por solicitação, "+
      "sem tráfego para servidores externos. Sem HTTPS; use rede privada e nunca compartilhe "+
      "o link publicamente. O upload posterior feito pelo celular pode consumir a franquia DO TELEFONE. "+
      "Conectar no Wi-Fi sozinho não inicia gravação nem transferência.");
    note.setPadding(0,20,0,12);box.addView(note);
    scroll.addView(box);setContentView(scroll);
    refresh();
    handler.post(monitor);
  }
  private void button(LinearLayout box,String caption,Runnable callback){
    Button b=new Button(this);
    b.setText(caption);b.setAllCaps(false);
    b.setOnClickListener(v->callback.run());box.addView(b);
  }
  private void startLan(){
    File file=selected();
    if(file==null){
      new AlertDialog.Builder(this).setMessage("Selecione um MP4 da galeria primeiro.")
        .setPositiveButton("OK",null).show();
      return;
    }
    if(!hotspotConsent.isChecked()){
      new AlertDialog.Builder(this).setMessage("Confirme o roteador PRIVADO e com senha. "+
        "O arquivo é disponibilizado em HTTP somente na rede local.")
        .setPositiveButton("OK",null).show();
      return;
    }
    stopLan();
    try{
      lan=FleetLanTransferServer.create(getApplicationContext(),file);
      lan.start();
      linkLabel.setText("Abrindo compartilhamento temporário na interface Wi-Fi...");
    }catch(Exception e){
      linkLabel.setText("Falha iniciando compartilhamento: "+e.getMessage());
    }
  }
  private void copyAddress(){
    String address=lan==null?"":lan.url();
    if(address.isEmpty()){
      linkLabel.setText("Ainda não há link. Conecte ao hotspot e inicie o compartilhamento.");
      return;
    }
    ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
    if(clipboard!=null){
      clipboard.setPrimaryClip(ClipData.newPlainText("Link local do Fleet",address));
      linkLabel.setText("Link copiado na multimídia. Digite-o no navegador "+
        "do celular conectado à rede do carro:\n"+address);
    }
  }
  private void stopLan(){
    FleetLanTransferServer server=lan;
    if(server!=null)server.stop();
    lan=null;
    if(linkLabel!=null)linkLabel.setText("Compartilhamento local encerrado.");
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  @Override protected void onDestroy(){
    stopLan();
    handler.removeCallbacks(monitor);
    super.onDestroy();
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
      "\nA cópia pode ser exportada offline ou disponibilizada ao telefone via Wi-Fi LOCAL por solicitação.");
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
          .setMessage(result+"\n\nPara baixar diretamente no telefone, use a opção de download Wi-Fi local nesta tela.")
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
