package com.ipreporter.server;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class SecretCipher {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public SecretCipher(AppProperties properties) {
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(properties.encryptionKey()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("APP_ENCRYPTION_KEY must be Base64 of exactly 32 bytes"); }
        if(bytes.length!=32) throw new IllegalArgumentException("APP_ENCRYPTION_KEY must be Base64 of exactly 32 bytes");
        key=new SecretKeySpec(bytes,"AES");
    }
    // 每条凭据使用独立随机 nonce 与 GCM 认证标签，数据库备份不含明文 Secret。
    public String encrypt(String plaintext) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(encrypted);
        } catch(Exception error) { throw new IllegalStateException("Credential encryption failed"); }
    }
    public String decrypt(String ciphertext) {
        try {
            String[] fields=ciphertext.split("\\.",2);
            byte[] nonce=Base64.getDecoder().decode(fields[0]);
            if(nonce.length!=12) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(Base64.getDecoder().decode(fields[1])),StandardCharsets.UTF_8);
        } catch(Exception error) { throw new IllegalStateException("Credential decryption failed; verify APP_ENCRYPTION_KEY"); }
    }
}
