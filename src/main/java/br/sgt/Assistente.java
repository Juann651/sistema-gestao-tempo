package br.sgt;

import br.sgt.AssistenteParser.Acao;
import br.sgt.AssistenteParser.Comando;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Assistente de comandos do SGT. Recebe o texto do pedido (na skill da Alexa, o que o usuário falou), entende o pedido
 * e executa pelo {@link TarefaService}, ou seja, com as mesmas regras (inclusive o modo foco) das telas.
 */
@Service
public class Assistente {
    /** aguarda = a Assistente fez uma pergunta e espera a resposta (a conversa por voz continua aberta). */
    public record Resposta(String texto, boolean recarregar, boolean aguarda) { }
    public record Pendente(String tipo, String id, String nome) { }

    /** Onde o assistente guarda a pergunta que espera resposta (na skill de voz, são os atributos da sessão da Alexa). */
    public interface Memoria {
        Pendente pegar();
        void guardar(Pendente p);
        void limpar();
        /** Quantas tarefas ler/mostrar numa listagem. Voz lê menos. */
        default int limiteLista() { return 12; }
    }

    private final TarefaService svc;

    public Assistente(TarefaService svc) { this.svc = svc; }

    private static Resposta diz(String t) { return new Resposta(t, false, false); }
    private static Resposta fez(String t) { return new Resposta(t, true, false); }
    private static Resposta espera(String t) { return new Resposta(t, false, true); }

    public Resposta responder(Acesso s, Memoria m, String texto) {
        if (texto == null || texto.isBlank()) return diz("Pode falar! Diga \"ajuda\" para ver o que eu sei fazer.");
        String t = texto.trim();
        if (t.length() > 500) t = t.substring(0, 500);
        List<Tarefa> ts = svc.listar(s);

        Pendente p = m.pegar();
        if (p != null) {
            m.limpar();
            Resposta r = tratarPendente(s, m, p, t);
            if (r != null) return r;
        }
        return executar(s, m, AssistenteParser.interpretar(t, ts), ts);
    }

    // ---------- conversas em duas etapas ----------
    private Resposta tratarPendente(Acesso s, Memoria m, Pendente p, String texto) {
        String n = AssistenteParser.sem(texto);
        boolean nao = n.matches("^(?:nao|n|cancela|cancelar|deixa|esquece|melhor nao)(?![a-z]).*");
        if (p.tipo().equals("REMOVER")) {
            if (n.matches("^(?:sim|s|pode|confirmo|confirma|claro|isso|ok|certeza|remove|remova)(?![a-z]).*")) {
                svc.remover(s, p.id());
                return fez("Pronto, removi «" + p.nome() + "».");
            }
            return nao ? diz("Tudo bem, não removi nada.") : null;
        }
        // GASTO: esperando o tempo gasto para concluir
        if (nao) return diz("Ok, não concluí «" + p.nome() + "».");
        Integer seg = AssistenteParser.tempoLivre(n);
        if (seg == null) {
            m.guardar(p);
            return espera("Não entendi o tempo. Diga algo como \"45 min\" ou \"1h 30min\" (ou \"cancelar\").");
        }
        return concluir(s, p.id(), p.nome(), seg);
    }

    private Resposta concluir(Acesso s, String id, String nome, int seg) {
        svc.concluir(s, id, seg);
        return fez("Concluí «" + nome + "» com esforço de " + Tempo.legivel(seg) + ". Bom trabalho!");
    }

    // ---------- execução ----------
    private Resposta executar(Acesso s, Memoria m, Comando c, List<Tarefa> ts) {
        return switch (c.acao) {
            case AJUDA -> diz(AJUDA);
            case CRIAR -> criar(s, c);
            case EDITAR, MOVER, CONCLUIR, REABRIR, REMOVER, INICIAR -> sobreTarefa(s, m, c, ts);
            case CAPACIDADE -> capacidade(s, c);
            case LISTAR -> listar(m, c, ts);
            case CONTAR -> contar(ts);
            case STATUS -> status(ts);
            case DESCONHECIDO -> diz("Não entendi. Diga \"ajuda\" para ver exemplos do que eu faço.");
        };
    }

