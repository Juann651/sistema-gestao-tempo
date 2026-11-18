package br.sgt;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Grupos (família/equipe e turma). Uma tarefa do grupo vira uma CÓPIA para cada membro escolhido: cada pessoa tem
 * o seu contador, o seu modo foco, o seu tempo gasto e a conta no seu dia. O servidor grava essas cópias com a
 * chave de serviço; as regras de quem pode o quê ficam aqui, no servidor.
 */
@Service
public class GrupoService {
    public record Item(Grupo grupo, int membros, boolean dono) { }
    public record Membro(String userId, String email, String nome, boolean dono, boolean voce) { }
    public record Atribuicao(String membro, String status, String gasto) { }
    public record TarefaGrupo(String gtid, String nome, String descricao, Nivel prio, Nivel urg, Nivel comp, Periodo turno,
                              String dia, String duracao, boolean podeRemover, int feitas, int total, int percentual,
                              List<Atribuicao> atribuicoes) { }
    public record Painel(Grupo grupo, boolean dono, boolean podeCriar, boolean veProgresso, List<Membro> membros,
                         List<TarefaGrupo> tarefas) { }

    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom azar = new SecureRandom();
    private final SupabaseService sb;

    public GrupoService(SupabaseService sb) { this.sb = sb; }

    public boolean ativo() { return sb.temServico(); }

