package br.sgt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Lacra textos com AES-GCM usando um segredo do servidor. Serve para o "código" do OAuth ser sem estado (nada a guardar). */
public final class Cofre {
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private final SecretKeySpec chave;

    public Cofre(String segredo) {
        try {
            this.chave = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(segredo.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String selar(String texto) {
        try {
            byte[] iv = new byte[12];
            ALEATORIO.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(128, iv));
            byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] tudo = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, tudo, 0, iv.length);
            System.arraycopy(cifrado, 0, tudo, iv.length, cifrado.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tudo);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Devolve o texto original, ou null se o código foi adulterado ou não é deste servidor. */
    public String abrir(String selado) {
        try {
            byte[] tudo = Base64.getUrlDecoder().decode(selado);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(128, Arrays.copyOfRange(tudo, 0, 12)));
            return new String(c.doFinal(tudo, 12, tudo.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }
}
