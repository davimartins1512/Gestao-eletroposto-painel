package br.com.andreao.fleetprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
/** Optional local UI; never streams data or changes vehicle state. */
public final class FleetTelemetryActivity extends Activity {
  private TextView output;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    LinearLayout layout=new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(24,24,24,24);
    TextView title=new TextView(this);title.setText("ANDREAO Fleet · Dados BYD");title.setTextSize(23);layout.addView(title);
    Button refresh=new Button(this);refresh.setText("ATUALIZAR LEITURA SOMENTE LEITURA");layout.addView(refresh);
    output=new TextView(this);output.setTextIsSelectable(true);output.setTextSize(16);layout.addView(output);
    refresh.setOnClickListener(v->load());
    ScrollView sc=new ScrollView(this);sc.addView(layout);setContentView(sc);
    load();
  }
  private void load(){
    output.setText("Consultando somente APIs disponíveis, sem gravar, transmitir ou comandar o carro...");
    new Thread(()->{
      String report;
      try{report=FleetTelemetryReadOnly.inspect(getApplicationContext()).report();}
      catch(Throwable e){report="Leitura indisponível: "+e.getClass().getSimpleName();}
      final String result=report;
      runOnUiThread(()->output.setText(result));
    },"fleet-oem-readonly").start();
  }
}