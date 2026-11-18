package br.com.andreao.fleetprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One consolidated, read-only acceptance report. Reuses known successful
 * files and diagnostics; never opens the OEM camera, toggles USB, contacts
 * servers or requests privileged vehicle data. Explicitly distinguishes
 * software contracts from in-car hardware proof.
 */
public final class FleetAcceptanceActivity extends Activity {
  private static final int SAVE=810;
  private TextView report;
  private Button run;
  private volatile String lastReport="";
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(30,25,30,25);
    TextView heading=new TextView(this);
    heading.setText("ANDREAO FLEET · VERIFICAÇÃO GERAL");
    heading.setTextSize(22);root.addView(heading);
    TextView instructions=new TextView(this);
    instructions.setText("UMA ÚNICA VERIFICAÇÃO AUTOMÁTICA: galeria, mídia MP4, destino "+
      "de armazenamento, retenção, perfil, rede local, histórico GPS e "+
      "capacidade documentada. Não exige tirar pendrive, reiniciar câmera "+
      "nem repetir ensaios comprovados. O relatório separa OK, ALERTA e "+
      "NÃO INTEGRADO com fontes reais.");
    instructions.setPadding(0,12,0,18);root.addView(instructions);
    run=button(root,"EXECUTAR VERIFICAÇÃO COMPLETA",this::execute);
    button(root,"COPIAR RELATÓRIO",this::copy);
    button(root,"SALVAR UM RELATÓRIO TXT",this::save);
    report=new TextView(this);
    report.setTextIsSelectable(true);report.setTextSize(14);
    report.setPadding(0,15,0,24);root.addView(report);
    scroll.addView(root);setContentView(scroll);
    execute();
  }
  private Button button(LinearLayout root,String label,Runnable callback){
    Button b=new Button(this);b.setAllCaps(false);b.setText(label);
    b.setOnClickListener(v->callback.run());root.addView(b);return b;
  }
  private void execute(){
    run.setEnabled(false);
    report.setText("Reunindo dados locais do app, sem ativar câmera nem acessar internet...");
    new Thread(()->{
      String result;
      try{result=collect();}
      catch(Throwable e){result="Verificação interrompida: "+e.getClass().getSimpleName()+
        "\nNenhum comando foi enviado ao veículo.";}
      final String finalReport=result;
      runOnUiThread(()->{
        lastReport=finalReport;
        report.setText(finalReport);
        run.setEnabled(true);
      });
    },"fleet-acceptance-one-report").start();
  }
  private String collect(){
    Context context=getApplicationContext();
    SharedPreferences prefs=getSharedPreferences(StoragePolicy.PREFS,MODE_PRIVATE);
    SharedPreferences tracks=getSharedPreferences(TrackerService.PREFS,MODE_PRIVATE);
    SharedPreferences local=getSharedPreferences(FleetProfileActivity.PREFS,MODE_PRIVATE);
    StringBuilder r=new StringBuilder(14000);
    r.append("ANDREAO FLEET · RELATÓRIO GERAL\n");
    r.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date())).append("\n");
    r.append("Veículo local: ").append(local.getString("vehicle_alias","Não identificado")).append("\n");
    r.append("Modelo cadastrado: ").append(local.getInt("model_index",0)).append("\n");
    r.append("Sem conexão de servidor / sem envio automático.\n\n");
    r.append("1. ARMAZENAMENTO E USB (SEM RETIRAR VOLUME)\n");
    int usb=0,writable=0;
    List<StoragePolicy.Candidate> volumes=StoragePolicy.inventory(context,true);
    for(StoragePolicy.Candidate candidate:volumes){
      if(candidate.removable)usb++;
      if(candidate.writable())writable++;
      r.append(candidate.name).append(": ")
        .append(candidate.writable()?"DISPONÍVEL":"INDISPONÍVEL")
        .append(" · livre ").append(String.format(Locale.US,"%.2f GiB",
          candidate.freeBytes/FleetStorageMath.GIB));
      if(candidate.error!=null)r.append(" · ").append(candidate.error);
      r.append('\n');
    }
    StoragePolicy.Candidate selected=StoragePolicy.select(context,prefs);
    r.append("Destino escolhido automaticamente: ")
      .append(selected==null?"NENHUM (não iniciar câmera)":selected.name).append('\n');
    r.append("USB presente nos diretórios deste app: ").append(usb).append(" volume(s)\n");
    r.append("Pontos de armazenamento graváveis: ").append(writable).append('\n');
    r.append("Política de mudança USB: seleciona novamente em cada rotação de clipe. "+
      "Não garante gravação em qualquer pendrive não reconhecido pelo Android.\n\n");
    r.append("2. CLIPES MP4 JÁ EXISTENTES (NÃO REPETE GRAVAÇÃO)\n");
    List<OemRecordingLibrary.Entry> items=OemRecordingLibrary.list(context);
    int protectedCount=0,invalid=0,checked=0,valid=0;
    long total=0L,protectedBytes=0L,durations=0L;
    for(OemRecordingLibrary.Entry item:items){
      total+=item.sizeBytes;
      if(item.protectedEvent){protectedCount++;protectedBytes+=item.sizeBytes;}
      if(checked<12){
        OemMp4Verifier.Result check=OemMp4Verifier.verify(item.file,false);
        checked++;
        if(check.valid){valid++;durations+=check.durationMs;}
        else invalid++;
      }
    }
    r.append("Clipes finalizados: ").append(items.size()).append('\n');
    r.append("Verificados por metadados Android (máx. 12): ").append(checked)
      .append(" · válidos ").append(valid).append(" · inconsistentes ").append(invalid).append('\n');
    r.append("Duração acumulada dos examinados: ")
      .append(String.format(Locale.US,"%.1f min",durations/60000d)).append('\n');
    r.append("Ocupação MP4: ").append(String.format(Locale.US,"%.2f GiB",total/FleetStorageMath.GIB))
      .append(" · protegidos ").append(protectedCount).append(" / ")
      .append(String.format(Locale.US,"%.2f GiB",protectedBytes/FleetStorageMath.GIB)).append('\n');
    r.append("Resultado: ").append(items.isEmpty()?"SEM MP4 SALVO (não é erro de câmera)":
      invalid==0?"ARQUIVOS EXAMINADOS LEGÍVEIS":"ALERTA: revisar clipes inconsistentes").append('\n');
    r.append("A reprodução e qualidade visual real dependem de avaliação do condutor/proprietário.\n\n");
    r.append("3. RETENÇÃO E COMPACTAÇÃO\n");
    float quota=prefs.getFloat("max_storage_gb",FleetStorageMath.DEFAULT_QUOTA_GB);
    float reserve=prefs.getFloat("reserve_free_gb",2f);
    float bitrate=prefs.getFloat("bitrate_mbps",4f);
    float period=prefs.getFloat("retention_hours",24f);
    float seconds=prefs.getFloat("segment_seconds",90f);
    r.append("MP4: ").append(seconds).append(" s/segmento · ")
      .append(bitrate).append(" Mbps · teto ").append(quota).append(" GiB\n");
    r.append("Tempo limite por idade: ").append(period)
      .append(" h · espaço preservado ").append(reserve).append(" GiB\n");
    r.append("Projeção 24h: ").append(String.format(Locale.US,"%.1f GiB",
      FleetStorageMath.expectedGiB(24,bitrate,false))).append('\n');
    r.append("Histórico teórico no teto: ").append(String.format(Locale.US,"%.1f h",
      FleetStorageMath.hoursInQuota(quota,bitrate,false))).append('\n');
    r.append("Proteção de ocorrência é prioritária; se ocupar quota, novas gravações são bloqueadas.\n");
    r.append("Espaço adequado à próxima gravação: ")
      .append(selected!=null&&OemMediaRetention.enoughSpace(selected.folder,prefs)?"SIM":"NÃO")
      .append("\n\n");
    r.append("4. GPS E HISTÓRICO LOCAL\n");
    r.append("Última sessão: ").append(tracks.getString(TrackerService.LAST_STATUS,"Não há registro")).append('\n');
    r.append("Pontos registrados: ").append(tracks.getInt(TrackerService.LAST_COUNT,0)).append('\n');
    r.append("GPS só inicia mediante autorização, com notificação e arquivo CSV local.\n\n");
    r.append("5. REDE E FRANQUIA BYD\n");
    r.append(FleetNetworkStatus.read(context).describe()).append('\n');
    r.append("Vídeo só é disponibilizado manualmente por 10 minutos na rede Wi-Fi "+
      "privada, com link aleatório de um único arquivo. "+
      "Nenhuma conexão de saída/cloud foi implementada.\n\n");
    r.append("6. PROCESSO CÂMERA ORIGINAL\n");
    String phase=OemProbeCheckpoint.read(context);
    r.append(phase.length()>1400?phase.substring(0,1400):phase).append('\n');
    r.append("A câmera OEM não é aberta durante esta verificação.\n\n");
    r.append("7. MATRIZ DE CAPACIDADES E RESTRIÇÕES\n");
    r.append("[APLICATIVO] Arquivos MP4, galeria, retenção, exportação e proteção: IMPLEMENTADOS\n");
    r.append("[APLICATIVO] GPS e sensores locais sob autorização: IMPLEMENTADOS\n");
    r.append("[APLICATIVO] MP4 por hotspot privado Wi-Fi: IMPLEMENTADO, requer confirmação no aparelho\n");
    r.append("[APLICATIVO] Configuração local e perfil do veículo: IMPLEMENTADOS\n");
    r.append("[HARDWARE] Gravação OEM manual estacionária: validada anteriormente no Dolphin Mini\n");
    r.append("[NÃO INTEGRADO] READY, P e freio por fonte autenticada / gravação ao dirigir\n");
    r.append("[NÃO INTEGRADO] BMS, saúde de tração SOC/SOH e tensão 12V real\n");
    r.append("[NÃO INTEGRADO] Gravação sentinela com carro desligado / proteção da bateria 12V\n");
    r.append("[NÃO INTEGRADO] Câmera ao vivo pela nuvem e dados sincronizados com painel web\n");
    r.append("[NÃO VERIFICADO] Convivência com câmera de ré durante manobra\n");
    r.append("\nEsta verificação prova apenas estados e arquivos disponíveis agora. "+
      "Não é validação de 24 horas, uso rodando ou segurança do sistema OEM.\n");
    return r.toString();
  }
  private void copy(){
    if(lastReport.isEmpty())return;
    ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
    if(cb!=null){
      cb.setPrimaryClip(ClipData.newPlainText("ANDREAO Fleet · relatório geral",lastReport));
      report.setText(lastReport+"\n\nRELATÓRIO COPIADO.");
    }
  }
  private void save(){
    if(lastReport.isEmpty())return;
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("text/plain");
    i.putExtra(Intent.EXTRA_TITLE,"andreao_fleet_relatorio_geral.txt");
    startActivityForResult(i,SAVE);
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(req!=SAVE||result!=RESULT_OK||data==null||data.getData()==null)return;
    try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
      if(out==null)throw new java.io.IOException("Sem destino");
      out.write(lastReport.getBytes(StandardCharsets.UTF_8));
      out.flush();
      report.setText(lastReport+"\n\nTXT salvo no local escolhido.");
    }catch(Exception ex){
      report.setText(lastReport+"\n\nFalha salvando TXT: "+ex.getClass().getSimpleName());
    }
  }
}
