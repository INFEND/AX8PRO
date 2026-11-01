package ru.xarkon.ax8;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

/** A pinned, immutable official update channel. No user-editable endpoints. */
public final class UpdateManager {
    private UpdateManager(){}
    public static final String MANIFEST_URL="https://github.com/ANTIFERMENT/AX8PRO/releases/latest/download/update.json";
    public static final String TRUSTED_CERT_SHA256="BD4A9D2F3DE3117714C900E9FF5A5D632A0AAE0B3BD3DC58C8F2AC83363E585A";
    public static final String PACKAGE="ru.xarkon.ax8pro";
    private static final String RELEASE_PREFIX="/ANTIFERMENT/AX8PRO/releases/download/";
    private static final long MAX_APK=200L*1024L*1024L;
    private static final String APK_NAME="AX8PRO-update.apk";
    public interface Listener {
        void status(String message);
        default void progress(long downloaded,long total){}
        void available(Release release,boolean newer);
        void downloaded(File apk);
        void error(String message);
    }
    public static final class Release {
        public final String versionName,apkUrl,sha256,notes;
        public final long versionCode,sizeBytes;
        Release(JSONObject j)throws Exception{
            if(j.optInt("schema",1)!=1)throw new IOException("Неизвестный формат OTA");
            if(!PACKAGE.equals(j.getString("packageName")))throw new IOException("Неверный идентификатор приложения");
            versionCode=j.getLong("versionCode");versionName=j.getString("versionName");
            apkUrl=j.getString("apkUrl");sha256=j.getString("sha256").toUpperCase(Locale.US);
            sizeBytes=j.optLong("sizeBytes",-1);notes=j.optString("notes","");
            URL url=new URL(apkUrl);
            if(!"https".equals(url.getProtocol())||!"github.com".equalsIgnoreCase(url.getHost())||
                !url.getPath().startsWith(RELEASE_PREFIX)||!url.getPath().endsWith("/AX8PRO.apk")||
                versionCode<1||sizeBytes>MAX_APK||!sha256.matches("[A-F0-9]{64}"))
                throw new IOException("Недоверенный APK или неверные метаданные");
        }
    }
    private static HttpsURLConnection connect(String url)throws Exception{
        URL u=new URL(url);if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("Требуется HTTPS");
        HttpsURLConnection c=(HttpsURLConnection)u.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(24000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","AX8PRO-Updater/1.0");return c;
    }
    private static String readManifest()throws Exception{
        HttpsURLConnection c=connect(MANIFEST_URL);
        try{
            if(c.getResponseCode()!=200)throw new IOException("Manifest HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>65536)throw new IOException("Manifest слишком большой");}
                return out.toString("UTF-8");
            }
        }finally{c.disconnect();}
    }
    public static Release fetchLatest()throws Exception{return new Release(new JSONObject(readManifest()));}
    public static long currentCode(Context c)throws Exception{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();}
    private static void ui(Activity a,Runnable r){a.runOnUiThread(r);}
    public static void check(Activity a,Listener listener){
        new Thread(()->{
            try{ui(a,()->listener.status("Проверка обновлений…"));Release r=fetchLatest();
                long code=currentCode(a);ui(a,()->listener.available(r,r.versionCode>code));
            }catch(Exception e){ui(a,()->listener.error(friendly(e)));}
        },"ax8pro-ota-check").start();
    }
    private static String friendly(Exception e){
        if(e instanceof UnknownHostException)return "Сервер обновлений недоступен. Проверьте интернет, VPN и DNS.";
        if(e instanceof SocketTimeoutException)return "Сервер обновлений не ответил вовремя.";
        String m=e.getMessage();return m==null?e.toString():m;
    }
    public static File apkFile(Context c){return new File(new File(c.getFilesDir(),"ota"),APK_NAME);}
    public static void download(Activity a,Release r,Listener listener){
        new Thread(()->{
            File dest=apkFile(a),temp=new File(dest.getParentFile(),APK_NAME+".part");
            try{
                if(!dest.getParentFile().isDirectory()&&!dest.getParentFile().mkdirs())throw new IOException("Не удалось создать папку OTA");
                if(r.sizeBytes>0&&dest.getParentFile().getUsableSpace()<r.sizeBytes+2*1024*1024)throw new IOException("Недостаточно памяти");
                HttpsURLConnection c=connect(r.apkUrl);long total=r.sizeBytes,bytes=0;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try{
                    if(c.getResponseCode()!=200)throw new IOException("APK HTTP "+c.getResponseCode());
                    if(total<1)total=c.getContentLengthLong();
                    try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(temp)){
                        byte[] buf=new byte[65536];int n;long last=0;
                        while((n=in.read(buf))!=-1){bytes+=n;if(bytes>MAX_APK)throw new IOException("Слишком большой APK");
                            out.write(buf,0,n);digest.update(buf,0,n);long now=System.currentTimeMillis();
                            if(now-last>200){last=now;final long b=bytes,t=total;ui(a,()->listener.progress(b,t));}
                        }out.getFD().sync();
                    }
                }finally{c.disconnect();}
                if(r.sizeBytes>0&&r.sizeBytes!=bytes)throw new IOException("Неполная загрузка APK");
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02X",b&255));
                if(!hash.toString().equals(r.sha256))throw new IOException("SHA-256 APK не совпадает");
                if(dest.exists()&&!dest.delete())throw new IOException("Не удалось заменить старый APK");
                if(!temp.renameTo(dest))throw new IOException("Не удалось сохранить APK");
                try{verifyApk(a,dest,r.versionCode);}catch(Exception e){dest.delete();throw e;}
                ui(a,()->listener.downloaded(dest));
            }catch(Exception e){temp.delete();ui(a,()->listener.error(friendly(e)));}
        },"ax8pro-ota-download").start();
    }
    private static String signatureHash(Signature s)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
        StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(String.format(Locale.US,"%02X",b&255));return out.toString();
    }
    private static Signature[] signers(PackageInfo p){
        if(Build.VERSION.SDK_INT>=28){return p.signingInfo==null?null:p.signingInfo.getApkContentsSigners();}
        return p.signatures;
    }
    public static void verifyApk(Context c,File apk,long requiredCode)throws Exception{
        PackageManager pm=c.getPackageManager();
        int flag=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo target=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flag);
        if(target==null||!PACKAGE.equals(target.packageName))throw new IOException("Неверный APK: пакет не AX 8 PRO");
        if(target.getLongVersionCode()!=requiredCode||requiredCode<=currentCode(c))throw new IOException("Версия APK не соответствует новой версии");
        Signature[] newSigns=signers(target);
        if(newSigns==null||newSigns.length!=1||!TRUSTED_CERT_SHA256.equals(signatureHash(newSigns[0])))
            throw new IOException("APK не подписан официальным ключом AX 8 PRO");
        PackageInfo installed=pm.getPackageInfo(c.getPackageName(),flag);Signature[] active=signers(installed);
        if(active==null||active.length!=1||!TRUSTED_CERT_SHA256.equals(signatureHash(active[0])))
            throw new IOException("Установлена отладочная версия. Для OTA сначала установите официальный релиз.");
    }
    public static void install(Activity a){
        try{
            File apk=apkFile(a);if(!apk.isFile())throw new IOException("APK обновления отсутствует");
            PackageInfo info=a.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
            if(info==null)throw new IOException("Не удалось прочитать APK");
            verifyApk(a,apk,info.getLongVersionCode());
            if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));
                android.widget.Toast.makeText(a,"Разрешите установку и повторно нажмите обновление",android.widget.Toast.LENGTH_LONG).show();return;
            }
            Uri uri=Uri.parse("content://ru.xarkon.ax8pro.ota/apk");
            Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri,"application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("AX8 PRO CONTROL",uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT,false);
            a.startActivity(intent);
        }catch(Exception e){new android.app.AlertDialog.Builder(a).setTitle("Обновление OTA")
            .setMessage(friendly(e)).setPositiveButton("Понятно",null).show();}
    }
    /** Shows a reliable Open action after an app update; Android may restrict automatic starts. */
    public static final class InstalledReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){
            if(intent==null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
            try{
                context.getSharedPreferences("ax8_control",Context.MODE_PRIVATE).edit()
                    .putLong("ota_last_installed_at",System.currentTimeMillis()).apply();
                if(Build.VERSION.SDK_INT>=33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
                Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if(launch==null)launch=new Intent(context,MainActivity.class);
                launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                android.app.PendingIntent pending=android.app.PendingIntent.getActivity(context,260,launch,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.NotificationManager manager=(android.app.NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
                if(manager==null)return;
                String channel="ax8pro_update_complete";
                if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(
                    new android.app.NotificationChannel(channel,"AX8 PRO CONTROL · Обновления",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT));
                android.app.Notification.Builder builder=Build.VERSION.SDK_INT>=26?
                    new android.app.Notification.Builder(context,channel):new android.app.Notification.Builder(context);
                builder.setSmallIcon(R.drawable.ic_app).setContentTitle("AX8 PRO CONTROL обновлён")
                    .setContentText("Нажмите, чтобы открыть приложение")
                    .setAutoCancel(true).setContentIntent(pending);
                manager.notify(260,builder.build());
            }catch(Exception ignored){}
        }
    }
}
