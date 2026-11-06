package com.ruoyi.game.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PiiCodec {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public PiiCodec(@Value("${game.pii-key}") String encodedKey){
        byte[] bytes=Base64.getUrlDecoder().decode(encodedKey);
        if(bytes.length!=32)throw new IllegalStateException("GAME_PII_KEY 必须是 32 字节的 Base64URL 密钥");
        key=new SecretKeySpec(bytes,"AES");
    }
    public String encrypt(String value){
        if(value==null||value.isEmpty())return null;
        byte[] iv=new byte[12];random.nextBytes(iv);
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));
            byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] data=Arrays.copyOf(iv,iv.length+ciphertext.length);
            System.arraycopy(ciphertext,0,data,iv.length,ciphertext.length);return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        }catch(GeneralSecurityException e){throw new IllegalStateException("联系方式加密失败",e);}
    }
    public String decrypt(String value){
        if(value==null)return "";
        try{
            if(!value.startsWith("v1."))throw new IllegalArgumentException("密文版本无效");
            byte[] data=Base64.getUrlDecoder().decode(value.substring(3));if(data.length<28)throw new IllegalArgumentException("密文长度无效");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(data,12)));
            return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("联系方式密文不可读",e);}
    }
}
