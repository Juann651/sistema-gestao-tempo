package br.sgt;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Todas as ações do usuário sobre tarefas e capacidades passam por aqui (telas E Assistente),
 * então a regra do modo foco vale igual para os dois.
 *
 * Modo foco: ao iniciar uma tarefa, o tempo reservado dela (duração) fica "travado". Enquanto ele
 * corre, a única ação permitida é concluir a tarefa em andamento. Passado o tempo, o bloqueio cai
 * sozinho, mas a tarefa continua em andamento até ser concluída.
 */
@Service
public class TarefaService {
    public static final int LIMITE_CAPACIDADE_SEG = 24 * 3600;
    private final SupabaseService sb;

    public TarefaService(SupabaseService sb) { this.sb = sb; }

    // ---------- consultas ----------
    public List<Tarefa> listar(Acesso s) { return s.chamar(sb::tarefas); }
    public Map<Periodo, Integer> capacidades(Acesso s) { return s.chamar(sb::capacidades); }

    public static Tarefa emAndamento(List<Tarefa> ts) {
        for (Tarefa t : ts) if (t.emAndamento()) return t;
        return null;
    }

    /** A tarefa cuja reserva de tempo ainda corre (a que trava o resto), ou null. */
    public static Tarefa travadora(List<Tarefa> ts, long agoraMs) {
        for (Tarefa t : ts) if (t.reservaAtiva(agoraMs)) return t;
        return null;
    }

    // ---------- validação ----------
    public static Map<String, Object> dados(String nome, String descricao, Nivel prioridade, Nivel urgencia,
                                            Nivel complexidade, int duracaoMin, Periodo turno) {
        if (nome == null || nome.isBlank()) throw new ErroSupabase(400, "Informe o nome da tarefa.");
        if (duracaoMin < 1) throw new ErroSupabase(400, "A duração deve ser maior que zero.");
        Map<String, Object> d = new HashMap<>();
        d.put("nome", nome.trim());
        d.put("descricao", descricao == null ? "" : descricao.trim());
        d.put("prioridade", prioridade.getPeso());
        d.put("urgencia", urgencia.getPeso());
        d.put("complexidade", complexidade.getPeso());
        d.put("duracao", duracaoMin);
        d.put("periodo", turno.getCodigo());
        return d;
    }

    /** Igual ao método acima, mas escolhendo o dia da tarefa (null = hoje, que é o padrão do banco). */
    public static Map<String, Object> dados(String nome, String descricao, Nivel prioridade, Nivel urgencia,
                                            Nivel complexidade, int duracaoMin, Periodo turno, LocalDate dia) {
        Map<String, Object> d = dados(nome, descricao, prioridade, urgencia, complexidade, duracaoMin, turno);
        if (dia != null) {
            if (dia.isBefore(Tempo.hoje())) throw new ErroSupabase(400, "Não é possível planejar uma tarefa para um dia que já passou.");
            if (dia.isAfter(Tempo.hoje().plusDays(365))) throw new ErroSupabase(400, "Escolha uma data dentro dos próximos 12 meses.");
            d.put("data", dia.toString());
        }
        return d;
    }

    /** Tarefas planejadas para um dia. */
    public static List<Tarefa> doDia(List<Tarefa> ts, LocalDate dia) {
        return ts.stream().filter(t -> t.dia().equals(dia)).toList();
    }

    /** Tarefas de dias anteriores que não foram concluídas nem replanejadas: o usuário decide o que fazer com elas. */
    public static List<Tarefa> pendentesAnteriores(List<Tarefa> ts, LocalDate hoje) {
        return ts.stream().filter(t -> t.dia().isBefore(hoje) && !t.concluida() && !t.replanejada()).toList();
    }

    // ---------- bloqueio ----------
    /** Lança erro se o modo foco estiver ativo (usado antes de qualquer ação que não seja concluir a tarefa em andamento). */
    public void exigirLivre(List<Tarefa> ts) {
        long agora = System.currentTimeMillis();
        Tarefa t = travadora(ts, agora);
        if (t != null)
            throw new ErroSupabase(423, "Modo foco em «" + t.nome() + "»: faltam " + Tempo.legivel(t.restanteSeg(agora))
                    + " do tempo reservado. Até lá, só é possível concluir essa tarefa.");
    }

