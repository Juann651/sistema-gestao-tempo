package br.sgt;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confere que a requisição veio mesmo da Amazon, como exigem os requisitos de segurança das skills:
 * URL do certificado, cadeia de certificados, assinatura do corpo, data/hora (até 150 s) e id da skill.
 */
@Component
public class AlexaVerificador {
    public static class Recusada extends RuntimeException {
        public Recusada(String m) { super(m); }
    }

    static final long TOLERANCIA_SEG = 150;
    private final boolean ativo;
    private final String skillId;
    private final X509TrustManager confianca;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, List<X509Certificate>> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AlexaVerificador(@Value("${alexa.verificar:true}") boolean ativo, @Value("${alexa.skill-id:}") String skillId) {
        this(ativo, skillId, confiancaPadrao());
    }

    AlexaVerificador(boolean ativo, String skillId, X509TrustManager confianca) {
        this.ativo = ativo; this.skillId = skillId == null ? "" : skillId.trim(); this.confianca = confianca;
    }

    private static X509TrustManager confiancaPadrao() {
        try {
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init((KeyStore) null);
            for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager x) return x;
            throw new IllegalStateException("Sem gerenciador de confiança X509.");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean ativo() { return ativo; }

    public void verificar(byte[] corpo, String urlCadeia, String assinatura256, String assinaturaSha1, JsonNode req) {
        if (!ativo) return;
        String assinatura = assinatura256 != null ? assinatura256 : assinaturaSha1;
        if (urlCadeia == null || assinatura == null) throw new Recusada("Faltam os cabeçalhos de assinatura.");
        validarUrl(urlCadeia);
        verificarAssinatura(corpo, cadeia(urlCadeia), assinatura, assinatura256 != null ? "SHA256withRSA" : "SHA1withRSA");
        validarHorario(req.at("/request/timestamp").asText(""), Instant.now());
        validarSkill(req);
    }

    // ---------- etapas (visíveis ao pacote para teste) ----------
    static void validarUrl(String url) {
        URI u;
        try { u = URI.create(url).normalize(); } catch (Exception e) { throw new Recusada("URL do certificado inválida."); }
        boolean ok = "https".equalsIgnoreCase(u.getScheme()) && "s3.amazonaws.com".equalsIgnoreCase(u.getHost())
                && u.getPath() != null && u.getPath().startsWith("/echo.api/") && (u.getPort() == -1 || u.getPort() == 443)
                && u.getUserInfo() == null;
        if (!ok) throw new Recusada("URL do certificado não é da Amazon.");
    }

    void verificarAssinatura(byte[] corpo, List<X509Certificate> cadeia, String assinaturaBase64, String algoritmo) {
        try {
            X509Certificate folha = cadeia.get(0);
            folha.checkValidity();
            Collection<List<?>> sans = folha.getSubjectAlternativeNames();
            boolean tem = false;
            if (sans != null) for (List<?> san : sans) if (Integer.valueOf(2).equals(san.get(0)) && "echo-api.amazon.com".equals(san.get(1))) tem = true;
            if (!tem) throw new Recusada("Certificado não é do echo-api.amazon.com.");
            confianca.checkServerTrusted(cadeia.toArray(new X509Certificate[0]), "RSA");
            Signature s = Signature.getInstance(algoritmo);
            s.initVerify(folha.getPublicKey());
            s.update(corpo);
            if (!s.verify(Base64.getDecoder().decode(assinaturaBase64.trim()))) throw new Recusada("Assinatura não confere.");
        } catch (Recusada r) { throw r;
        } catch (Exception e) { throw new Recusada("Não foi possível validar a assinatura."); }
    }

    static void validarHorario(String timestamp, Instant agora) {
        try {
            long dif = Math.abs(Duration.between(Instant.parse(timestamp), agora).getSeconds());
            if (dif > TOLERANCIA_SEG) throw new Recusada("Requisição fora do prazo.");
        } catch (Recusada r) { throw r;
        } catch (Exception e) { throw new Recusada("Data/hora ausente ou inválida."); }
    }

    void validarSkill(JsonNode req) {
        if (skillId.isEmpty()) return;
        String id = req.at("/session/application/applicationId").asText(req.at("/context/System/application/applicationId").asText(""));
        if (!skillId.equals(id)) throw new Recusada("Requisição de outra skill.");
    }

    private List<X509Certificate> cadeia(String url) {
        return cache.computeIfAbsent(url, u -> {
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new Recusada("Não consegui baixar o certificado.");
                CertificateFactory f = CertificateFactory.getInstance("X.509");
                return f.generateCertificates(new ByteArrayInputStream(r.body())).stream().map(c -> (X509Certificate) c).toList();
            } catch (Recusada r) { throw r;
            } catch (Exception e) { throw new Recusada("Não consegui obter o certificado."); }
        });
    }
}