    private static final String AJUDA = """
            Oi! Sou o assistente do SGT. Posso controlar suas tarefas. Exemplos:
            • criar tarefa "Revisar contrato" prioridade alta urgência média duração 1h30 na tarde
            • editar revisar contrato duração 2h / mudar prioridade de revisar contrato para baixa
            • renomear revisar contrato para Revisar contrato final
            • mover revisar contrato para a madrugada
            • iniciar revisar contrato (liga o modo foco)
            • concluir revisar contrato gastei 1h20
            • reabrir / remover revisar contrato
            • capacidade da manhã para 3h 30min
            • listar tarefas da noite / quantas tarefas / resumo / quanto tempo falta
            Dica: use aspas no nome quando ele tiver palavras como "tarde" ou "noite".""";

    private Resposta criar(Acesso s, Comando c) {
        if (c.nome == null || c.nome.isBlank())
            return diz("Qual o nome da tarefa? Ex.: criar tarefa \"Revisar relatório\" prioridade alta, 1h30, na tarde.");
        List<String> padroes = new ArrayList<>();
        Nivel pr = c.prioridade != null ? c.prioridade : pad(padroes, "prioridade", Nivel.MEDIA);
        Nivel ur = c.urgencia != null ? c.urgencia : pad(padroes, "urgência", Nivel.MEDIA);
        Nivel cp = c.complexidade != null ? c.complexidade : pad(padroes, "complexidade", Nivel.MEDIA);
        int seg = 3600;
        if (c.duracaoSeg != null) seg = Math.max(1, c.duracaoSeg); else padroes.add("duração");
        Periodo turno = c.turno;
        if (turno == null) { turno = turnoAgora(); padroes.add("turno"); }
        svc.criar(s, TarefaService.dadosSeg(c.nome, c.descricao, pr, ur, cp, seg, turno, null));
        String r = "Criei «" + c.nome + "»: prioridade " + pr.getRotulo() + ", urgência " + ur.getRotulo()
                + ", complexidade " + cp.getRotulo() + ", " + Tempo.legivel(seg) + ", turno " + turno.getRotulo() + ".";
        if (!padroes.isEmpty()) r += " (Usei o padrão em: " + String.join(", ", padroes) + ".)";
        return fez(r);
    }

    private static <T> T pad(List<String> usados, String campo, T valor) { usados.add(campo); return valor; }

    private static Periodo turnoAgora() {
        int h = LocalTime.now(ZoneId.of("America/Sao_Paulo")).getHour();
        return h < 6 ? Periodo.MADRUGADA : h < 12 ? Periodo.MANHA : h < 18 ? Periodo.TARDE : Periodo.NOITE;
    }

    private Resposta sobreTarefa(Acesso s, Memoria m, Comando c, List<Tarefa> ts) {
        if (c.alvo == null) return semAlvo(c, ts);
        Tarefa t = c.alvo;
        switch (c.acao) {
            case INICIAR -> {
                svc.iniciar(s, t.id());
                return fez("Iniciei «" + t.nome() + "». Reservei " + t.duracaoFmt()
                        + " e, até acabar, só dá para concluir essa tarefa.");
            }
            case REABRIR -> { svc.reabrir(s, t.id()); return fez("Reabri «" + t.nome() + "»."); }
            case REMOVER -> {
                svc.exigirLivre(ts);   // já recusa agora, em vez de pedir confirmação e falhar depois
                m.guardar(new Pendente("REMOVER", t.id(), t.nome()));
                return espera("Remover «" + t.nome() + "» de vez? Responda \"sim\" para confirmar.");
            }
            case MOVER -> {
                if (c.turno == null) return diz("Para qual turno? Madrugada, manhã, tarde ou noite.");
                svc.mover(s, t.id(), c.turno);
                return fez("Movi «" + t.nome() + "» para " + c.turno.getRotulo() + ".");
            }
            case CONCLUIR -> {
                int seg = c.gastoSeg != null ? c.gastoSeg : t.emAndamento() ? (int) t.decorridoSeg(System.currentTimeMillis()) : 0;
                if (seg < 1) {
                    m.guardar(new Pendente("GASTO", t.id(), t.nome()));
                    return espera("Quanto tempo você gastou em «" + t.nome() + "»? (ex.: 45 min ou 1h 30min)");
                }
                return concluir(s, t.id(), t.nome(), seg);
            }
            default -> { return editar(s, c, t); }
        }
    }

