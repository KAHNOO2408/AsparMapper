package ir.aspar.mapper.adb;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Connects this app to the phone's own ADB daemon over "Wireless debugging".
 * The RSA key + certificate are generated once and kept in the app's private files,
 * so pairing is needed only the first time.
 */
public final class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;

    public static synchronized AdbManager get(@NonNull Context context) throws Exception {
        if (instance == null) instance = new AdbManager(context.getApplicationContext());
        return instance;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(context.getFilesDir(), "adb_private.key");
        File certFile = new File(context.getFilesDir(), "adb_cert.pem");
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = readKey(keyFile);
                cert = readCert(certFile);
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = gen.generateKeyPair();
            PublicKey publicKey = pair.getPublic();
            key = pair.getPrivate();
            cert = makeCertificate(publicKey, key);
            writeKey(keyFile, key);
            writeCert(certFile, cert);
        }
        privateKey = key;
        certificate = cert;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "AsparMapper";
    }

    private static Certificate makeCertificate(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String subject = "CN=Aspar Mapper";
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date();
        // long validity: the certificate is only used to talk to this phone's own adbd
        Date notAfter = new Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000);
        CertificateExtensions ext = new CertificateExtensions();
        ext.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        ext.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X500Name name = new X500Name(subject);
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", ext);
        X509CertImpl impl = new X509CertImpl(info);
        impl.sign(privateKey, algorithm);
        return impl;
    }

    private static PrivateKey readKey(File f) throws Exception {
        byte[] bytes = new byte[(int) f.length()];
        try (InputStream is = new FileInputStream(f)) {
            int off = 0;
            while (off < bytes.length) {
                int n = is.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void writeKey(File f, PrivateKey key) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(key.getEncoded());
        }
    }

    private static Certificate readCert(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
    }

    private static void writeCert(File f, Certificate cert) throws Exception {
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            encoder.encode(cert.getEncoded(), os);
            os.write('\n');
            os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }
}
