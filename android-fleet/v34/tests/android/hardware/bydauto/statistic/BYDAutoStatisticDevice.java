package android.hardware.bydauto.statistic;
import android.content.Context;
public final class BYDAutoStatisticDevice {
  public static double soc=81.5;
  public static boolean denied=false;
  public static BYDAutoStatisticDevice getInstance(Context context){return new BYDAutoStatisticDevice();}
  public double getElecPercentageValue(){
    if(denied)throw new SecurityException("BYD signed permission");
    return soc;
  }
}
