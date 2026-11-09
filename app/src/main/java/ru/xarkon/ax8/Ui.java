package ru.xarkon.ax8;

import android.content.Context;
import android.app.Dialog;
import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.TextUtils;
import java.util.*;

final class Ui {
    static int BG=0xFF0A111B,CARD=0xFF121E2A,CARD2=0xFF203140;
    static int TEXT=0xFFF1F6FB,MUTED=0xFF9CAEBd,CYAN=0xFF55DFCB;
    static int BLUE=0xFF5B9AF9,GREEN=0xFF50DA9E,ORANGE=0xFFFFB765,RED=0xFFFB6A7B,YELLOW=0xFFFFD166;
    static int BORDER=0xFF283947;
    static float FONT_SCALE=1f; static int CORNER=22; static String FONT="sans-serif";
    static boolean MOTION=true, COMPACT=false;
    static void load(android.content.SharedPreferences pref){
        String theme=pref.getString("theme","dark");
        if("light".equals(theme)){
            BG=0xFFF5F7FB;CARD=0xFFFFFFFF;CARD2=0xFFE8EEF5;TEXT=0xFF152536;MUTED=0xFF607587;
            BORDER=0xFFD7E1EB;GREEN=0xFF087C52;ORANGE=0xFFAE641A;RED=0xFFBA3447;YELLOW=0xFF996A00;
        }else if("amoled".equals(theme)){
            BG=0xFF000000;CARD=0xFF0B0B0D;CARD2=0xFF191B20;TEXT=0xFFF5F7F9;MUTED=0xFF9BA8B1;
            BORDER=0xFF26303B;GREEN=0xFF50DA9E;ORANGE=0xFFFFB765;RED=0xFFFB6A7B;YELLOW=0xFFFFD166;
        }else if("slate".equals(theme)){
            BG=0xFF151921;CARD=0xFF202834;CARD2=0xFF2B3645;TEXT=0xFFEEF3F8;MUTED=0xFFA4B2C5;
            BORDER=0xFF3E4D61;GREEN=0xFF50DA9E;ORANGE=0xFFFFB765;RED=0xFFFB6A7B;YELLOW=0xFFFFD166;
        }else{
            BG=0xFF0A111B;CARD=0xFF121E2A;CARD2=0xFF203140;TEXT=0xFFF1F6FB;MUTED=0xFF9CAEBd;
            BORDER=0xFF283947;GREEN=0xFF50DA9E;ORANGE=0xFFFFB765;RED=0xFFFB6A7B;YELLOW=0xFFFFD166;
        }
        if(pref.contains("background_color"))BG=pref.getInt("background_color",BG);
        if(pref.contains("card_color"))CARD=pref.getInt("card_color",CARD);
        CYAN=pref.getInt("accent_color",0xFF55DFCB);
        BLUE=CYAN;
        // Prevent invisible text on pale or custom backgrounds: base text is theme-appropriate.
        FONT_SCALE=Math.max(.8f,Math.min(1.35f,pref.getInt("font_scale",100)/100f));
        CORNER=pref.getInt("corner",22);
        FONT="sans-serif-condensed";
        MOTION=pref.getBoolean("motion",true);COMPACT=pref.getBoolean("compact",false);
    }
    static boolean isLight(){return (Color.red(BG)*299+Color.green(BG)*587+Color.blue(BG)*114)/1000>155;}
    static int contrastOn(int color){return (Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)/1000>150?Color.BLACK:Color.WHITE;}