    private Resposta editar(Acesso s, Comando c, Tarefa t) {
        Map<String, Object> d = new HashMap<>();
        List<String> mudou = new ArrayList<>();
        if (c.nome != null && !c.nome.isBlank()) { d.put("nome", c.nome); mudou.add("nome → «" + c.nome + "»"); }
        if (c.descricao != null) { d.put("descricao", c.descricao); mudou.add("descrição"); }
        if (c.prioridade != null) { d.put("prioridade", c.prioridade.getPeso()); mudou.add("prioridade → " + c.prioridade.getRotulo()); }
        if (c.urgencia != null) { d.put("urgencia", c.urgencia.getPeso()); mudou.add("urgência → " + c.urgencia.getRotulo()); }
        if (c.complexidade != null) { d.put("complexidade", c.complexidade.getPeso()); mudou.add("complexidade → " + c.complexidade.getRotulo()); }
        if (c.duracaoSeg != null) {
            int seg = Math.max(1, c.duracaoSeg);
            d.put("duracao", (seg + 59) / 60); d.put("duracao_seg", seg); mudou.add("duração → " + Tempo.legivel(seg));
        }
        if (c.turno != null) { d.put("periodo", c.turno.getCodigo()); mudou.add("turno → " + c.turno.getRotulo()); }
        if (d.isEmpty())
            return diz("O que você quer mudar em «" + t.nome() + "»? Nome, descrição, prioridade, urgência, complexidade, duração ou turno.");
        svc.editar(s, t.id(), d);
        return fez("Atualizei «" + t.nome() + "»: " + String.join(", ", mudou) + ".");
    }

    private Resposta semAlvo(Comando c, List<Tarefa> ts) {
        if (!c.ambiguas.isEmpty())
            return diz("Encontrei mais de uma tarefa parecida: " + nomes(c.ambiguas) + ". Qual delas? Diga o nome completo.");
        String citado = c.alvoTexto == null || c.alvoTexto.isBlank() ? "" : " (\"" + c.alvoTexto + "\")";
        String dica = switch (c.acao) { case CONCLUIR, INICIAR, MOVER -> " pendente"; case REABRIR -> " concluída"; default -> ""; };
        List<Tarefa> pool = ts.stream().filter(t -> switch (c.acao) {
            case CONCLUIR, INICIAR, MOVER -> !t.concluida();
            case REABRIR -> t.concluida();
            default -> true; }).limit(6).toList();
        return diz("Não encontrei essa tarefa" + dica + citado + "."
                + (pool.isEmpty() ? " Você não tem tarefas nessa situação." : " Algumas que você tem: " + nomes(pool) + "."));
    }

    private static String nomes(List<Tarefa> l) {
        return l.stream().map(t -> "«" + t.nome() + "»").collect(Collectors.joining(", "));
    }