    private static Tarefa achar(List<Tarefa> ts, String id) {
        return ts.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ErroSupabase(404, "Tarefa não encontrada."));
    }

    private void gravar(Acesso s, String id, Map<String, Object> d) {
        s.chamar(x -> { sb.salvarTarefa(x, id, d); return null; });
    }

    // ---------- ações ----------
    public void criar(Acesso s, Map<String, Object> dados) {
        exigirLivre(listar(s));
        gravar(s, null, dados);
    }

    /** Atualiza só os campos enviados. */
    public Tarefa editar(Acesso s, String id, Map<String, Object> dados) {
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        exigirLivre(ts);
        gravar(s, id, dados);
        return t;
    }

    public Tarefa mover(Acesso s, String id, Periodo turno) {
        Map<String, Object> d = new HashMap<>();
        d.put("periodo", turno.getCodigo());
        return editar(s, id, d);
    }

    public Tarefa remover(Acesso s, String id) {
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        exigirLivre(ts);
        s.chamar(x -> { sb.remover(x, id); return null; });
        return t;
    }

    /**
     * Replaneja uma tarefa pendente de um dia anterior: cria uma cópia para hoje e marca a original como replanejada.
     * A original continua no histórico daquele dia (o resultado dele não muda); nada é movido automaticamente.
     */
    public Tarefa replanejar(Acesso s, String id) {
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        exigirLivre(ts);
        if (t.concluida()) throw new ErroSupabase(400, "«" + t.nome() + "» já está concluída.");
        if (t.replanejada()) throw new ErroSupabase(400, "«" + t.nome() + "» já foi replanejada.");
        if (!t.dia().isBefore(Tempo.hoje())) throw new ErroSupabase(400, "Só tarefas de dias anteriores podem ser replanejadas.");
        gravar(s, null, dados(t.nome(), t.descricao(), Nivel.de(t.prioridade()), Nivel.de(t.urgencia()),
                Nivel.de(t.complexidade()), t.duracao(), t.turno(), Tempo.hoje()));
        Map<String, Object> d = new HashMap<>();
        d.put("replanejada_em", Instant.now().toString());
        d.put("iniciada_em", null);   // uma tarefa antiga "em andamento" não pode continuar travando as outras
        gravar(s, id, d);
        return t;
    }

    public Tarefa iniciar(Acesso s, String id) {
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        if (t.concluida()) throw new ErroSupabase(400, "«" + t.nome() + "» já está concluída.");
        Tarefa atual = emAndamento(ts);
        if (atual != null)
            throw new ErroSupabase(423, atual.id().equals(id) ? "«" + t.nome() + "» já está em andamento."
                    : "Conclua «" + atual.nome() + "» antes de iniciar outra tarefa.");
        Map<String, Object> d = new HashMap<>();
        d.put("iniciada_em", Instant.now().toString());
        gravar(s, id, d);
        return t;
    }

    /** Conclui registrando o esforço (segundos) e quem fez. Permitida mesmo durante o modo foco, se for a tarefa em andamento. */
    public Tarefa concluir(Acesso s, String id, int segundosGastos) {
        if (segundosGastos < 1) throw new ErroSupabase(400, "Informe o tempo gasto (maior que zero).");
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        Tarefa trava = travadora(ts, System.currentTimeMillis());
        if (trava != null && !trava.id().equals(id)) exigirLivre(ts);
        s.chamar(x -> {
            Map<String, Object> d = new HashMap<>();
            d.put("concluida", true);
            d.put("tempo_gasto", segundosGastos);
            d.put("concluida_em", Instant.now().toString());
            d.put("concluida_por", x.email());
            sb.salvarTarefa(x, id, d);
            return null;
        });
        return t;
    }

    public Tarefa reabrir(Acesso s, String id) {
        List<Tarefa> ts = listar(s);
        Tarefa t = achar(ts, id);
        exigirLivre(ts);
        Map<String, Object> d = new HashMap<>();
        d.put("concluida", false);
        d.put("concluida_em", null);
        d.put("concluida_por", null);
        d.put("iniciada_em", null);
        gravar(s, id, d);
        return t;
    }

    public void capacidade(Acesso s, Periodo turno, int segundos) {
        if (segundos < 0) throw new ErroSupabase(400, "A capacidade não pode ser negativa.");
        if (segundos > LIMITE_CAPACIDADE_SEG) throw new ErroSupabase(400, "A capacidade de um turno não pode passar de 24 horas.");
        exigirLivre(listar(s));
        s.chamar(x -> { sb.salvarCapacidade(x, turno, segundos); return null; });
    }
}