    private String novoCodigo() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(ALFABETO.charAt(azar.nextInt(ALFABETO.length())));
        return b.toString();
    }

    private Grupo exigirMembro(String userId, String grupoId) {
        Grupo g = sb.grupo(grupoId);
        if (g == null || !sb.idsMembros(grupoId).contains(userId)) throw new ErroSupabase(404, "Grupo não encontrado.");
        return g;
    }

    private Grupo exigirDono(String userId, String grupoId) {
        Grupo g = exigirMembro(userId, grupoId);
        if (!g.donoId().equals(userId)) throw new ErroSupabase(403, "Só o responsável pelo grupo pode fazer isso.");
        return g;
    }

    /** id do grupo -> nome, para mostrar “👥 Nome” nas tarefas. Nunca lança erro (é só um enfeite). */
    public Map<String, String> nomes(String userId) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!ativo()) return m;
        try { for (Grupo g : sb.gruposDoUsuario(userId)) m.put(g.id(), g.nome()); } catch (RuntimeException ignorada) { }
        return m;
    }

    public List<Item> listar(String userId) {
        List<Grupo> gs = sb.gruposDoUsuario(userId);
        Map<String, Integer> cont = sb.contagemMembros(gs.stream().map(Grupo::id).toList());
        return gs.stream().map(g -> new Item(g, cont.getOrDefault(g.id(), 1), g.donoId().equals(userId))).toList();
    }

    public Grupo criar(String userId, String nome, String tipo) {
        String n = nome == null ? "" : nome.trim();
        if (n.isEmpty()) throw new ErroSupabase(400, "Informe o nome do grupo.");
        if (n.length() > 60) throw new ErroSupabase(400, "O nome do grupo pode ter no máximo 60 caracteres.");
        if (!"FAMILIA".equals(tipo) && !"TURMA".equals(tipo)) throw new ErroSupabase(400, "Escolha o tipo do grupo.");
        Grupo g = null;
        for (int i = 0; i < 5 && g == null; i++) {
            try { g = sb.criarGrupo(n, tipo, userId, novoCodigo()); }
            catch (ErroSupabase e) { if (e.status != 409) throw e; }   // 409 = código repetido: tenta outro
        }
        if (g == null) throw new ErroSupabase(500, "Não foi possível gerar um código para o grupo. Tente de novo.");
        sb.adicionarMembro(g.id(), userId);
        return g;
    }

    public Grupo entrar(String userId, String codigo) {
        String c = codigo == null ? "" : codigo.trim().toUpperCase().replace(" ", "").replace("-", "");
        Grupo g = sb.grupoPorCodigo(c);
        if (g == null) throw new ErroSupabase(404, "Código de convite inválido.");
        sb.adicionarMembro(g.id(), userId);
        return g;
    }

    private static String nomeDe(Perfil p) {
        if (p == null || p.email() == null || p.email().isBlank()) return "Usuário";
        int i = p.email().indexOf('@');
        return i > 0 ? p.email().substring(0, i) : p.email();
    }

    public Painel painel(String userId, String grupoId) {
        Grupo g = exigirMembro(userId, grupoId);
        boolean dono = g.donoId().equals(userId);
        boolean veProgresso = dono || g.familia();
        boolean podeCriar = dono || g.familia();
        List<String> ids = sb.idsMembros(grupoId);
        Map<String, Perfil> perfis = sb.perfisPorId(ids);
        List<Membro> membros = ids.stream()
                .map(id -> new Membro(id, perfis.get(id) == null ? "" : perfis.get(id).email(), nomeDe(perfis.get(id)), id.equals(g.donoId()), id.equals(userId)))
                .sorted(Comparator.comparing(Membro::dono).reversed().thenComparing(Membro::nome))
                .toList();

        // só cópias de quem ainda é membro; quem não vê o progresso enxerga apenas a própria cópia
        List<Tarefa> copias = sb.tarefasDoGrupo(grupoId).stream()
                .filter(t -> ids.contains(t.userId()) && (veProgresso || userId.equals(t.userId())))
                .toList();
        Map<String, List<Tarefa>> porTarefa = new LinkedHashMap<>();
        for (Tarefa t : copias) porTarefa.computeIfAbsent(t.grupoTarefaId() == null ? t.id() : t.grupoTarefaId(), k -> new ArrayList<>()).add(t);

        List<TarefaGrupo> tarefas = new ArrayList<>();
        for (Map.Entry<String, List<Tarefa>> e : porTarefa.entrySet()) {
            if (tarefas.size() >= 40) break;
            List<Tarefa> l = e.getValue();
            Tarefa r = l.get(0);
            List<Atribuicao> at = l.stream().map(t -> new Atribuicao(nomeDe(perfis.get(t.userId())),
                    t.concluida() ? "feita" : t.emAndamento() ? "andamento" : "pendente",
                    t.concluida() ? t.gastoFmt() : "")).toList();
            int feitas = (int) l.stream().filter(Tarefa::concluida).count();
            tarefas.add(new TarefaGrupo(e.getKey(), r.nome(), r.descricao() == null ? "" : r.descricao(), r.prio(), r.urg(), r.comp(),
                    r.turno(), Tempo.data(r.dia()), r.duracaoFmt(), dono || userId.equals(r.criadaPor()),
                    feitas, l.size(), l.isEmpty() ? 0 : feitas * 100 / l.size(), at));
        }
        return new Painel(g, dono, podeCriar, veProgresso, membros, tarefas);
    }

    public void criarTarefa(String userId, String grupoId, Map<String, Object> dados, List<String> atribuidos) {
        Grupo g = exigirMembro(userId, grupoId);
        if (!(g.donoId().equals(userId) || g.familia()))
            throw new ErroSupabase(403, "Neste grupo só o responsável cria tarefas.");
        List<String> ids = sb.idsMembros(grupoId);
        LinkedHashSet<String> alvo = new LinkedHashSet<>(atribuidos == null ? List.of() : atribuidos);
        if (alvo.isEmpty()) throw new ErroSupabase(400, "Escolha pelo menos uma pessoa para receber a tarefa.");
        for (String a : alvo) if (!ids.contains(a)) throw new ErroSupabase(400, "Só é possível atribuir a membros do grupo.");
        Map<String, Perfil> perfis = sb.perfisPorId(new ArrayList<>(alvo));
        String gtid = UUID.randomUUID().toString();
        List<Map<String, Object>> linhas = new ArrayList<>();
        for (String a : alvo) {
            Map<String, Object> l = new LinkedHashMap<>(dados);
            l.put("user_id", a);
            l.put("email", perfis.get(a) == null ? "" : perfis.get(a).email());
            l.put("grupo_id", grupoId);
            l.put("grupo_tarefa_id", gtid);
            l.put("criada_por", userId);
            linhas.add(l);
        }
        sb.criarTarefasDoGrupo(linhas);
    }

    public void removerTarefa(String userId, String grupoId, String gtid) {
        Grupo g = exigirMembro(userId, grupoId);
        Tarefa r = sb.tarefasDoGrupo(grupoId).stream().filter(t -> gtid.equals(t.grupoTarefaId())).findFirst()
                .orElseThrow(() -> new ErroSupabase(404, "Tarefa do grupo não encontrada."));
        if (!(g.donoId().equals(userId) || userId.equals(r.criadaPor())))
            throw new ErroSupabase(403, "Só quem criou a tarefa (ou o responsável) pode removê-la.");
        sb.removerTarefaDoGrupo(grupoId, gtid);
    }

    public void sair(String userId, String grupoId) {
        Grupo g = exigirMembro(userId, grupoId);
        if (g.donoId().equals(userId)) throw new ErroSupabase(400, "O responsável não pode sair: exclua o grupo ou peça a outra pessoa para criar um novo.");
        sb.desvincularTarefas(grupoId, userId);
        sb.removerMembro(grupoId, userId);
    }

    public void removerMembro(String userId, String grupoId, String alvo) {
        Grupo g = exigirDono(userId, grupoId);
        if (alvo.equals(g.donoId())) throw new ErroSupabase(400, "O responsável não pode ser removido.");
        sb.desvincularTarefas(grupoId, alvo);
        sb.removerMembro(grupoId, alvo);
    }

    /** As tarefas já recebidas continuam com cada pessoa, como tarefas pessoais. */
    public void excluir(String userId, String grupoId) {
        exigirDono(userId, grupoId);
        sb.excluirGrupo(grupoId);
    }

    public void novoCodigo(String userId, String grupoId) {
        exigirDono(userId, grupoId);
        for (int i = 0; i < 5; i++) {
            try { sb.trocarCodigo(grupoId, novoCodigo()); return; }
            catch (ErroSupabase e) { if (e.status != 409) throw e; }
        }
        throw new ErroSupabase(500, "Não foi possível gerar um novo código.");
    }
}
