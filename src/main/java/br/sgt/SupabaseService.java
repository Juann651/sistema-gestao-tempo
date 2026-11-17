package br.sgt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Fala com o Supabase: login (Auth) e tarefas/capacidades (PostgREST), sempre com o token do usuário. */
@Service
public class SupabaseService {
    public record Sessao(String access, String refresh, String userId, String email) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient http = RestClient.create();
    private final String url, key;

    public SupabaseService(@Value("${supabase.url}") String url, @Value("${supabase.key}") String key) {
        this.url = url.trim().replaceAll("/+$", "");
        this.key = key.trim();
    }

    // ---------- login ----------
    public Sessao entrar(String email, String senha) {
        return sessao(enviar(req(HttpMethod.POST, "/auth/v1/token?grant_type=password", null), Map.of("email", email, "password", senha)));
    }

    /** Devolve a sessão se o Supabase já liberou o acesso, ou null se ainda falta confirmar o e-mail. */
    public Sessao cadastrar(String email, String senha) {
        JsonNode n = enviar(req(HttpMethod.POST, "/auth/v1/signup", null), Map.of("email", email, "password", senha));
        return n != null && n.hasNonNull("access_token") ? sessao(n) : null;
    }

    /** Troca um refresh token por uma sessão nova (o Supabase gira o refresh token a cada uso). */
    public Sessao renovar(String refresh) {
        return sessao(enviar(req(HttpMethod.POST, "/auth/v1/token?grant_type=refresh_token", null), Map.of("refresh_token", refresh)));
    }

    private Sessao sessao(JsonNode n) {
        JsonNode u = n.get("user");
        return new Sessao(n.get("access_token").asText(), n.get("refresh_token").asText(), u.get("id").asText(), u.path("email").asText());
    }

    /** Executa f com a sessão atual; se o token expirou, renova uma vez e tenta de novo. */
    public <T> T chamar(HttpSession http, Function<Sessao, T> f) {
        Sessao atual = (Sessao) http.getAttribute("sessao");
        if (atual == null) throw new ErroSupabase(401, "Entre para continuar.");
        try {
            return f.apply(atual);
        } catch (ErroSupabase e) {
            if (e.status != 401) throw e;
            Sessao nova;
            try { nova = renovar(atual.refresh()); } catch (ErroSupabase e2) { throw new ErroSupabase(401, "Sessão expirada. Entre novamente."); }
            http.setAttribute("sessao", nova);
            return f.apply(nova);
        }
    }


    // ---------- acesso em nome de um usuário ----------
    /** Acesso pelo navegador: usa (e renova, se preciso) a sessão guardada no HttpSession. */
    public Acesso web(HttpSession http) {
        return new Acesso() {
            @Override public <T> T chamar(Function<Sessao, T> f) { return SupabaseService.this.chamar(http, f); }
        };
    }

    /** Acesso pela Alexa: o token da conta vinculada é o próprio JWT do Supabase (a Alexa cuida de renová-lo). */
    public Acesso porToken(String jwt) {
        Sessao s = sessaoDoJwt(jwt);
        return new Acesso() {
            @Override public <T> T chamar(Function<Sessao, T> f) { return f.apply(s); }
        };
    }

