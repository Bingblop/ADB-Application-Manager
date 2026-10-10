package com.bloatware.bingblop;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * The key this app signs edited APKs with. It is generated inside the Android Keystore (in secure hardware where the
 * phone has it), so it can't be copied off the device; only signatures leave it. {@link ApkSigner} does the signing.
 */
final class SigningKey {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "adbmgr_apk_signing_key";
    private static final String SUBJECT = "CN=ADB Application Manager Pro, O=On-device signing key";

    final PrivateKey key;
    final X509Certificate cert;
    final long created;
    final boolean hardware;
    final int bits;

    private SigningKey(PrivateKey key, X509Certificate cert, long created, boolean hardware, int bits) {
        this.key = key;
        this.cert = cert;
        this.created = created;
        this.hardware = hardware;
        this.bits = bits;
    }

    String sha256() throws Exception {
        return ApkSigner.certSha256(cert);
    }

    String subject() {
        return cert.getSubjectX500Principal().getName();
    }

    private static KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        return ks;
    }

    /** The existing key, or null when none has been made yet. */
    static synchronized SigningKey existing() throws Exception {
        KeyStore ks = store();
        if (!ks.containsAlias(ALIAS)) return null;
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        java.security.cert.Certificate c = ks.getCertificate(ALIAS);
        if (key == null || !(c instanceof X509Certificate)) return null;
        Date made = ks.getCreationDate(ALIAS);
        boolean hw = false;
        int bits = 0;
        try {
            KeyInfo info = KeyFactory.getInstance(key.getAlgorithm(), PROVIDER).getKeySpec(key, KeyInfo.class);
            hw = info.isInsideSecureHardware();
            bits = info.getKeySize();
        } catch (Throwable ignored) {
            // the key still signs; only the label is lost
        }
        return new SigningKey(key, (X509Certificate) c, made != null ? made.getTime() : 0, hw, bits);
    }

    /** The key, generated first when this is the first time. */
    static synchronized SigningKey getOrCreate() throws Exception {
        SigningKey k = existing();
        return k != null ? k : generate();
    }

    /** Throws the old key away and makes a new one (APKs signed with the old key can then no longer be updated over). */
    static synchronized SigningKey regenerate() throws Exception {
        KeyStore ks = store();
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        return generate();
    }

    private static SigningKey generate() throws Exception {
        long now = System.currentTimeMillis();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER);
        kpg.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal(SUBJECT))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 24L * 3600 * 1000))
                .setCertificateNotAfter(new Date(2461449600000L))   // 2048-01-01: past Play's 2033 rule, still a classic UTCTime date
                .build());
        kpg.generateKeyPair();
        SigningKey k = existing();
        if (k == null) throw new IllegalStateException("The Android Keystore didn't keep the new key");
        return k;
    }
}
