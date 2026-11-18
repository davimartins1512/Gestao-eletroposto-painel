package br.com.andreao.fleetprobe;
import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
/** BYD OEM read-only telemetry. No ADB, root, hidden-API bypass or vehicle control. */
public final class FleetTelemetryReadOnly {
  private FleetTelemetryReadOnly(){}
  private static final String BASE="android.hardware.bydauto.";
  public static final class Snapshot {
    public Double soc, soh, rangeKm, odometerKm, speedKmh, voltage12v, hvVoltage, hvCurrent;
    public Integer gear;
    public final String source="BYD DiLink HAL (leitura passiva, se permitida)";
    private static String value(Double x,String unit){return x==null?"indisponível":String.format(Locale.US,"%.2f %s",x,unit);}
    public String report(){
      return "FLEET · TELEMETRIA SOMENTE LEITURA\nFonte: "+source+
        "\nSOC tração: "+value(soc,"%")+
        "\nSOH tração (FID OEM): "+value(soh,"%")+
        "\nBateria auxiliar 12V: "+value(voltage12v,"V")+
        "\nAutonomia EV: "+value(rangeKm,"km")+
        "\nOdômetro: "+value(odometerKm,"km")+
        "\nVelocidade: "+value(speedKmh,"km/h")+
        "\nTensão tração: "+value(hvVoltage,"V")+
        "\nCorrente tração: "+value(hvCurrent,"A")+
        "\nMarcha: "+(gear==null?"indisponível":gear==1?"P":gear==2?"R":gear==3?"N":gear==4?"D":"código "+gear)+
        "\nAs leituras podem ser negadas pelo firmware/assinatura BYD. Sem valores inventados."+
        "\nNão usar para desbloqueio de gravação em movimento ou modo sentinela.\n";
    }
  }
  public static Snapshot inspect(Context ctx){
    Snapshot s=new Snapshot();
    Object statistic=instance(ctx,BASE+"statistic.BYDAutoStatisticDevice");
    s.soc=range(call(statistic,"getElecPercentageValue"),0,100);
    s.rangeKm=range(call(statistic,"getElecDrivingRangeValue"),0,2000);
    s.odometerKm=range(call(statistic,"getTotalMileageValue"),0,2000000);
    s.speedKmh=range(call(instance(ctx,BASE+"speed.BYDAutoSpeedDevice"),"getCurrentSpeed"),0,300);
    Double g=range(call(instance(ctx,BASE+"gearbox.BYDAutoGearboxDevice"),"getGearboxAutoModeType"),1,15);
    if(g!=null)s.gear=g.intValue();
    Object manager=instance(ctx,BASE+"BYDAutoDeviceManager");
    s.soh=range(fid(manager,"Statistic","STATISTIC_BATTERY_HEALTHY_INDEX",1014,false),1,100);
    s.voltage12v=range(fid(manager,"Ota","OTA_BATTERY_POWER_VOLTAGE",1001,true),6,20);
    s.hvVoltage=range(fid(manager,"Charging","CHARGING_CHARGE_BATTERY_VOLT",1009,false),50,1000);
    s.hvCurrent=range(fid(manager,"Charging","CHARGING_CHARGE_CURRENT",1009,true),-1000,1000);
    return s;
  }
  private static Double range(Object v,double lo,double hi){
    if(!(v instanceof Number))return null;
    double n=((Number)v).doubleValue();
    return Double.isFinite(n)&&n>=lo&&n<=hi?n:null;
  }
  private static Object instance(Context ctx,String name){
    try{
      Class<?> c=Class.forName(name);
      for(Method m:c.getMethods()){
        if(!m.getName().equals("getInstance")||!Modifier.isStatic(m.getModifiers()))continue;
        Class<?>[] p=m.getParameterTypes();
        if(p.length==1&&p[0]==Context.class)return m.invoke(null,ctx);
        if(p.length==0)return m.invoke(null);
      }
    }catch(Throwable denied){}
    return null;
  }
  private static Object call(Object o,String method,Object... args){
    if(o==null)return null;
    try{
      for(Method m:o.getClass().getMethods())
        if(m.getName().equals(method)&&m.getParameterTypes().length==args.length)
          return m.invoke(o,args);
    }catch(Throwable denied){}
    return null;
  }
  private static Object fid(Object manager,String category,String symbol,int device,boolean floating){
    if(manager==null)return null;
    Integer id=null;
    for(String name:new String[]{BASE+"BYDAutoFeatureIds$"+category,BASE+"BYDAutoFeatureIds"}){
      try{id=((Number)Class.forName(name).getField(symbol).get(null)).intValue();break;}
      catch(Throwable denied){}
    }
    if(id==null)return null;
    return call(manager,floating?"getDouble":"getInt",device,id);
  }
}