    private static JsonNode corpoDoJwt(String jwt) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        } catch (Exception e) {
            throw new ErroSupabase(401, "Conta não vinculada.");
        }
    }

    private static Sessao sessaoDoJwt(String jwt) {
        if (jwt == null || jwt.isBlank()) throw new ErroSupabase(401, "Conta não vinculada.");
        JsonNode c = corpoDoJwt(jwt);
        if (!c.hasNonNull("sub") || (c.hasNonNull("exp") && c.get("exp").asLong() <= Instant.now().getEpochSecond()))
            throw new ErroSupabase(401, "A vinculação com a conta expirou.");
        return new Sessao(jwt, null, c.get("sub").asText(), c.path("email").asText(""));
    }

    /** Segundos que faltam para o JWT expirar (para o campo expires_in do OAuth). */
    public static long segundosAteExpirar(String jwt) {
        JsonNode c = corpoDoJwt(jwt);
        return c.hasNonNull("exp") ? Math.max(1, c.get("exp").asLong() - Instant.now().getEpochSecond()) : 3600;
    }

    // ---------- dados ----------
    public List<Tarefa> tarefas(Sessao s) {
        JsonNode n = enviar(req(HttpMethod.GET, "/rest/v1/tarefas?select=*&order=criada_em", s.access()), null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Tarefa>>() { });
    }

    /** Capacidade de cada turno, em SEGUNDOS. Usa a coluna "segundos"; se ela estiver vazia, converte os "minutos" antigos. */
    public Map<Periodo, Integer> capacidades(Sessao s) {
        Map<Periodo, Integer> m = new EnumMap<>(Periodo.class);
        for (Periodo p : Periodo.values()) m.put(p, p.getCapacidadePadraoSeg());
        JsonNode n = enviar(req(HttpMethod.GET, "/rest/v1/capacidades?select=periodo,minutos,segundos", s.access()), null);
        if (n != null) n.forEach(x -> {
            int seg = x.hasNonNull("segundos") ? x.get("segundos").asInt() : x.path("minutos").asInt() * 60;
            m.put(Periodo.de(x.get("periodo").asText()), seg);
        });
        return m;
    }

    /** id vazio = cadastrar; com id = atualizar só os campos enviados. */
    public void salvarTarefa(Sessao s, String id, Map<String, Object> dados) {
        if (id == null || id.isBlank()) enviar(req(HttpMethod.POST, "/rest/v1/tarefas", s.access()), dados);
        else enviar(req(HttpMethod.PATCH, "/rest/v1/tarefas?id=eq." + uuid(id), s.access()), dados);
    }

    public void remover(Sessao s, String id) {
        enviar(req(HttpMethod.DELETE, "/rest/v1/tarefas?id=eq." + uuid(id), s.access()), null);
    }

    /** Grava em segundos; "minutos" continua preenchido para manter compatibilidade com a coluna antiga. */
    public void salvarCapacidade(Sessao s, Periodo p, int segundos) {
        enviar(req(HttpMethod.POST, "/rest/v1/capacidades?on_conflict=user_id,periodo", s.access())
                .header("Prefer", "resolution=merge-duplicates"),
                Map.of("user_id", s.userId(), "periodo", p.getCodigo(), "minutos", segundos / 60, "segundos", segundos));
    }

    // ---------- infraestrutura ----------
    private static String uuid(String id) {
        try { return UUID.fromString(id).toString(); } catch (IllegalArgumentException e) { throw new ErroSupabase(400, "Tarefa inválida."); }
    }

    private RestClient.RequestBodySpec req(HttpMethod m, String caminho, String token) {
        return http.method(m).uri(url + caminho)
                .header("apikey", key)
                .header("Authorization", "Bearer " + (token != null ? token : key))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode enviar(RestClient.RequestBodySpec r, Object corpo) {
        if (corpo != null) r.body(corpo);
        return r.retrieve()
                .onStatus(HttpStatusCode::isError, (rq, rs) -> { throw erro(rs.getStatusCode().value(), rs.getBody()); })
                .body(JsonNode.class);
    }

    private ErroSupabase erro(int status, InputStream corpo) {
        String m = "Erro " + status + " ao falar com o Supabase.";
        try {
            JsonNode n = JSON.readTree(corpo);
            for (String c : new String[]{"msg", "error_description", "message", "error"})
                if (n.hasNonNull(c)) { m = n.get(c).asText(); break; }
        } catch (Exception ignorada) { }
        if (m.contains("Invalid login")) m = "E-mail ou senha incorretos.";
        else if (m.contains("not confirmed")) m = "Confirme seu e-mail antes de entrar.";
        else if (m.contains("already registered")) m = "Este e-mail já está cadastrado.";
        return new ErroSupabase(status, m);
    }
}
