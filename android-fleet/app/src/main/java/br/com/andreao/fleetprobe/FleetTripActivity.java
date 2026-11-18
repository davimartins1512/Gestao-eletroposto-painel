package br.com.andreao.fleetprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Local opt-in GPS journey tracking with visible foreground notification. */
public final class FleetTripActivity extends Activity {
  private static final int LOCATION=101,EXPORT=102;
  private int minutes=30;
  private Spinner period;
  private TextView status;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    ScrollView scroll=new ScrollView(this);
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);box.setPadding(30,24,30,24);
    TextView heading=new TextView(this);heading.setText("ANDREAO FLEET · TRAJETOS LOCAIS");
    heading.setTextSize(22);box.addView(heading);
    TextView description=new TextView(this);
    description.setText("O GPS funciona SEM INTERNET. O trajeto fica em CSV dentro do carro, "+
      "com notificação visível e parada manual. Sem rastreamento oculto, "+
      "sem enviar pontos ao painel e sem acionar qualquer comando do carro. "+
      "Só inicie com autorização do responsável/condutor.");
    description.setPadding(0,10,0,20);box.addView(description);
    period=new Spinner(this);
    String[] options={"15 minutos","30 minutos","60 minutos","120 minutos"};
    period.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,options));
    period.setSelection(1);box.addView(period);
    button(box,"INICIAR GPS LOCAL COM NOTIFICAÇÃO",()->{
      minutes=new int[]{15,30,60,120}[period.getSelectedItemPosition()];
      new AlertDialog.Builder(this).setTitle("Registrar trajeto local?")
        .setMessage("O GPS e sensores serão registrados em arquivo CSV por até "+minutes+
          " minutos ou até você tocar PARAR. O serviço mostra notificação. "+
          "Não registra áudio nem vídeo e não precisa de internet.")
        .setNegativeButton("Cancelar",null)
        .setPositiveButton("Autorizar", (d,w)->startWithPermission()).show();
    });
    button(box,"PARAR GPS LOCAL",()->{
      startService(new Intent(this,TrackerService.class).setAction(TrackerService.ACTION_STOP));
      status.setText("Solicitada parada. Atualize o estado.");
    });
    button(box,"VER ESTADO E PONTOS DO ÚLTIMO TRAJETO",this::refresh);
    button(box,"EXPORTAR ÚLTIMO CSV (SEM INTERNET)",this::export);
    status=new TextView(this);status.setTextIsSelectable(true);status.setTextSize(14);
    status.setPadding(0,18,0,18);box.addView(status);
    scroll.addView(box);setContentView(scroll);
    refresh();
  }
  private void button(LinearLayout box,String label,Runnable action){
    Button b=new Button(this);b.setAllCaps(false);b.setText(label);
    b.setOnClickListener(v->action.run());box.addView(b);
  }
  private void startWithPermission(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
       checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
      start();
    }else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION);
  }
  private void start(){
    Intent intent=new Intent(this,TrackerService.class)
      .setAction(TrackerService.ACTION_START)
      .putExtra(TrackerService.EXTRA_MAX_MINUTES,minutes);
    try{
      if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);
      else startService(intent);
      status.setText("Coleta GPS local solicitada. Confira a notificação e atualize o estado.");
    }catch(Throwable e){status.setText("Não iniciou GPS: "+e.getClass().getSimpleName());}
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==LOCATION){
      boolean granted=false;
      for(int value:results)if(value==PackageManager.PERMISSION_GRANTED)granted=true;
      if(granted)start();
      else status.setText("GPS desativado: permissão não concedida.");
    }
  }
  private File lastCsv(){
    SharedPreferences p=getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE);
    String path=p.getString(TrackerService.LAST_FILE,"");
    if(path.isEmpty())return null;
    try{
      File f=new File(path);
      File ext=getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS);
      File local=new File(getFilesDir(),"tracks");
      String canonical=f.getCanonicalPath();
      boolean allowed=(ext!=null&&canonical.startsWith(ext.getCanonicalPath()+File.separator)) ||
        canonical.startsWith(local.getCanonicalPath()+File.separator);
      return allowed&&f.isFile()&&f.getName().startsWith("andreao_fleet_gps_")&&
        f.getName().endsWith(".csv")?f:null;
    }catch(Exception e){return null;}
  }
  private void refresh(){
    SharedPreferences p=getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE);
    File latest=lastCsv();
    status.setText("Sessão: "+p.getString(TrackerService.LAST_STATUS,"Nenhuma")+
      "\nColeta ativa (último estado): "+(p.getBoolean(TrackerService.RUNNING,false)?"SIM":"NÃO")+
      "\nPontos GPS da última sessão: "+p.getInt(TrackerService.LAST_COUNT,0)+
      "\nCSV local: "+(latest==null?"Nenhum":latest.getName())+
      "\nTamanho: "+(latest==null?"0":String.format(Locale.US,"%.1f KB",latest.length()/1024d))+
      "\nSem upload, sem GPS remoto. A falta de sinal GPS não pode ser mascarada.");
  }
  private void export(){
    File src=lastCsv();
    if(src==null){status.setText("Não existe CSV local para exportar.");return;}
    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/csv");
    intent.putExtra(Intent.EXTRA_TITLE,src.getName());
    startActivityForResult(intent,EXPORT);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
    File input=lastCsv();
    if(input==null){status.setText("Trajeto CSV não encontrado.");return;}
    final android.net.Uri uri=data.getData();
    new Thread(()->{
      String resultText;
      try(FileInputStream in=new FileInputStream(input);
          OutputStream out=getContentResolver().openOutputStream(uri)){
        if(out==null)throw new java.io.IOException("Destino indisponível");
        byte[] chunk=new byte[32768];int n;
        while((n=in.read(chunk))!=-1)out.write(chunk,0,n);
        out.flush();
        resultText="Trajeto CSV exportado somente para o local escolhido.";
      }catch(Exception e){resultText="Exportação falhou: "+e.getClass().getSimpleName();}
      final String message=resultText;
      runOnUiThread(()->status.setText(message));
    },"fleet-export-gps").start();
  }
}
