package org.audioall.android;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.Locale;

/** Read-only, per-URI permission grants. No broad storage permission needed. */
public final class ReceivedProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException {
        if(!"org.audioall.android.received".equals(uri.getAuthority()) || uri.getPathSegments().size()!=1)throw new FileNotFoundException();
        try{File root=new File(getContext().getFilesDir(),"received").getCanonicalFile();File file=new File(root,uri.getLastPathSegment()).getCanonicalFile();if(!file.getParentFile().equals(root)||!file.isFile())throw new FileNotFoundException();return file;}catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public String getType(Uri uri){String name=uri.getLastPathSegment();int dot=name==null?-1:name.lastIndexOf('.');String type=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));return type==null?"application/octet-stream":type;}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File file=resolve(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols,1);Object[] row=new Object[cols.length];for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?file.getName():OpenableColumns.SIZE.equals(cols[i])?file.length():null;c.addRow(row);return c;}catch(FileNotFoundException e){return null;}}
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
