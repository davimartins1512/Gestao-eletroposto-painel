package android.hardware.bydauto;
public final class BYDAutoDeviceManager {
  public static int soh=93;
  public static double voltage12=12.73;
  public static int mutations=0;
  public static BYDAutoDeviceManager getInstance(){return new BYDAutoDeviceManager();}
  public int getInt(int device,int fid) {
    if(device==1014&&fid==94)return soh;
    return -10013;
  }
  public double getDouble(int device,int fid){
    if(device==1001&&fid==91)return voltage12;
    return -10013;
  }
}
