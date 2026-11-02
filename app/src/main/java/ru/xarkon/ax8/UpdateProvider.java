package ru.xarkon.ax8;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only content:// URI for the system installer, including filename/size metadata. */
public final class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File approved(Uri uri)throws FileNotFoundException{
        if(uri==null||!"/apk".equals(uri.getPath()))
            throw new FileNotFoundException("Недопустимый адрес APK");
        File file=UpdateManager.apkFile(getContext());
        if(!file.isFile())throw new FileNotFoundException("Файл обновления отсутствует");
        return file;
    }
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");
        return ParcelFileDescriptor.open(approved(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{
            File file=approved(uri);
            String[] columns=projection!=null?projection:
                new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
            MatrixCursor cursor=new MatrixCursor(columns);
            Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++){
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]="AX8PRO_Update.apk";
                else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();
            }
            cursor.addRow(row);
            return cursor;
        }catch(FileNotFoundException ignored){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
