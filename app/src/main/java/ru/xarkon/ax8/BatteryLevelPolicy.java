package ru.xarkon.ax8;

/** Router battery presentation. Never treat the coarse battery_pers gauge as an exact percentage. */
final class BatteryLevelPolicy {
    private static final int[] STOPS={0,15,35,60,80,100};
    private static final int[] COLORS={0xFFFF536A,0xFFFF7853,0xFFFFA843,0xFFE7CA47,0xFF91DA5D,0xFF20DC69};
    private BatteryLevelPolicy(){}

    static int percent(double value,String source){
        if(Double.isNaN(value)||Double.isInfinite(value)||value<0)return -1;
        // Some firmware returns a 0..1 fraction as a decimal string.
        if(value>0&&value<=1&&source!=null&&(source.contains(".")||source.contains(",")))value*=100;
        // Reject broken readings instead of turning them into a false 100%.
        if(value>100)return -1;
        return (int)Math.round(value);
    }

    /** Prefer the confirmed AX8 percent field, then a legacy WebUI percent. */
    static int firstValid(double preferred,String preferredRaw,double fallback,String fallbackRaw){
        int result=percent(preferred,preferredRaw);
        return result>=0?result:percent(fallback,fallbackRaw);
    }

    /** 0..4/5 represents a pictogram step on some AX8 firmwares, NOT a precise percent. */
    static int coarseLevel(double value){
        if(Double.isNaN(value)||Double.isInfinite(value)||value<0||value>5
                ||Math.abs(value-Math.rint(value))>.001)return -1;
        return (int)(Math.min(4,Math.rint(value))*25);
    }

    static int color(int level,boolean light){
        if(level<0)return light?0xFF617180:0xFF9BA9B8;
        int l=Math.max(0,Math.min(100,level));
        int color=COLORS[COLORS.length-1];
        for(int i=0;i<STOPS.length-1;i++){
            if(l<=STOPS[i+1]){
                float t=(l-STOPS[i])/(float)(STOPS[i+1]-STOPS[i]);
                color=blend(COLORS[i],COLORS[i+1],t);
                break;
            }
        }
        // Text must retain contrast on the white/light themes.
        return light?blend(color,0xFF000000,.42f):color;
    }

    static int blend(int from,int to,float ratio){
        float r=Math.max(0f,Math.min(1f,ratio));
        int red=Math.round(((from>>16)&255)*(1-r)+((to>>16)&255)*r);
        int green=Math.round(((from>>8)&255)*(1-r)+((to>>8)&255)*r);
        int blue=Math.round((from&255)*(1-r)+(to&255)*r);
        return 0xFF000000|(red<<16)|(green<<8)|blue;
    }
}
