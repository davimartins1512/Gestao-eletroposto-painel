package br.com.andreao.fleetprobe;

/** Pure-Java capacity forecasts for operator-configured H.264 recording. */
public final class FleetStorageMath {
  private FleetStorageMath(){}
  public static final double GIB=1073741824d;
  public static final float DEFAULT_QUOTA_GB=8f;
  public static double expectedGiB(double hours,double bitrateMbps,boolean audio){
    if(!Double.isFinite(hours)||!Double.isFinite(bitrateMbps)||hours<0||bitrateMbps<=0)
      return 0d;
    double rate=(bitrateMbps+(audio?0.032d:0d))*1000000d/8d;
    return hours*3600d*rate*1.04d/GIB;
  }
  public static double hoursInQuota(double quotaGiB,double bitrateMbps,boolean audio){
    if(!Double.isFinite(quotaGiB)||quotaGiB<=0)return 0d;
    double oneHour=expectedGiB(1d,bitrateMbps,audio);
    return oneHour>0d?quotaGiB/oneHour:0d;
  }
  public static double bitrateForBudget(double gib,double hours,boolean audio){
    if(gib<=0d||hours<=0d||!Double.isFinite(gib)||!Double.isFinite(hours))return 0d;
    double rate=gib*GIB*8d/(hours*3600d*1000000d*1.04d);
    return Math.max(0d,rate-(audio?0.032d:0d));
  }
}
