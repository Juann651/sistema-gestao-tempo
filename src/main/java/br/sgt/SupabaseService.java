package br.sgt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
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

    private Sessao renovar(String refresh) {
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

    // ---------- dados ----------
    public List<Tarefa> tarefas(Sessao s) {
        JsonNode n = enviar(req(HttpMethod.GET, "/rest/v1/tarefas?select=*&order=criada_em", s.access()), null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Tarefa>>() { });
    }

    public Map<Periodo, Integer> capacidades(Sessao s) {
        Map<Periodo, Integer> m = new EnumMap<>(Periodo.class);
        for (Periodo p : Periodo.values()) m.put(p, p.getCapacidadePadrao());
        JsonNode n = enviar(req(HttpMethod.GET, "/rest/v1/capacidades?select=periodo,minutos", s.access()), null);
        if (n != null) n.forEach(x -> m.put(Periodo.de(x.get("periodo").asText()), x.get("minutos").asInt()));
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

    public void salvarCapacidade(Sessao s, Periodo p, int minutos) {
        enviar(req(HttpMethod.POST, "/rest/v1/capacidades?on_conflict=user_id,periodo", s.access())
                .header("Prefer", "resolution=merge-duplicates"),
                Map.of("user_id", s.userId(), "periodo", p.getCodigo(), "minutos", minutos));
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