    private Resposta capacidade(Acesso s, Comando c) {
        if (c.turno == null) return diz("De qual turno? Madrugada, manhã, tarde ou noite.");
        if (c.capacidadeSeg == null)
            return diz("Quanto tempo de capacidade para a " + c.turno.getRotulo().toLowerCase() + "? Ex.: capacidade da " + c.turno.getRotulo().toLowerCase() + " para 3h 30min.");
        svc.capacidade(s, c.turno, c.capacidadeSeg);
        return fez("Capacidade do turno " + c.turno.getRotulo() + " agora é " + Tempo.legivel(c.capacidadeSeg) + ".");
    }

    // ---------- consultas ----------
    private Resposta listar(Memoria m, Comando c, List<Tarefa> ts) {
        boolean concl = Boolean.TRUE.equals(c.concluidas);
        List<Tarefa> l = ts.stream().filter(t -> t.concluida() == concl && (c.turno == null || t.turno() == c.turno))
                .sorted(concl ? Comparator.comparing(Tarefa::nome) : Comparator.comparingInt(Tarefa::score).reversed()).toList();
        String tipo = concl ? "concluída(s)" : "pendente(s)";
        String onde = c.turno == null ? "" : " na " + c.turno.getRotulo().toLowerCase();
        if (l.isEmpty()) return diz("Nenhuma tarefa " + (concl ? "concluída" : "pendente") + onde + ".");
        StringBuilder b = new StringBuilder("Você tem " + l.size() + " tarefa(s) " + tipo + onde + ":");
        l.stream().limit(m.limiteLista()).forEach(t -> {
            b.append("\n• ").append(t.nome()).append(" — ").append(t.turno().getRotulo()).append(" · ");
            if (concl) b.append("esforço ").append(t.gastoFmt());
            else b.append(t.duracaoFmt()).append(" · prioridade ").append(t.prio().getRotulo()).append(t.emAndamento() ? " · EM ANDAMENTO" : "");
        });
        if (l.size() > m.limiteLista()) b.append("\n… e mais ").append(l.size() - m.limiteLista()).append(".");
        return diz(b.toString());
    }

    private Resposta contar(List<Tarefa> ts) {
        long feitas = ts.stream().filter(Tarefa::concluida).count(), pend = ts.size() - feitas;
        Map<Periodo, Long> porTurno = new LinkedHashMap<>();
        for (Periodo p : Periodo.values()) porTurno.put(p, ts.stream().filter(t -> !t.concluida() && t.turno() == p).count());
        String turnos = porTurno.entrySet().stream().map(e -> e.getKey().getRotulo() + " " + e.getValue()).collect(Collectors.joining(", "));
        return diz("Você tem " + ts.size() + " tarefa(s) cadastrada(s): " + pend + " pendente(s) e " + feitas + " concluída(s). Pendentes por turno: " + turnos + ".");
    }

    private Resposta status(List<Tarefa> ts) {
        long agora = System.currentTimeMillis();
        long feitas = ts.stream().filter(Tarefa::concluida).count(), pend = ts.size() - feitas;
        long gasto = ts.stream().filter(Tarefa::concluida).mapToLong(Tarefa::tempoGasto).sum();
        long planejado = ts.stream().filter(t -> !t.concluida()).mapToLong(Tarefa::duracaoSeg).sum();
        StringBuilder b = new StringBuilder();
        Tarefa a = TarefaService.emAndamento(ts);
        if (a == null) b.append("Nenhuma tarefa em andamento. ");
        else if (a.reservaAtiva(agora))
            b.append("Em foco: «").append(a.nome()).append("» — faltam ").append(Tempo.legivel(a.restanteSeg(agora))).append(" do tempo reservado (outras ações bloqueadas). ");
        else
            b.append("«").append(a.nome()).append("» passou do tempo reservado em ").append(Tempo.legivel((agora - a.fimMs()) / 1000)).append("; conclua quando terminar. ");
        b.append(pend).append(" pendente(s) (").append(Tempo.legivel(planejado)).append(" planejados) e ")
                .append(feitas).append(" concluída(s) com ").append(Tempo.legivel(gasto)).append(" de esforço.");
        return diz(b.toString());
    }
}
