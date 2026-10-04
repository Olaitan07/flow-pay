package com.flowpay.kyc.service;

import com.flowpay.kyc.configuration.KycProperties;
import com.flowpay.kyc.entity.IdType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Protects government ID numbers.
 * <ul>
 *   <li><b>Encryption</b> (AES-256-GCM, random nonce each time): the stored value is unreadable without the key and
 *       any tampering is detected on decryption.</li>
 *   <li><b>Keyed hash</b> (HMAC-SHA-256): lets us notice the same ID used by two customers without decrypting.
 *       It is keyed, so a leaked database cannot be brute-forced offline the way a plain hash of an 11-digit number could.</li>
 * </ul>
 * Two separate keys are derived from the one configured master key so neither use weakens the other.
 */
@Component
public class IdNumberProtector {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;

    public IdNumberProtector(KycProperties properties) {
        if (properties.encryptionKey() == null || properties.encryptionKey().isBlank()) {
            throw new IllegalStateException("KYC_ENCRYPTION_KEY must be configured (base64 of 32 random bytes)");
        }
        byte[] master = Base64.getDecoder().decode(properties.encryptionKey().trim());
        if (master.length < 32) {
            throw new IllegalStateException("KYC_ENCRYPTION_KEY must decode to at least 32 bytes");
        }
        this.encryptionKey = new SecretKeySpec(derive(master, "flowpay-kyc-id-number-encryption"), "AES");
        this.hashKey = new SecretKeySpec(derive(master, "flowpay-kyc-id-number-hash"), "HmacSHA256");
    }

    /** Upper-cases and removes spaces and dashes so "a-123 456" and "A123456" count as the same number. */
    public static String normalise(String idNumber) {
        return idNumber.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public String encrypt(String normalisedNumber) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(normalisedNumber.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt ID number", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt ID number (wrong key or corrupted data)", e);
        }
    }

    /** Same input always gives the same output, which is what makes duplicate detection possible. */
    public String fingerprint(IdType type, String normalisedNumber) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal((type + ":" + normalisedNumber).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String last4(String normalisedNumber) {
        return normalisedNumber.substring(Math.max(0, normalisedNumber.length() - 4));
    }

    /** "*******1234": enough for a customer to recognise the number, useless to anyone else. */
    public static String mask(String last4) {
        return "*".repeat(7) + last4;
    }

    private static byte[] derive(byte[] master, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            byte[] derived = mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
            // sanity: never return an all-zero key
            if (MessageDigest.isEqual(derived, new byte[derived.length])) {
                throw new IllegalStateException("Key derivation failed");
            }
            return derived;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
