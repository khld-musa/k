package com.example.ussdGateway.util;

import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * JPA AttributeConverter that encrypts/decrypts String columns at the persistence layer.
 *
 * [FIX P0-1] Migrated from AES/ECB (deterministic, insecure) to AES/128-GCM (authenticated, random IV).
 *  - New ciphertexts are prefixed with "GCM:" to distinguish from legacy ECB values.
 *  - Legacy ECB values are still decryptable (backward compat for zero-downtime migration).
 *  - Each save produces a unique ciphertext — identical plaintexts are no longer detectable in DB.
 */
@Slf4j
@Converter
@Component
public class AttributeEncryptor implements AttributeConverter<String, String> {

    private static final String AES_GCM   = "AES/GCM/NoPadding";
    private static final String AES_ECB   = "AES"; // kept only for migrating legacy ECB rows
    private static final int    GCM_IV_LEN  = 12;  // NIST-recommended IV length for GCM
    private static final int    GCM_TAG_LEN = 128; // bits
    private static final String GCM_PREFIX  = "GCM:";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // volatile: Spring may inject this from a non-main thread during context refresh
    private static volatile String SECRET;

    @Value("${app.encryption.secret:SixteenByteSecrt}")
    public void setSecret(String secret) {
        AttributeEncryptor.SECRET = secret;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] key = deriveKey(SECRET);
            byte[] iv  = new byte[GCM_IV_LEN];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // Layout: GCM_PREFIX + Base64(iv[12] || ciphertext+tag)
            byte[] combined = new byte[GCM_IV_LEN + encrypted.length];
            System.arraycopy(iv,        0, combined, 0,          GCM_IV_LEN);
            System.arraycopy(encrypted, 0, combined, GCM_IV_LEN, encrypted.length);
            return GCM_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("[P0-1] GCM encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            if (dbData.startsWith(GCM_PREFIX)) {
                // ── New GCM-encrypted value ──
                byte[] combined   = Base64.getDecoder().decode(dbData.substring(GCM_PREFIX.length()));
                byte[] iv         = Arrays.copyOfRange(combined, 0, GCM_IV_LEN);
                byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LEN, combined.length);

                Cipher cipher = Cipher.getInstance(AES_GCM);
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(deriveKey(SECRET), "AES"),
                        new GCMParameterSpec(GCM_TAG_LEN, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } else {
                // ── [MIGRATION] Legacy AES/ECB value — decrypt and re-encrypt on next save ──
                Cipher cipher = Cipher.getInstance(AES_ECB);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(SECRET), "AES"));
                return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            if (dbData.startsWith(GCM_PREFIX)) {
                // A GCM value that fails to decrypt (e.g. the key changed) must not be handed back as if it were
                // the plaintext - it was being used as the SSH password. Treat it as "no stored value" instead.
                log.error("Failed to decrypt an encrypted column value; treating it as not set: {}", e.getMessage());
                return null;
            }
            // Last resort: return raw value (handles completely unencrypted legacy data)
            return dbData;
        }
    }

    /**
     * Derives a 16-byte (AES-128) key from the configured secret.
     * Truncates to 16 bytes if longer, zero-pads if shorter (production secrets should be ≥16 chars).
     */
    private static byte[] deriveKey(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        return raw.length >= 16 ? Arrays.copyOfRange(raw, 0, 16) : Arrays.copyOf(raw, 16);
    }
}
