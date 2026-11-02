package ru.xarkon.ax8;

/** Latency-based cadence, not an undocumented router capability claim. */
public final class AutoPollController {
    private AutoPollController(){}
    public static int clamp(int ms){
        // Check bounds before rounding: Integer.MAX_VALUE + 50 overflows.
        if(ms<=500)return 500;
        if(ms>=5000)return 5000;
        return ((ms+50)/100)*100;
    }
    /** Pick the shortest stable interval from observed full-request duration. */
    public static int next(int previousMs,long elapsedMs,boolean successful){
        int old=clamp(previousMs);
        if(!successful){return clamp((int)Math.min(5000L,Math.round(old*1.65)+100L));}
        // Allow a safety margin for small fluctuations. Bounded by the user-visible 0.5–5s range.
        // Bound elapsed time before scaling to avoid overflow for extreme values.
        long bounded=Math.max(0L,Math.min(elapsedMs,5000L));
        long raw=Math.max(500L,Math.min(5000L,Math.round(bounded*1.18d)+90L));
        int target=clamp((int)raw);
        // Move quickly toward a slower interval, cautiously return to faster polling.
        int chosen=target>old?target:(int)Math.round(old*.45d+target*.55d);
        return clamp(chosen);
    }
}