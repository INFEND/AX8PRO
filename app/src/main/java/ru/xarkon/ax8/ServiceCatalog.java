package ru.xarkon.ax8;

import java.util.*;

/** Exact host seeds, not wildcard coverage or a list of dedicated IP ranges. */
final class ServiceCatalog {
    static final class Item {
        final String id,name,category,hosts;
        Item(String i,String n,String c,String h){id=i;name=n;category=c;hosts=h;}
    }
    static final List<Item> ITEMS=Collections.unmodifiableList(Arrays.asList(
        new Item("play","Google Play","Магазины","play.google.com android.clients.google.com android.apis.google.com dl.google.com dl-ssl.google.com"),
        new Item("rustore","RuStore","Магазины","apps.rustore.ru www.rustore.ru api.rustore.ru"),
        new Item("appstore","App Store","Магазины","apps.apple.com itunes.apple.com buy.itunes.apple.com"),
        new Item("galaxy","Galaxy Store","Магазины","galaxystore.samsung.com"),
        new Item("appgallery","Huawei AppGallery","Магазины","appgallery.huawei.com"),
        new Item("msstore","Microsoft Store","Магазины","apps.microsoft.com store.microsoft.com"),
        new Item("android","Обновления Android","Обновления","ota.googlezip.net ota-cache1.googlezip.net ota-cache3.googlezip.net"),
        new Item("ios","Обновления iOS / iPadOS","Обновления","appldnld.apple.com mesu.apple.com gdmf.apple.com"),
        new Item("windows","Windows Update","Обновления","download.windowsupdate.com windowsupdate.microsoft.com delivery.mp.microsoft.com"),
        new Item("samsungota","Обновления Samsung","Обновления","fota-secure-dn.ospserver.net fota-cloud-dn.ospserver.net"),
        new Item("drive","Google Drive","Облако","drive.google.com drive.googleapis.com"),
        new Item("icloud","iCloud","Облако","www.icloud.com setup.icloud.com icloud.com"),
        new Item("onedrive","OneDrive","Облако","onedrive.live.com onedrive.com"),
        new Item("yadisk","Яндекс Диск","Облако","disk.yandex.ru disk.360.yandex.ru api.disk.yandex.net dataapi.disk.yandex.net intapi.disk.yandex.net webdav.disk.yandex.net cloud-api.yandex.net"),
        new Item("scloud","Samsung Cloud","Облако","support.samsungcloud.com samsungcloud.com"),
        new Item("photos","Google Фото","Облако","photos.google.com photoslibrary.googleapis.com"),
        new Item("dropbox","Dropbox","Облако","www.dropbox.com api.dropboxapi.com content.dropboxapi.com"),
        new Item("mega","MEGA","Облако","mega.nz g.api.mega.co.nz"),
        new Item("box","Box","Облако","app.box.com api.box.com upload.box.com"),
        new Item("mailcloud","Облако Mail","Облако","cloud.mail.ru"),
        new Item("youtube","YouTube","Видео и музыка","www.youtube.com m.youtube.com youtubei.googleapis.com"),
        new Item("netflix","Netflix","Видео и музыка","www.netflix.com api-global.netflix.com"),
        new Item("tiktok","TikTok","Видео и музыка","www.tiktok.com"),
        new Item("spotify","Spotify","Видео и музыка","open.spotify.com api.spotify.com"),
        new Item("steam","Steam","Игры","store.steampowered.com steamcommunity.com"),
        new Item("epic","Epic Games","Игры","store.epicgames.com")
    ));
    static Item get(String id){for(Item i:ITEMS)if(i.id.equals(id))return i;return null;}
    static boolean validScope(String scope){return "ALL".equals(scope)||scope!=null&&scope.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}");}
    /** Deterministic, bounded plan. No UI text or arbitrary shell fragments enter it. */
    static String plan(String revision,Map<String,Set<String>> rules){
        if(!revision.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Некорректная версия правил");
        StringBuilder b=new StringBuilder(revision).append('\n');int count=0;
        for(String scope:new TreeSet<>(rules.keySet())){
            if(!validScope(scope))throw new IllegalArgumentException("Некорректный MAC");
            for(String id:new TreeSet<>(rules.get(scope))){
                Item item=get(id);if(item==null)throw new IllegalArgumentException("Неизвестный сервис");
                if(++count>128)throw new IllegalArgumentException("Максимум 128 сочетаний сервис / устройство");
                b.append(scope).append(' ').append(id).append(' ').append(item.hosts).append('\n');
            }
        }
        return b.toString();
    }
    private ServiceCatalog(){}
}
