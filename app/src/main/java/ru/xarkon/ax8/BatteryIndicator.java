package ru.xarkon.ax8;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact, read-only router battery charge indicator: dynamic fill + animated percent gradient. */
final class BatteryIndicator extends LinearLayout {
    private final BatteryGlyph glyph;
    private final BatteryText value;

    BatteryIndicator(Context context){
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        glyph=new BatteryGlyph(context);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(glyph,new LayoutParams(Ui.dp(context,25),Ui.dp(context,17)));
        value=new BatteryText(context);
        LayoutParams textLayout=new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT);
        textLayout.leftMargin=Ui.dp(context,7);
        addView(value,textLayout);
        setBattery(-1,"—");
    }

    void setBattery(int level,String displayed){
        String text=displayed==null?"—":displayed;
        glyph.setLevel(level);
        value.setLevel(level);
        if(!text.contentEquals(value.getText()))value.setText(text);
        setContentDescription("Заряд батареи роутера: "+(text.equals("—")?"нет данных":text));
    }

    private static final class BatteryGlyph extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        private int level=-1;
        BatteryGlyph(Context context){super(context);}
        void setLevel(int newLevel){
            int safe=newLevel<0?-1:Math.min(100,newLevel);
            if(level!=safe){level=safe;invalidate();}
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float scale=getResources().getDisplayMetrics().density;
            float w=getWidth(),h=getHeight();
            float left=scale,top=scale*2,right=w-scale*5,bottom=h-scale*2;
            if(right<=left||bottom<=top)return;
            rect.set(left,top,right,bottom);
            int color=BatteryLevelPolicy.color(level,Ui.isLight());
            paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(scale*1.3f);
            paint.setColor(level<0?Ui.MUTED:color);
            canvas.drawRoundRect(rect,scale*2.5f,scale*2.5f,paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(right+scale,top+rect.height()*.26f,
                    right+scale*3.6f,bottom-rect.height()*.26f,scale,scale,paint);
            if(level>0){
                float inset=scale*2.5f;
                rect.set(left+inset,top+inset,right-inset,bottom-inset);
                float filled=rect.width()*level/100f;
                rect.right=rect.left+filled;
                if(filled>0)canvas.drawRoundRect(rect,scale*1.3f,scale*1.3f,paint);
            }
        }
    }

    private static final class BatteryText extends TextView {
        private final Matrix matrix=new Matrix();
        private LinearGradient gradient;
        private ValueAnimator shimmer;
        private int level=-1;
        private float phase=0f;
        private int shaderWidth=-1;

        BatteryText(Context context){
            super(context);
            setTextSize(14*Ui.FONT_SCALE);
            setTypeface(android.graphics.Typeface.create(Ui.FONT,android.graphics.Typeface.BOLD));
            setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            setSingleLine(true);
            setIncludeFontPadding(false);
            setTextColor(Ui.MUTED);
        }

        void setLevel(int newLevel){
            int safe=newLevel<0?-1:Math.min(100,newLevel);
            if(level==safe)return;
            level=safe;gradient=null;shaderWidth=-1;
            setTextColor(BatteryLevelPolicy.color(level,Ui.isLight()));
            updateAnimation();invalidate();
        }

        private boolean animateAllowed(){
            PowerManager power=(PowerManager)getContext().getSystemService(Context.POWER_SERVICE);
            return Ui.MOTION && level>=0 && isAttachedToWindow()
                    && getWindowVisibility()==View.VISIBLE
                    && (power==null||!power.isPowerSaveMode());
        }
        private void updateAnimation(){
            if(animateAllowed()){
                if(shimmer==null){
                    shimmer=ValueAnimator.ofFloat(0f,1f);
                    shimmer.setDuration(3400L);
                    shimmer.setRepeatCount(ValueAnimator.INFINITE);
                    shimmer.setRepeatMode(ValueAnimator.REVERSE);
                    shimmer.setInterpolator(new LinearInterpolator());
                    shimmer.addUpdateListener(a->{phase=(float)a.getAnimatedValue();postInvalidateOnAnimation();});
                    shimmer.start();
                }
            }else if(shimmer!=null){
                shimmer.cancel();shimmer=null;phase=0f;invalidate();
            }
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();updateAnimation();}
        @Override protected void onDetachedFromWindow(){if(shimmer!=null){shimmer.cancel();shimmer=null;}super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){
            super.onWindowVisibilityChanged(visibility);updateAnimation();
        }
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            super.onSizeChanged(w,h,ow,oh);gradient=null;shaderWidth=-1;
        }
        @Override protected void onDraw(Canvas canvas){
            if(level>=0 && getWidth()>0){
                if(gradient==null||shaderWidth!=getWidth()){
                    int base=BatteryLevelPolicy.color(level,Ui.isLight());
                    int bright=BatteryLevelPolicy.blend(base,
                            Ui.isLight()?0xFF000000:0xFFFFFFFF,Ui.isLight()?.16f:.43f);
                    gradient=new LinearGradient(0,0,getWidth(),0,
                            new int[]{base,bright,base},new float[]{0f,.48f,1f},Shader.TileMode.CLAMP);
                    shaderWidth=getWidth();
                }
                matrix.setTranslate((phase*2f-1f)*getWidth()*.62f,0f);
                gradient.setLocalMatrix(matrix);
                getPaint().setShader(gradient);
            }else getPaint().setShader(null);
            super.onDraw(canvas);
            getPaint().setShader(null);
        }
    }
}
