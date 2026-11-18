package br.sgt;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Regras da consistência 🔥, sem banco e sem Spring (por isso é fácil de testar).
 *
 * Um dia fechado termina em um de quatro resultados:
 *  - GANHOU: cumpriu a meta (padrão 70% das tarefas planejadas) -> sequência +1;
 *  - NEUTRO: sem tarefas ou dia de descanso planejado -> sequência não muda;
 *  - PROTEGIDO: não cumpriu, mas havia um protetor 🛡️ -> gasta o protetor, sequência mantida;
 *  - PERDEU: não cumpriu e não havia protetor -> sequência volta a 0 (o recorde nunca diminui).
 * A cada N dias seguidos (padrão 7) o usuário ganha um protetor, até o máximo configurado.
 */
public final class MotorConsistencia {
    private MotorConsistencia() { }

    public enum Resultado {
        GANHOU("ganhou"), PERDEU("perdeu"), NEUTRO("neutro"), PROTEGIDO("protegido");
        private final String codigo;
        Resultado(String codigo) { this.codigo = codigo; }
        public String codigo() { return codigo; }
    }

    /** Regras configuráveis pelo administrador. protetorACada = 0 desliga os protetores. */
    public record Config(int limiarDia, int limiarAlto, int protetorACada, int maxProtetores) {
        public static Config padrao() { return new Config(70, 80, 7, 2); }
    }

    /** Uma tarefa planejada para o dia: se foi feita dentro do dia e em que turno estava. */
    public record TarefaDia(boolean feita, Periodo turno) { }

    public record Dia(LocalDate data, int total, int concluidas, int taxa, boolean descanso, boolean meta,
                      boolean alta, Set<Periodo> turnosCompletos) {
        public boolean neutro() { return descanso || total == 0; }
    }

    public record Estado(int atual, int recorde, int protetores, int diasAlta, int diasTurno) { }

    public record Passo(Estado estado, Resultado resultado) { }

    /** Situação do dia de hoje (ainda aberto). faltam = tarefas que ainda precisam ser feitas para bater a meta. */
    public record Progresso(int total, int feitas, int taxa, int faltam, boolean meta) { }

    /** Números usados pelas conquistas que não dependem só da sequência. */
    public record Metricas(int recorde, int tarefasConcluidas, int diasAlta, int diasTurno) { }

    public static Dia avaliar(LocalDate data, List<TarefaDia> tarefas, boolean descanso, Config cfg) {
        int total = tarefas.size();
        int feitas = (int) tarefas.stream().filter(TarefaDia::feita).count();
        int taxa = total == 0 ? 0 : feitas * 100 / total;
        boolean meta = total > 0 && feitas * 100 >= cfg.limiarDia() * total;
        boolean alta = total > 0 && feitas * 100 >= cfg.limiarAlto() * total;
        Set<Periodo> completos = EnumSet.noneOf(Periodo.class);
        for (Periodo p : Periodo.values()) {
            long doTurno = tarefas.stream().filter(t -> t.turno() == p).count();
            long feitasTurno = tarefas.stream().filter(t -> t.turno() == p && t.feita()).count();
            if (doTurno > 0 && doTurno == feitasTurno) completos.add(p);
        }
        return new Dia(data, total, feitas, taxa, descanso, meta, alta, completos);
    }

    public static Passo aplicar(Estado e, Dia d, Config cfg) {
        if (d.neutro()) return new Passo(e, Resultado.NEUTRO);
        int altas = e.diasAlta() + (d.alta() ? 1 : 0);
        int turnos = e.diasTurno() + (d.turnosCompletos().isEmpty() ? 0 : 1);
        if (d.meta()) {
            int atual = e.atual() + 1;
            int prot = e.protetores();
            if (cfg.protetorACada() > 0 && atual % cfg.protetorACada() == 0 && prot < cfg.maxProtetores()) prot++;
            return new Passo(new Estado(atual, Math.max(e.recorde(), atual), prot, altas, turnos), Resultado.GANHOU);
        }
        if (e.protetores() > 0)
            return new Passo(new Estado(e.atual(), e.recorde(), e.protetores() - 1, altas, turnos), Resultado.PROTEGIDO);
        return new Passo(new Estado(0, e.recorde(), e.protetores(), altas, turnos), Resultado.PERDEU);
    }

    public static Progresso progresso(List<TarefaDia> tarefas, Config cfg) {
        int total = tarefas.size();
        int feitas = (int) tarefas.stream().filter(TarefaDia::feita).count();
        int taxa = total == 0 ? 0 : feitas * 100 / total;
        int necessarias = (cfg.limiarDia() * total + 99) / 100;
        return new Progresso(total, feitas, taxa, Math.max(0, necessarias - feitas), total > 0 && feitas >= necessarias);
    }

    /** Valor atual do usuário para um tipo de conquista (usado para desbloquear e para a barra de progresso). */
    public static int valorAtual(String tipo, Metricas m) {
        return switch (tipo == null ? "" : tipo) {
            case "SEQUENCIA" -> m.recorde();
            case "TOTAL_TAREFAS" -> m.tarefasConcluidas();
            case "DIAS_ALTA_CONCLUSAO" -> m.diasAlta();
            case "DIAS_TURNO_COMPLETO" -> m.diasTurno();
            default -> 0;
        };
    }

    public static boolean atingiu(String tipo, int requisito, Metricas m) {
        return requisito > 0 && valorAtual(tipo, m) >= requisito;
    }
}
