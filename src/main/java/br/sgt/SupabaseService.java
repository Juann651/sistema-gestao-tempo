package br.sgt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
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
    private final String url, key, serviceKey;

    /**
     * serviceKey = chave "service_role" do Supabase. Fica SÓ no servidor (variável SUPABASE_SERVICE_KEY), nunca vai
     * para o navegador. Com ela o servidor grava a consistência, as conquistas e os papéis, que o usuário comum
     * não pode alterar. Vazia = esses recursos ficam desligados e o resto do SGT funciona normalmente.
     */
    public SupabaseService(@Value("${supabase.url}") String url, @Value("${supabase.key}") String key,
                           @Value("${supabase.service-key:}") String serviceKey) {
        this.url = url.trim().replaceAll("/+$", "");
        this.key = key.trim();
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
    }

    public boolean temServico() { return !serviceKey.isEmpty(); }

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

    // ---------- perfis e papéis (USER / ADMIN) ----------
    /** Perfil de quem está logado (o próprio usuário consegue ler o seu, pela regra RLS). */
    public Perfil perfil(Sessao s) {
        JsonNode n = enviar(req(HttpMethod.GET, "/rest/v1/perfis?select=*&id=eq." + id(s.userId()), s.access()), null);
        List<Perfil> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Perfil>>() { });
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Perfil> perfis() {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/perfis?select=*&order=criado_em", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Perfil>>() { });
    }

    public void definirPapel(String userId, String papel) {
        servico(HttpMethod.PATCH, "/rest/v1/perfis?id=eq." + id(userId), Map.of("role", papel), null);
    }

    // ---------- configurações globais (regras da consistência) ----------
    public Map<String, String> configuracoes() {
        Map<String, String> m = new LinkedHashMap<>();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/configuracoes?select=chave,valor", null, null);
        if (n != null) n.forEach(x -> m.put(x.get("chave").asText(), x.get("valor").asText()));
        return m;
    }

    public void salvarConfiguracao(String chave, String valor) {
        servico(HttpMethod.POST, "/rest/v1/configuracoes?on_conflict=chave", Map.of("chave", chave, "valor", valor),
                "resolution=merge-duplicates");
    }

    // ---------- conquistas (catálogo global) ----------
    public List<Conquista> conquistas() {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/conquistas?select=*&order=tipo,requisito", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Conquista>>() { });
    }

    /** id vazio = criar; com id = atualizar só os campos enviados. Nunca apaga desbloqueios de usuários. */
    public void salvarConquista(String id, Map<String, Object> dados) {
        if (id == null || id.isBlank()) servico(HttpMethod.POST, "/rest/v1/conquistas", dados, null);
        else servico(HttpMethod.PATCH, "/rest/v1/conquistas?id=eq." + id(id), dados, null);
    }

    /** conquista_id -> data/hora do desbloqueio, só do usuário informado. */
    public Map<String, String> conquistasDoUsuario(String userId) {
        Map<String, String> m = new LinkedHashMap<>();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/conquistas_usuario?select=conquista_id,desbloqueada_em&user_id=eq." + id(userId), null, null);
        if (n != null) n.forEach(x -> m.put(x.get("conquista_id").asText(), x.path("desbloqueada_em").asText("")));
        return m;
    }

    public void desbloquear(String userId, String conquistaId) {
        servico(HttpMethod.POST, "/rest/v1/conquistas_usuario?on_conflict=user_id,conquista_id",
                Map.of("user_id", id(userId), "conquista_id", id(conquistaId)), "resolution=ignore-duplicates");
    }

    /** conquista_id -> quantos usuários já desbloquearam (para o painel do administrador). */
    public Map<String, Integer> desbloqueiosPorConquista() {
        Map<String, Integer> m = new LinkedHashMap<>();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/conquistas_usuario?select=conquista_id", null, null);
        if (n != null) n.forEach(x -> m.merge(x.get("conquista_id").asText(), 1, Integer::sum));
        return m;
    }

    // ---------- sequência, dias fechados e descanso ----------
    public Sequencia sequencia(String userId) {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/sequencias?select=*&user_id=eq." + id(userId), null, null);
        List<Sequencia> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Sequencia>>() { });
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Sequencia> sequencias() {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/sequencias?select=*", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Sequencia>>() { });
    }

    public void salvarSequencia(Sequencia q) {
        servico(HttpMethod.POST, "/rest/v1/sequencias?on_conflict=user_id", q.paraBanco(), "resolution=merge-duplicates");
    }

    public void salvarRegistros(List<Map<String, Object>> registros) {
        if (registros.isEmpty()) return;
        servico(HttpMethod.POST, "/rest/v1/registros_dia?on_conflict=user_id,data", registros, "resolution=merge-duplicates");
    }

    /** Dias já encerrados do usuário, do mais novo para o mais antigo. desde = null traz tudo (até 2000 dias). */
    public List<RegistroDia> registros(String userId, LocalDate desde) {
        String filtro = desde == null ? "" : "&data=gte." + desde;
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/registros_dia?select=*&user_id=eq." + id(userId) + filtro + "&order=data.desc&limit=2000", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<RegistroDia>>() { });
    }

    public Set<LocalDate> diasDescanso(String userId) {
        Set<LocalDate> s = new HashSet<>();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/dias_descanso?select=data&user_id=eq." + id(userId), null, null);
        if (n != null) n.forEach(x -> s.add(LocalDate.parse(x.get("data").asText())));
        return s;
    }

    public void marcarDescanso(String userId, LocalDate dia) {
        servico(HttpMethod.POST, "/rest/v1/dias_descanso?on_conflict=user_id,data",
                Map.of("user_id", id(userId), "data", dia.toString()), "resolution=ignore-duplicates");
    }

    public void removerDescanso(String userId, LocalDate dia) {
        servico(HttpMethod.DELETE, "/rest/v1/dias_descanso?user_id=eq." + id(userId) + "&data=eq." + dia, null, null);
    }

    // ---------- grupos (família / turma) — sempre pelo servidor, com a chave de serviço ----------
    private static final java.util.regex.Pattern CODIGO = java.util.regex.Pattern.compile("[A-Z0-9]{4,12}");

    private static String lista(Iterable<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String x : ids) { if (b.length() > 0) b.append(','); b.append(id(x)); }
        return b.toString();
    }

    public Grupo criarGrupo(String nome, String tipo, String donoId, String codigo) {
        JsonNode n = servico(HttpMethod.POST, "/rest/v1/grupos",
                Map.of("nome", nome, "tipo", tipo, "dono_id", id(donoId), "codigo", codigo), "return=representation");
        List<Grupo> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Grupo>>() { });
        if (l.isEmpty()) throw new ErroSupabase(500, "Não foi possível criar o grupo.");
        return l.get(0);
    }

    public Grupo grupo(String grupoId) {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/grupos?select=*&id=eq." + id(grupoId), null, null);
        List<Grupo> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Grupo>>() { });
        return l.isEmpty() ? null : l.get(0);
    }

    public Grupo grupoPorCodigo(String codigo) {
        if (codigo == null || !CODIGO.matcher(codigo).matches()) return null;
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/grupos?select=*&codigo=eq." + codigo, null, null);
        List<Grupo> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Grupo>>() { });
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Grupo> gruposDoUsuario(String userId) {
        List<String> ids = new ArrayList<>();
        JsonNode m = servico(HttpMethod.GET, "/rest/v1/grupo_membros?select=grupo_id&user_id=eq." + id(userId), null, null);
        if (m != null) m.forEach(x -> ids.add(x.get("grupo_id").asText()));
        if (ids.isEmpty()) return List.of();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/grupos?select=*&order=criado_em&id=in.(" + lista(ids) + ")", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Grupo>>() { });
    }

    public List<String> idsMembros(String grupoId) {
        List<String> ids = new ArrayList<>();
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/grupo_membros?select=user_id&grupo_id=eq." + id(grupoId), null, null);
        if (n != null) n.forEach(x -> ids.add(x.get("user_id").asText()));
        return ids;
    }

    /** grupo_id -> quantidade de membros, para a lista de grupos. */
    public Map<String, Integer> contagemMembros(List<String> grupoIds) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (grupoIds.isEmpty()) return m;
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/grupo_membros?select=grupo_id&grupo_id=in.(" + lista(grupoIds) + ")", null, null);
        if (n != null) n.forEach(x -> m.merge(x.get("grupo_id").asText(), 1, Integer::sum));
        return m;
    }

    public Map<String, Perfil> perfisPorId(List<String> userIds) {
        Map<String, Perfil> m = new LinkedHashMap<>();
        if (userIds.isEmpty()) return m;
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/perfis?select=*&id=in.(" + lista(userIds) + ")", null, null);
        List<Perfil> l = n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Perfil>>() { });
        for (Perfil p : l) m.put(p.id(), p);
        return m;
    }

    public void adicionarMembro(String grupoId, String userId) {
        servico(HttpMethod.POST, "/rest/v1/grupo_membros?on_conflict=grupo_id,user_id",
                Map.of("grupo_id", id(grupoId), "user_id", id(userId)), "resolution=ignore-duplicates");
    }

    public void removerMembro(String grupoId, String userId) {
        servico(HttpMethod.DELETE, "/rest/v1/grupo_membros?grupo_id=eq." + id(grupoId) + "&user_id=eq." + id(userId), null, null);
    }

    /** Ao sair/ser removido, as tarefas já recebidas viram tarefas pessoais (nada é apagado). */
    public void desvincularTarefas(String grupoId, String userId) {
        Map<String, Object> d = new java.util.HashMap<>();
        d.put("grupo_id", null);
        d.put("grupo_tarefa_id", null);
        servico(HttpMethod.PATCH, "/rest/v1/tarefas?grupo_id=eq." + id(grupoId) + "&user_id=eq." + id(userId), d, null);
    }

    public void excluirGrupo(String grupoId) {
        servico(HttpMethod.DELETE, "/rest/v1/grupos?id=eq." + id(grupoId), null, null);
    }

    public void trocarCodigo(String grupoId, String codigo) {
        servico(HttpMethod.PATCH, "/rest/v1/grupos?id=eq." + id(grupoId), Map.of("codigo", codigo), null);
    }

    public List<Tarefa> tarefasDoGrupo(String grupoId) {
        JsonNode n = servico(HttpMethod.GET, "/rest/v1/tarefas?select=*&grupo_id=eq." + id(grupoId) + "&order=data.desc&limit=500", null, null);
        return n == null ? List.of() : JSON.convertValue(n, new TypeReference<List<Tarefa>>() { });
    }

    public void criarTarefasDoGrupo(List<Map<String, Object>> linhas) {
        if (!linhas.isEmpty()) servico(HttpMethod.POST, "/rest/v1/tarefas", linhas, null);
    }

    public void removerTarefaDoGrupo(String grupoId, String grupoTarefaId) {
        servico(HttpMethod.DELETE, "/rest/v1/tarefas?grupo_id=eq." + id(grupoId) + "&grupo_tarefa_id=eq." + id(grupoTarefaId), null, null);
    }

    // ---------- infraestrutura ----------
    private static String id(String valor) {
        try { return UUID.fromString(valor).toString(); } catch (Exception e) { throw new ErroSupabase(400, "Identificador inválido."); }
    }

    /** Chamada com a chave de serviço (só o servidor tem). Um 401/403 aqui é problema de configuração, não do usuário. */
    private JsonNode servico(HttpMethod m, String caminho, Object corpo, String prefer) {
        if (serviceKey.isEmpty()) throw new ErroSupabase(503, "Recurso indisponível: configure SUPABASE_SERVICE_KEY no servidor.");
        try {
            RestClient.RequestBodySpec r = http.method(m).uri(url + caminho)
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .contentType(MediaType.APPLICATION_JSON);
            if (prefer != null) r.header("Prefer", prefer);
            return enviar(r, corpo);
        } catch (ErroSupabase e) {
            if (e.status == 401 || e.status == 403)
                throw new ErroSupabase(502, "O servidor não conseguiu usar a chave de serviço do Supabase. Confira SUPABASE_SERVICE_KEY.");
            throw e;
        }
    }

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
