package sa.urtv.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only, for the screensaver (same signing key, checked per call): the active account's photo names
 * (content://sa.urtv.home.photos/list) and each photo (…/file/<name>). Only the active account's, so a
 * locked account's photos never show while someone else is watching.
 */
public class PhotoProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private boolean trusted() {
        int uid = Binder.getCallingUid();
        return uid == Process.myUid()
            || getContext().getPackageManager().checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!trusted()) throw new SecurityException("not the same signer");
        if ("active".equals(uri.getLastPathSegment())) {   // the screensaver keeps its settings per account
            Profiles.Profile p = Profiles.active(getContext());
            MatrixCursor a = new MatrixCursor(new String[] {"id", "name"});
            a.addRow(new Object[] {p.id, p.name});
            return a;
        }
        MatrixCursor c = new MatrixCursor(new String[] {"name"});
        if (!"list".equals(uri.getLastPathSegment())) return c;
        Profiles.Profile p = Profiles.active(getContext());
        for (File f : Profiles.photos(getContext(), p.id)) c.addRow(new Object[] {f.getName()});
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!trusted()) throw new SecurityException("not the same signer");
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"file".equals(seg.get(0)) || !seg.get(1).matches("[A-Za-z0-9_-]+\\.jpg")) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(Profiles.photosDir(getContext(), Profiles.active(getContext()).id), seg.get(1));
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "list".equals(uri.getLastPathSegment()) ? null : "image/jpeg"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
