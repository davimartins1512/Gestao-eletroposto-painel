package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Local playback + export + protect-by-file. No cloud access and no
 * interaction with the OEM camera; launch while recording is STOPPED.
 */
public final class OemRecordingLibraryActivity extends Activity {
  private LinearLayout listContainer;
  private VideoView player;
  private TextView status;
  private volatile boolean working=false;
  private Spinner interval;
  private volatile List<OemRecordingLibrary.Entry> mostRecent=new ArrayList<>();
  private File playing;

  @Override public void onCreate(Bundle savedInstanceState){
    super.onCreate(savedInstanceState);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(24,16,24,24);
    TextView title=new TextView(this);
    title.setText("ANDREAO Fleet · galeria local de gravações");
    title.setTextSize(22);
    root.addView(title);
    TextView hint=new TextView(this);
    hint.setText("Lista de MP4 já finalizados na memória interna/USB do ANDREAO Fleet. "
      +"Nada é enviado à internet. Proteja ocorrências ou exporte um clipe isolado. "
      +"Reproduza somente com o carro parado; interrompa qualquer teste de câmera antes.");
    hint.setPadding(0,10,0,12);
    root.addView(hint);
    interval=new Spinner(this);
    String[] periods={"Todos os vídeos","Última 1 hora","Últimas 6 horas",
      "Últimas 24 horas","Últimos 7 dias"};
    interval.setAdapter(new ArrayAdapter<String>(this,
      android.R.layout.simple_spinner_dropdown_item,periods));
    root.addView(interval);
    interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
      @Override public void onItemSelected(AdapterView<?> parent,android.view.View view,int pos,long id){
        showEntries(mostRecent);
      }
      @Override public void onNothingSelected(AdapterView<?> parent){}
    });
    Button refresh=button(root,"ATUALIZAR LISTA",()->refresh());
    button(root,"RECUPERAR MP4 TEMPORÁRIOS VÁLIDOS (APENAS PARADO)",()->confirmRecovery());
    button(root,"VER ÚLTIMO RELATÓRIO DE GRAVAÇÃO",()->showLastReport());
    player=new VideoView(this);
    player.setMinimumHeight(260);
    player.setVisibility(android.view.View.GONE);
    root.addView(player,new LinearLayout.LayoutParams(-1,260));
    status=new TextView(this);
    status.setText("Nenhuma reprodução iniciada.");
    status.setPadding(0,12,0,12);
    root.addView(status);
    listContainer=new LinearLayout(this);
    listContainer.setOrientation(LinearLayout.VERTICAL);
    root.addView(listContainer);
    scroll.addView(root);setContentView(scroll);
    refresh();
  }
  private Button button(LinearLayout root,String text,Runnable callback){
    Button b=new Button(this);
    b.setText(text);b.setAllCaps(false);
    b.setOnClickListener(v->callback.run());
    root.addView(b);
    return b;
  }
  private void refresh(){
    if(working)return;
    working=true;
    status.setText("Procurando clipes MP4 finalizados...");
    new Thread(()->{
      List<OemRecordingLibrary.Entry> found=OemRecordingLibrary.list(getApplicationContext());
      runOnUiThread(()->{working=false;mostRecent=found;showEntries(found);});
    },"fleet-library-scan").start();
  }
  private long horizonHours(){
    if(interval==null)return 0;
    switch(interval.getSelectedItemPosition()){
      case 1:return 1;
      case 2:return 6;
      case 3:return 24;
      case 4:return 168;
      default:return 0;
    }
  }
  private void showEntries(List<OemRecordingLibrary.Entry> found){
    listContainer.removeAllViews();
    long hours=horizonHours();
    long start=hours>0?System.currentTimeMillis()-hours*3600000L:0L;
    List<OemRecordingLibrary.Entry> filtered=new ArrayList<>();
    for(OemRecordingLibrary.Entry entry:found)
      if(start<=0L||entry.lastModified>=start)filtered.add(entry);
    status.setText(filtered.isEmpty()?"Nenhum MP4 encontrado no período selecionado.":
      filtered.size()+" clipe(s) no período escolhido, em pastas autorizadas.");
    int limit=Math.min(200,filtered.size());
    for(int i=0;i<limit;i++){
      OemRecordingLibrary.Entry e=filtered.get(i);
      LinearLayout card=new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(10,12,10,12);
      TextView label=new TextView(this);
      String when=new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault())
        .format(new Date(e.lastModified));
      label.setText((e.protectedEvent?"[OCORRÊNCIA PROTEGIDA] ":"")+
         e.title()+"\n"+when+" · "+e.storageLabel+
         " · "+String.format(Locale.US,"%.1f MB",e.sizeBytes/1048576d));
      label.setTextSize(14);card.addView(label);
      button(card,"REPRODUZIR",()->play(e.file.getAbsolutePath()));
      button(card,"EXPORTAR ESTE MP4 PARA GALERIA",()->export(e.file.getAbsolutePath()));
      if(!e.protectedEvent)button(card,"PROTEGER COMO OCORRÊNCIA",()->protect(e.file.getAbsolutePath()));
      listContainer.addView(card);
    }
    if(filtered.size()>limit){
      TextView more=new TextView(this);
      more.setText("Mostrando os 200 mais recentes. Exporte/proteja os necessários antes da rotação.");
      listContainer.addView(more);
    }
  }
  private void showLastReport(){
    if(working)return;
    working=true;
    status.setText("Lendo o histórico privado de sessões concluídas...");
    new Thread(()->{
      final String text=OemSessionJournal.latest(getApplicationContext());
      runOnUiThread(()->{
        working=false;
        new AlertDialog.Builder(this).setTitle("Última sessão de gravação")
          .setMessage(text).setPositiveButton("Fechar",null).show();
      });
    },"fleet-read-last-session").start();
  }
  private void confirmRecovery(){
    if(working)return;
    new AlertDialog.Builder(this).setTitle("Verificar MP4 incompletos após falha?")
      .setMessage("O processo verificará apenas arquivos .mp4.partial antigos do ANDREAO Fleet. "+
        "Só promoverá a MP4 um arquivo com vídeo e duração legíveis. "+
        "Arquivos corrompidos ficarão intactos para análise. "+
        "Use somente após encerrar qualquer gravação.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Verificar e recuperar", (d,w)->recover())
      .show();
  }
  private void recover(){
    if(working)return;
    working=true;
    status.setText("Examinando apenas gravações temporárias do aplicativo...");
    new Thread(()->{
      OemPartialRecovery.Report recovery=OemPartialRecovery.recover(getApplicationContext());
      runOnUiThread(()->{
        working=false;
        new AlertDialog.Builder(this).setTitle("Diagnóstico de recuperação")
          .setMessage(recovery.summary()).setPositiveButton("OK",null).show();
        refresh();
      });
    },"fleet-recover-orphan-mp4").start();
  }
  private File safeFile(String path){
    File f=OemRecordingLibrary.resolve(getApplicationContext(),path);
    if(f==null)status.setText("Arquivo indisponível, removido ou fora do armazenamento permitido.");
    return f;
  }
  private void play(String file){
    File f=safeFile(file);
    if(f==null)return;
    try{
      if(playing!=null)player.stopPlayback();
      playing=f;
      player.setVisibility(android.view.View.VISIBLE);
      MediaController controls=new MediaController(this);
      controls.setAnchorView(player);
      player.setMediaController(controls);
      player.setVideoPath(f.getAbsolutePath());
      player.setOnErrorListener((media,what,extra)->{
        status.setText("O reprodutor deste Android não conseguiu abrir o MP4 ("+what+", "+extra+"). "
          +"Exporte e reproduza em outro dispositivo se necessário.");
        return true;
      });
      player.setOnPreparedListener(media->{
        status.setText("Reproduzindo: "+f.getName());
        player.start();
      });
    }catch(Throwable e){
      status.setText("Falha abrindo vídeo: "+e.getClass().getSimpleName());
    }
  }
  private void export(String file){
    File f=safeFile(file);
    if(f==null||working)return;
    working=true;
    status.setText("Exportando somente "+f.getName()+" para Movies...");
    new Thread(()->{
      String result=OemVideoExporter.copyToMovies(getApplicationContext(),f);
      runOnUiThread(()->{working=false;status.setText(result);});
    },"fleet-export-one-video").start();
  }
  private void protect(String path){
    File file=safeFile(path);
    if(file==null)return;
    new AlertDialog.Builder(this).setTitle("Proteger esta ocorrência?")
      .setMessage("Impedirá que a limpeza automática apague este MP4. "
        +"É necessário revisar periodicamente a finalidade da retenção. "
        +"O arquivo continua na memória do carro ou USB escolhido.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Proteger", (d,w)->{
        File current=safeFile(path);
        if(current==null)return;
        File protectedFile=OemMediaRetention.protect(current);
        status.setText(protectedFile==null?"Não foi possível proteger o clipe.":
          "Clipe preservado: "+protectedFile.getName());
        refresh();
      }).show();
  }
  @Override protected void onPause(){
    super.onPause();
    if(player!=null)player.pause();
  }
  @Override protected void onDestroy(){
    if(player!=null)player.stopPlayback();
    super.onDestroy();
  }
}