    static int dp(Context c,float d){return (int)(d*c.getResources().getDisplayMetrics().density+0.5f);}
    static GradientDrawable shape(Context c,int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));
        if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;
    }
    static GradientDrawable gradient(Context c,int radius,int... color){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,color);d.setCornerRadius(dp(c,radius));return d;
    }
    static TextView text(Context c,String value,int sp,int color,boolean strong){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(sp*FONT_SCALE);t.setTextColor(color);
        t.setTypeface(Typeface.create(FONT,strong?Typeface.BOLD:Typeface.NORMAL));
        t.setFontFeatureSettings("kern");t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);return t;
    }
    static LinearLayout vertical(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static LinearLayout horizontal(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    static LinearLayout.LayoutParams lp(Context c,int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(c,width),height<0?height:dp(c,height));}
    static LinearLayout.LayoutParams weight(Context c){return new LinearLayout.LayoutParams(0,-2,1f);}
    static void addSpace(LinearLayout col,Context c,int height){View x=new View(c);col.addView(x,lp(c,1,height));}
    static LinearLayout card(Context c){
        LinearLayout v=vertical(c);v.setBackground(shape(c,CARD,0,CORNER));v.setPadding(dp(c,COMPACT?13:18),dp(c,COMPACT?12:18),dp(c,COMPACT?13:18),dp(c,COMPACT?12:18));
        return v;
    }
    static void title(LinearLayout target,Context c,String head,String subtitle){
        target.addView(text(c,head,20,TEXT,true));if(subtitle!=null&&!subtitle.isEmpty()){
            addSpace(target,c,6);target.addView(text(c,subtitle,12,MUTED,false));
        }
    }
    static void animateTouch(View v){
        if(!MOTION)return;
        v.setOnTouchListener((view,event)->{
            if(event.getAction()==MotionEvent.ACTION_DOWN)view.animate().scaleX(.975f).scaleY(.975f).setDuration(110).start();
            else if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL)view.animate().scaleX(1).scaleY(1).setDuration(135).start();
            return false;
        });
    }
    static TextView button(Context c,String text,int fill,int fg,Runnable action){
        TextView t=text(c,text,14,fg,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(c,48));
        t.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));t.setBackground(shape(c,fill,0,Math.max(4,CORNER-7)));
        t.setClickable(true);t.setFocusable(true);
        t.setForeground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((CYAN&0x00FFFFFF)|0x28000000),null,shape(c,0xFFFFFFFF,0,Math.max(4,CORNER-7))));
        t.setOnClickListener(v->{v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);action.run();});
        animateTouch(t);return t;
    }
    static LinearLayout infoRow(Context c,String label,String value,int accent){
        LinearLayout row=horizontal(c);row.setPadding(0,dp(c,9),0,dp(c,9));
        TextView a=text(c,label,13,MUTED,false),b=text(c,value,14,accent,true);
        a.setPadding(0,0,dp(c,12),0);
        b.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        b.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        row.addView(a,new LinearLayout.LayoutParams(0,-2,.94f));
        row.addView(b,new LinearLayout.LayoutParams(0,-2,1.06f));return row;
    }
    static View rule(Context c){View v=new View(c);v.setBackgroundColor(BORDER);return v;}
    static LinearLayout navItem(Context c,String symbol,String label,boolean active,Runnable run){
        return navItem(c,symbol,label,active,0,run);
    }
    static LinearLayout navItem(Context c,String symbol,String label,boolean active,int badgeCount,Runnable run){
        LinearLayout l=vertical(c);l.setGravity(Gravity.CENTER);l.setPadding(2,10,2,10);
        if(active)l.setBackground(shape(c,CARD2,0,Math.max(4,CORNER-6)));
        // Fixed-size icon slot. The badge is always inside its parent (no negative margins),
        // including on narrow screens and for '99+'.
        FrameLayout wrap=new FrameLayout(c);
        LinearLayout.LayoutParams wrapParams=lp(c,49,34);wrapParams.gravity=Gravity.CENTER_HORIZONTAL;
        TextView icon=text(c,symbol,23,active?CYAN:MUTED,true);icon.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(c,32),dp(c,31),Gravity.CENTER);
        wrap.addView(icon,ip);
        if(badgeCount>0){
            String shown=badgeCount>99?"99+":String.valueOf(badgeCount);
            TextView badge=text(c,shown,9,isLight()?Color.BLACK:Color.WHITE,true);
            badge.setMinWidth(dp(c,18));badge.setMinHeight(dp(c,18));
            badge.setPadding(dp(c,3),0,dp(c,3),0);
            badge.setGravity(Gravity.CENTER);badge.setBackground(shape(c,RED,0,99));
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(c,19),Gravity.END|Gravity.TOP);
            wrap.addView(badge,bp);
        }
        TextView caption=text(c,label,10,active?TEXT:MUTED,active);caption.setGravity(Gravity.CENTER);
        l.addView(wrap,wrapParams);addSpace(l,c,4);l.addView(caption);l.setOnClickListener(v->run.run());animateTouch(l);return l;
    }
    static TextView chip(Context c,String value,int fg){
        TextView x=text(c,value,11,fg,true);x.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6));
        x.setBackground(shape(c,CARD2,0,12));return x;
    }
    /** Native modal dialog whose background, buttons and typography follow Ui.load(). */
    static Dialog modal(Activity activity,String heading,String message,LinearLayout body,String positive,Runnable accept){
        Dialog dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vertical(activity);
        int pad=dp(activity,20);
        panel.setPadding(pad,pad,pad,dp(activity,15));
        panel.setBackground(shape(activity,CARD,0,Math.max(9,CORNER)));
        panel.addView(text(activity,heading,20,TEXT,true));
        if(message!=null&&!message.isEmpty()){
            addSpace(panel,activity,12);panel.addView(text(activity,message,15,TEXT,false));
        }
        if(body!=null){
            addSpace(panel,activity,13);
            DisplayMetrics dm=activity.getResources().getDisplayMetrics();
            final int maxBody=Math.min((int)(dm.heightPixels*.52f),dp(activity,440));
            ScrollView sc=new ScrollView(activity){
                @Override protected void onMeasure(int ws,int hs){
                    super.onMeasure(ws,MeasureSpec.makeMeasureSpec(maxBody,MeasureSpec.AT_MOST));
                }
            };
            sc.setFillViewport(false);sc.setVerticalScrollBarEnabled(false);
            sc.addView(body);
            panel.addView(sc,new LinearLayout.LayoutParams(-1,-2));
        }
        addSpace(panel,activity,22);
        LinearLayout actions=horizontal(activity);actions.setGravity(Gravity.END);
        if(accept!=null){
            TextView cancel=button(activity,"Отмена",CARD2,MUTED,dialog::dismiss);
            actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(activity,47),1));
            View space=new View(activity);actions.addView(space,lp(activity,9,1));
        }
        TextView yes=button(activity,positive,accept==null?CARD2:CYAN,accept==null?TEXT:contrastOn(CYAN),()->{
            if(accept!=null)accept.run();dialog.dismiss();
        });
        actions.addView(yes,new LinearLayout.LayoutParams(0,dp(activity,47),1));
        panel.addView(actions);
        dialog.setContentView(panel);
        Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setDimAmount(.68f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        window=dialog.getWindow();if(window!=null){
            DisplayMetrics dm=activity.getResources().getDisplayMetrics();
            window.setLayout(Math.min(dm.widthPixels-dp(activity,30),dp(activity,500)),WindowManager.LayoutParams.WRAP_CONTENT);
            window.setNavigationBarColor(BG);
            window.getDecorView().setSystemUiVisibility(isLight()?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        }
        return dialog;
    }
    /** Bounded dark bottom sheet, independent of the system's default light/gray AlertDialog. */
    static Dialog sheet(Activity activity,String title,String subtitle,LinearLayout content){
        Dialog dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=vertical(activity);
        panel.setPadding(dp(activity,20),dp(activity,19),dp(activity,20),dp(activity,12));
        panel.setBackground(shape(activity,CARD,0,Math.max(5,CORNER)));
        View handle=new View(activity);handle.setBackground(shape(activity,BORDER,0,4));
        LinearLayout.LayoutParams handleP=lp(activity,39,4);handleP.gravity=Gravity.CENTER_HORIZONTAL;
        panel.addView(handle,handleP);addSpace(panel,activity,16);
        TextView head=text(activity,title,20,TEXT,true);head.setMaxLines(2);head.setEllipsize(TextUtils.TruncateAt.END);panel.addView(head);
        if(subtitle!=null&&!subtitle.isEmpty()){
            addSpace(panel,activity,6);panel.addView(text(activity,subtitle,12,MUTED,false));
        }
        addSpace(panel,activity,12);
        // The content can shrink when the keyboard is visible and never forces controls off-screen.
        DisplayMetrics dm=activity.getResources().getDisplayMetrics();
        final int maxContent=(int)(dm.heightPixels*.68f);
        ScrollView scroller=new ScrollView(activity){
            @Override protected void onMeasure(int ws,int hs){
                int available=MeasureSpec.getSize(hs);
                if(available<=0)available=maxContent;
                super.onMeasure(ws,MeasureSpec.makeMeasureSpec(Math.min(maxContent,available),MeasureSpec.AT_MOST));
            }
        };
        scroller.setFillViewport(false);scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);scroller.addView(content);
        panel.addView(scroller,new LinearLayout.LayoutParams(-1,-2));
        addSpace(panel,activity,12);
        TextView dismiss=button(activity,"Закрыть",CARD2,MUTED,dialog::dismiss);
        panel.addView(dismiss,lp(activity,-1,44));
        dialog.setContentView(panel);
        android.view.Window win=dialog.getWindow();
        if(win!=null){win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.setDimAmount(.68f);win.setGravity(Gravity.BOTTOM);}
        dialog.show();
        win=dialog.getWindow();if(win!=null){
            WindowManager.LayoutParams attr=win.getAttributes();
            attr.width=Math.min(dm.widthPixels-dp(activity,18),dp(activity,560));
            attr.height=WindowManager.LayoutParams.WRAP_CONTENT;
            attr.gravity=Gravity.BOTTOM;attr.y=dp(activity,8);
            attr.windowAnimations=R.style.AX8SheetAnimation;
            win.setAttributes(attr);win.setNavigationBarColor(BG);
            win.getDecorView().setSystemUiVisibility(isLight()?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        }
        return dialog;
    }

    static class SignalGauge extends View {
        private static final int SG_BAD=0xFFFF5A67, SG_WARN=0xFFFFD34D, SG_GOOD=0xFF54E38E, SG_GREAT=0xFF00E436, SG_EMPTY=0xFF8898A8;
        private float level=Float.NaN;
        private android.animation.ValueAnimator animator;
        private final Paint p=new Paint(3);
        SignalGauge(Context c){super(c);}
        private int stateColor(){
            if(Float.isNaN(level))return SG_EMPTY;
            if(level<0.25f)return SG_BAD;
            if(level<0.55f)return 0xFFFFB84F;
            if(level<0.75f)return SG_WARN;
            if(level<0.9f)return SG_GOOD;
            return SG_GREAT;
        }
        void setLevelImmediate(float l){
            if(animator!=null){animator.cancel();animator=null;}
            level=l;invalidate();
        }
        void setLevel(float l){
            if(!MOTION){setLevelImmediate(l);return;}
            if(Float.isNaN(l)){setLevelImmediate(l);return;}
            l=Math.max(0,Math.min(1,l));
            if(!Float.isNaN(level)&&Math.abs(level-l)<.012f)return;
            if(animator!=null){animator.cancel();animator=null;}
            if(Float.isNaN(level)){setLevelImmediate(l);return;}
            animator=android.animation.ValueAnimator.ofFloat(level,l);
            animator.setDuration(440);
            animator.addUpdateListener(v->{level=(float)v.getAnimatedValue();invalidate();});
            animator.start();
        }
        @Override protected void onDetachedFromWindow(){if(animator!=null){animator.cancel();animator=null;}super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);
            float w=getWidth(),h=getHeight(),size=Math.min(w,h),cx=w/2,cy=h/2,r=size*.39f;
            int state=stateColor();
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),11));p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(40,62,78));c.drawArc(cx-r,cy-r,cx+r,cy+r,145,250,false,p);
            if(!Float.isNaN(level)){
                p.setColor(state);
                c.drawArc(cx-r,cy-r,cx+r,cy+r,145,250*level,false,p);
            }
            p.setStyle(Paint.Style.FILL);p.setColor(state);p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(FONT,Typeface.BOLD));p.setTextSize(dp(getContext(),29));
            c.drawText(Float.isNaN(level)?"—":Math.round(level*100)+"%",cx,cy+dp(getContext(),9),p);
            p.setTextSize(dp(getContext(),10));p.setTypeface(Typeface.create(FONT,Typeface.NORMAL));p.setColor(MUTED);
            c.drawText("ОЦЕНКА",cx,cy+dp(getContext(),29),p);
        }
    }
    static class SpeedGraph extends View {
        private final ArrayDeque<Float> down=new ArrayDeque<>(),up=new ArrayDeque<>();
        private final Paint p=new Paint(3);
        SpeedGraph(Context c){super(c);for(int i=0;i<28;i++){down.add(0f);up.add(0f);}}
        void push(float dl,float ul){
            if(down.size()>=28)down.poll();
            if(up.size()>=28)up.poll();
            down.add(Math.max(0f,dl));up.add(Math.max(0f,ul));invalidate();
        }
        private String rate(float x){
            if(x>=1_000_000f)return String.format(Locale.US,"%.1f MB/s",x/1_000_000f);
            if(x>=1_000f)return String.format(Locale.US,"%.1f KB/s",x/1_000f);
            return String.format(Locale.US,"%.0f B/s",x);
        }
        private void drawSeries(Canvas c,ArrayDeque<Float> values,float max,int plotW,int plotH,int left,int top,int line,int fill){
            if(values.isEmpty())return;
            float step=values.size()>1?(float)plotW/(values.size()-1):plotW;
            float x=left;int i=0;Path curve=new Path(),area=new Path();
            for(float v:values){
                float y=top+plotH-(v/max)*(plotH-dp(getContext(),10));
                if(i==0){curve.moveTo(x,y);area.moveTo(x,top+plotH);}curve.lineTo(x,y);area.lineTo(x,y);x+=step;i++;
            }
            area.lineTo(left+plotW,top+plotH);area.close();
            p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawPath(area,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(dp(getContext(),2));p.setColor(line);c.drawPath(curve,p);
        }
        @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();if(w==0||h==0)return;
            int rightPad=dp(getContext(),58),top=dp(getContext(),6),bottom=dp(getContext(),14),left=dp(getContext(),2);
            int plotW=Math.max(dp(getContext(),80),w-rightPad-left),plotH=Math.max(dp(getContext(),36),h-top-bottom);
            float max=1f;for(float x:down)max=Math.max(max,x);for(float x:up)max=Math.max(max,x);
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),1));p.setColor(0x33506A80);
            for(int mark=0;mark<=4;mark++){
                float ratio=mark/4f;
                float y=top+plotH-(plotH*ratio);
                c.drawLine(left,y,left+plotW,y,p);
            }
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.RIGHT);p.setTypeface(Typeface.create(FONT,Typeface.NORMAL));
            p.setTextSize(dp(getContext(),9));p.setColor(MUTED);
            for(int mark=0;mark<=4;mark++){
                float ratio=mark/4f;
                float y=top+plotH-(plotH*ratio);
                c.drawText(mark==0?"0 B/s":rate(max*ratio),w-dp(getContext(),2),y+dp(getContext(),3),p);
            }
            drawSeries(c,down,max,plotW,plotH,left,top,CYAN,0x3045DACD);
            drawSeries(c,up,max,plotW,plotH,left,top,ORANGE,0x26FFB765);
        }
    }
    // End of Ui. No executable statements may follow the closing class brace.
}
