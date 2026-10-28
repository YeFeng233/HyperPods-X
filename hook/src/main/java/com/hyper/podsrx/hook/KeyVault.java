package com.hyper.podsrx.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The bound headset's proximity keys inside the Bluetooth host, so advertisements decrypt after a
 * host restart without an AACP session. Device-protected storage makes them readable before the
 * first unlock; AndroidKeyStore wraps them in the app's SecureKeys format. Never written to the
 * module's remote preferences, which every scoped process can read.
 */
final class KeyVault {
    static final int IRK = 1, ENC = 4;
    private static final String ALIAS = "hyperpods_hub_keys";
    private static final String BOUND = "address";
    private final SharedPreferences preferences;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    KeyVault(Context host) {
        preferences = host.createDeviceProtectedStorageContext().getSharedPreferences("hyperpods_hub", Context.MODE_PRIVATE);
    }

    /** The address whose keys are kept; empty before the first push. */
    String address() {
        return preferences.getString(BOUND, "");
    }

    /** Keeps [bytes] for [address]; a new address first forgets the previous headset's keys. */
    synchronized void put(String address, int type, byte[] bytes) throws Exception {
        if (address == null || address.isEmpty() || !valid(bytes)) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (!address.equalsIgnoreCase(address())) {
            editor.clear();
            cache.clear();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storageKey());
        byte[] iv = cipher.getIV(), sealed = cipher.doFinal(bytes);
        byte[] stored = Arrays.copyOf(iv, iv.length + sealed.length);
        System.arraycopy(sealed, 0, stored, iv.length, sealed.length);
        editor.putString(BOUND, address).putString(name(address, type), Base64.encodeToString(stored, Base64.NO_WRAP)).apply();
        cache.put(name(address, type), bytes.clone());
    }

    /** Returns a copy, or null when no valid key is kept for [address]. */
    byte[] get(String address, int type) {
        if (address == null || address.isEmpty()) return null;
        String name = name(address, type);
        byte[] cached = cache.get(name);
        if (cached != null) return cached.clone();
        try {
            String text = preferences.getString(name, null);
            if (text == null) return null;
            byte[] data = Base64.decode(text, Base64.NO_WRAP);
            if (data.length < 28) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, storageKey(), new GCMParameterSpec(128, data, 0, 12));
            byte[] bytes = cipher.doFinal(data, 12, data.length - 12);
            if (!valid(bytes)) return null;
            cache.put(name, bytes.clone());
            return bytes;
        } catch (Exception error) {
            DiagnosticLog.w("HyperPods-X", "Hub key unavailable", error);
            return null;
        }
    }

    /** Drops every key once the headset is unbound or replaced. */
    synchronized void forgetUnless(String address) {
        if (address != null && address.equalsIgnoreCase(address())) return;
        preferences.edit().clear().apply();
        cache.clear();
    }

    private static String name(String address, int type) {
        return address.toUpperCase(java.util.Locale.ROOT) + ":" + type;
    }

    private static boolean valid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return false;
        boolean zero = true, ones = true;
        for (byte b : bytes) { zero &= b == 0; ones &= b == -1; }
        return !zero && !ones;
    }

    private static synchronized SecretKey storageKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
