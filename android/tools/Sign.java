import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** apksigner without build-tools: Sign <keystore.p12> <in.apk> <out.apk> <minSdk>; v1+v2+v3, entries aligned. */
public class Sign {
    public static void main(String[] a) throws Exception {
        char[] pass = "android".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[0])) { ks.load(in, pass); }
        PrivateKey key = (PrivateKey) ks.getKey("androiddebugkey", pass);
        X509Certificate cert = (X509Certificate) ks.getCertificate("androiddebugkey");
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("androiddebugkey", key,
            Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
            .setInputApk(new File(a[1])).setOutputApk(new File(a[2]))
            .setMinSdkVersion(Integer.parseInt(a[3])).build().sign();
    }
}
