package br.com.andreao.fleetprobe;
import android.content.Context;
import android.hardware.bydauto.BYDAutoDeviceManager;
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice;
public final class FleetTelemetryJvmTest {
  private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
  public static void main(String[] ignored) {
    FleetTelemetryReadOnly.Snapshot good=FleetTelemetryReadOnly.inspect(new Context());
    check(good.soc!=null&&Math.abs(good.soc-81.5)<0.001,"SOC valid");
    check(good.soh!=null&&good.soh==93,"SOH signed firmware FID");
    check(good.voltage12v!=null&&Math.abs(good.voltage12v-12.73)<0.001,"12V");
    BYDAutoDeviceManager.soh=150;
    FleetTelemetryReadOnly.Snapshot outlier=FleetTelemetryReadOnly.inspect(new Context());
    check(outlier.soh==null,"SOH out of bounds must not appear");
    BYDAutoDeviceManager.soh=-10013;
    check(FleetTelemetryReadOnly.inspect(new Context()).soh==null,"BYD invalid sentinel rejected");
    BYDAutoStatisticDevice.denied=true;
    check(FleetTelemetryReadOnly.inspect(new Context()).soc==null,"Permission denied reports absent, not bypassed");
    check(BYDAutoDeviceManager.mutations==0,"No vehicle setters");
    System.out.println("6 mock assertions passed; NOT a physical BYD or firmware integration test");
  }
}
