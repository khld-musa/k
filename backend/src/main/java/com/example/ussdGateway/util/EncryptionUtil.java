package com.example.ussdGateway.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility with proper key derivation (PBKDF2).
 * Each encryption produces a unique ciphertext due to random IV.
 * Format: Base64(IV[12] || ciphertext || authTag[16])
 */
@Slf4j
@Component
public class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit auth tag
    private static final int KEY_LENGTH = 256;       // AES-256
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    @Value("${encryption.secret}")
    private String secretKey;

    @Value("${encryption.salt:}")
    private String configuredSalt;

    private SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("encryption.secret must be configured");
        }
        if (secretKey.contains("changeme") || secretKey.contains("change-in-prod") || secretKey.contains("change-me") || secretKey.contains("your-32-byte") || secretKey.contains("placeholder") || secretKey.contains("dev-only")) {
            throw new IllegalStateException(
                    "encryption.secret contains a default placeholder value ('changeme' etc). "
                    + "Set a strong, unique ENCRYPTION_SECRET environment variable.");
        }

        // Use configured salt or fall back to a default (for backward compatibility)
        byte[] salt;
        if (configuredSalt != null && !configuredSalt.isBlank()) {
            salt = configuredSalt.getBytes(StandardCharsets.UTF_8);
        } else {
            log.warn("No encryption.salt configured. Using built-in default salt. "
                    + "Set ENCRYPTION_SALT environment variable for production.");
            salt = "kraken-ssh-encryption-salt-v2".getBytes(StandardCharsets.UTF_8);
        }

        try {
            // Derive a 256-bit key from the secret using PBKDF2
            KeySpec spec = new PBEKeySpec(secretKey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] derivedKey = factory.generateSecret(spec).getEncoded();
            this.keySpec = new SecretKeySpec(derivedKey, "AES");
            log.info("EncryptionUtil initialized with AES-256-GCM + PBKDF2 key derivation");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize encryption key", e);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext: IV[12] || ciphertext+tag
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encrypted.length);
            byteBuffer.put(iv);
            byteBuffer.put(encrypted);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting data", e);
        }
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isEmpty()) {
            return encryptedValue;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedValue);

            // Extract IV from the first 12 bytes
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] original = cipher.doFinal(ciphertext);
            return new String(original, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting data", e);
        }
    }
}

