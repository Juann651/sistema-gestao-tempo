package br.sgt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Vinculação de conta da Alexa (OAuth 2.0, "authorization code grant").
 * O usuário entra com o mesmo e-mail e senha do SGT; o token que a Alexa guarda é o JWT do próprio Supabase,
 * então cada pedido por voz roda com as permissões (RLS) daquele usuário. Nada fica guardado no servidor:
 * o código de autorização é um pacote lacrado (AES-GCM) que vale 5 minutos e só pode ser usado uma vez.
 */
@Controller
public class AlexaOAuth {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REDIRECT = Pattern.compile(
            "^https://(?:pitangui|layla)\\.amazon\\.com/api/skill/link/[A-Za-z0-9]+$|^https://alexa\\.amazon\\.co\\.jp/api/skill/link/[A-Za-z0-9]+$");
    private static final long VALIDADE_CODIGO_SEG = 300;

    private final SupabaseService sb;
    private final String clientId, clientSecret;
    private final Cofre cofre;
    private final Map<String, Long> usados = new ConcurrentHashMap<>();

    public AlexaOAuth(SupabaseService sb, @Value("${alexa.secret:}") String segredo,
                      @Value("${alexa.client-id:sgt-alexa}") String clientId, @Value("${alexa.client-secret:}") String clientSecret) {
        this.sb = sb;
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.cofre = segredo.isBlank() ? null : new Cofre(segredo.trim());
    }

    private boolean configurado() { return cofre != null && !clientSecret.isEmpty(); }

    private String problema(String cid, String tipo, String redirect) {
        if (!configurado()) return "A integração com a Alexa não está configurada neste servidor.";
        if (!clientId.equals(cid)) return "Aplicativo desconhecido (client_id).";
        if (!"code".equals(tipo)) return "Tipo de resposta não suportado.";
        if (redirect == null || !REDIRECT.matcher(redirect).matches()) return "Endereço de retorno (redirect_uri) não permitido.";
        return null;
    }

    private static void semCache(HttpServletResponse r) {
        r.setHeader("Cache-Control", "no-store");
        r.setHeader("X-Frame-Options", "DENY");
    }

    // ---------- 1) tela de login ----------
    @GetMapping("/oauth/authorize")
    String formulario(@RequestParam(name = "client_id", required = false) String cid,
                      @RequestParam(name = "response_type", required = false) String tipo,
                      @RequestParam(name = "redirect_uri", required = false) String redirect,
                      @RequestParam(required = false) String state, HttpServletResponse r, Model m) {
        semCache(r);
        String p = problema(cid, tipo, redirect);
        if (p != null) { m.addAttribute("mensagem", p); return "erro"; }
        preencher(m, cid, tipo, redirect, state);
        return "alexa-login";
    }

    private static void preencher(Model m, String cid, String tipo, String redirect, String state) {
        m.addAttribute("clientId", cid); m.addAttribute("tipo", tipo);
        m.addAttribute("redirect", redirect); m.addAttribute("state", state == null ? "" : state);
    }

    // ---------- 2) login feito: devolve o código para a Alexa ----------
    @PostMapping("/oauth/authorize")
    ModelAndView entrar(@RequestParam(name = "client_id", required = false) String cid,
                        @RequestParam(name = "response_type", required = false) String tipo,
                        @RequestParam(name = "redirect_uri", required = false) String redirect,
                        @RequestParam(required = false) String state,
                        @RequestParam String email, @RequestParam String senha, HttpServletResponse r) {
        semCache(r);
        String p = problema(cid, tipo, redirect);
        if (p != null) return new ModelAndView("erro", "mensagem", p);
        try {
            SupabaseService.Sessao s = sb.entrar(email.trim(), senha);
            ObjectNode n = JSON.createObjectNode();
            n.put("a", s.access()); n.put("r", s.refresh()); n.put("e", Instant.now().getEpochSecond() + VALIDADE_CODIGO_SEG);
            String url = redirect + "?code=" + enc(cofre.selar(n.toString())) + "&state=" + enc(state == null ? "" : state);
            return new ModelAndView(new RedirectView(url, false, true, false));
        } catch (ErroSupabase e) {
            ModelAndView mv = new ModelAndView("alexa-login");
            mv.addObject("erro", e.getMessage());
            mv.addObject("clientId", cid); mv.addObject("tipo", tipo); mv.addObject("redirect", redirect); mv.addObject("state", state == null ? "" : state);
            return mv;
        }
    }

    private static String enc(String v) { return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8); }

    // ---------- 3) a Alexa troca o código (ou o refresh token) por tokens ----------
    @PostMapping(value = "/oauth/token", produces = "application/json")
    ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> p,
                                              @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        if (!configurado()) return erro(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable");
        if (!clienteOk(p, auth)) return erro(HttpStatus.UNAUTHORIZED, "invalid_client");
        try {
            String tipo = p.getOrDefault("grant_type", "");
            String access, refresh;
            if (tipo.equals("authorization_code")) {
                String codigo = p.getOrDefault("code", "");
                String claro = cofre.abrir(codigo);
                if (claro == null) return erro(HttpStatus.BAD_REQUEST, "invalid_grant");
                JsonNode n = JSON.readTree(claro);
                long agora = Instant.now().getEpochSecond();
                usados.values().removeIf(exp -> exp < agora);
                if (n.path("e").asLong() < agora || usados.putIfAbsent(codigo, n.path("e").asLong()) != null)
                    return erro(HttpStatus.BAD_REQUEST, "invalid_grant");
                access = n.get("a").asText(); refresh = n.get("r").asText();
            } else if (tipo.equals("refresh_token")) {
                SupabaseService.Sessao nova = sb.renovar(p.getOrDefault("refresh_token", ""));
                access = nova.access(); refresh = nova.refresh();
            } else {
                return erro(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
            }
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("access_token", access);
            corpo.put("token_type", "Bearer");
            corpo.put("expires_in", SupabaseService.segundosAteExpirar(access));
            corpo.put("refresh_token", refresh);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("Pragma", "no-cache").body(corpo);
        } catch (ErroSupabase e) {
            return erro(HttpStatus.BAD_REQUEST, "invalid_grant");
        } catch (Exception e) {
            return erro(HttpStatus.BAD_REQUEST, "invalid_request");
        }
    }

    private boolean clienteOk(Map<String, String> p, String auth) {
        String id = p.get("client_id"), segredo = p.get("client_secret");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String[] par = new String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8).split(":", 2);
                id = par[0]; segredo = par.length > 1 ? par[1] : null;
            } catch (IllegalArgumentException e) { return false; }
        }
        return id != null && segredo != null && igual(id, clientId) & igual(segredo, clientSecret);
    }

    private static boolean igual(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> erro(HttpStatus s, String codigo) {
        return ResponseEntity.status(s).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("error", codigo));
    }
}
