package br.sgt;

import br.sgt.MotorConsistencia.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Teste simples (sem JUnit). Rode: java -cp target/classes:target/test-classes br.sgt.MotorConsistenciaTeste */
public class MotorConsistenciaTeste {
    static int falhas = 0;
    static void esperar(String nome, Object obtido, Object esperado) {
        boolean ok = obtido.equals(esperado);
        if (!ok) falhas++;
        System.out.println((ok ? "OK    " : "FALHA ") + nome + (ok ? "" : "  obtido=" + obtido + " esperado=" + esperado));
    }
    static List<TarefaDia> tarefas(int feitas, int total, Periodo p) {
        List<TarefaDia> l = new ArrayList<>();
        for (int i = 0; i < total; i++) l.add(new TarefaDia(i < feitas, p));
        return l;
    }
    static Passo dia(Estado e, int feitas, int total, boolean descanso, Config c) {
        return MotorConsistencia.aplicar(e, MotorConsistencia.avaliar(LocalDate.of(2026, 1, 1), tarefas(feitas, total, Periodo.MANHA), descanso, c), c);
    }

    public static void main(String[] a) {
        Config c = Config.padrao();
        Estado zero = new Estado(0, 0, 0, 0, 0);

        esperar("3 de 5 (60%) não ganha o dia", dia(zero, 3, 5, false, c).resultado(), Resultado.PERDEU);
        esperar("4 de 5 (80%) ganha o dia", dia(zero, 4, 5, false, c).resultado(), Resultado.GANHOU);
        esperar("7 de 10 (70%) ganha (limite exato)", dia(zero, 7, 10, false, c).resultado(), Resultado.GANHOU);
        esperar("sem tarefas é neutro", dia(new Estado(5, 9, 0, 0, 0), 0, 0, false, c).resultado(), Resultado.NEUTRO);
        esperar("dia de descanso é neutro (mesmo sem fazer nada)", dia(new Estado(5, 9, 0, 0, 0), 0, 4, true, c).resultado(), Resultado.NEUTRO);
        esperar("neutro não altera a sequência", dia(new Estado(5, 9, 0, 0, 0), 0, 0, false, c).estado(), new Estado(5, 9, 0, 0, 0));

        // exemplo do GPT: seg feito, ter feito, qua neutro, qui feito => 3 dias
        Estado e = dia(zero, 5, 5, false, c).estado();
        e = dia(e, 5, 5, false, c).estado();
        e = dia(e, 0, 0, false, c).estado();
        e = dia(e, 5, 5, false, c).estado();
        esperar("seg, ter, (neutro), qui = 3 dias", e.atual(), 3);

        // perda sem protetor: zera a sequência, recorde fica
        Passo p = dia(new Estado(12, 18, 0, 0, 0), 1, 5, false, c);
        esperar("perde sem protetor: sequência 0", p.estado().atual(), 0);
        esperar("perde sem protetor: recorde mantido", p.estado().recorde(), 18);

        // protetor: ganho a cada 7 dias, e uso ao falhar
        Estado s = zero;
        for (int i = 0; i < 7; i++) s = dia(s, 5, 5, false, c).estado();
        esperar("7 dias seguidos dão 1 protetor", s.protetores(), 1);
        Passo salvo = dia(s, 0, 5, false, c);
        esperar("falha com protetor => PROTEGIDO", salvo.resultado(), Resultado.PROTEGIDO);
        esperar("protetor mantém a sequência", salvo.estado().atual(), 7);
        esperar("protetor é consumido", salvo.estado().protetores(), 0);

        // teto de protetores
        Estado t = zero;
        for (int i = 0; i < 28; i++) t = dia(t, 5, 5, false, c).estado();
        esperar("28 dias: protetores limitados ao máximo (2)", t.protetores(), 2);
        esperar("protetorACada=0 desliga os protetores",
                dia(new Estado(6, 6, 0, 0, 0), 5, 5, false, new Config(70, 80, 0, 2)).estado().protetores(), 0);

        // alta conclusão e turno completo
        esperar("8 de 10 conta como dia de alta conclusão (80%)", dia(zero, 8, 10, false, c).estado().diasAlta(), 1);
        esperar("7 de 10 não conta como alta conclusão", dia(zero, 7, 10, false, c).estado().diasAlta(), 0);
        esperar("turno 100% feito conta", dia(zero, 5, 5, false, c).estado().diasTurno(), 1);

        // progresso de hoje
        Progresso pr = MotorConsistencia.progresso(tarefas(1, 5, Periodo.MANHA), c);
        esperar("hoje 1/5: faltam 3 para bater 70%", pr.faltam(), 3);
        esperar("hoje 1/5: meta não batida", pr.meta(), false);
        esperar("hoje 4/5: meta batida", MotorConsistencia.progresso(tarefas(4, 5, Periodo.MANHA), c).meta(), true);
        esperar("hoje sem tarefas: sem meta", MotorConsistencia.progresso(List.of(), c).meta(), false);

        // conquistas
        Metricas m = new Metricas(30, 100, 12, 7);
        esperar("sequência 30 desbloqueia 30", MotorConsistencia.atingiu("SEQUENCIA", 30, m), true);
        esperar("sequência 30 não desbloqueia 60", MotorConsistencia.atingiu("SEQUENCIA", 60, m), false);
        esperar("100 tarefas", MotorConsistencia.atingiu("TOTAL_TAREFAS", 100, m), true);
        esperar("30 dias de alta conclusão ainda não (12)", MotorConsistencia.atingiu("DIAS_ALTA_CONCLUSAO", 30, m), false);
        esperar("7 dias de turno completo", MotorConsistencia.atingiu("DIAS_TURNO_COMPLETO", 7, m), true);
        esperar("tipo desconhecido nunca desbloqueia", MotorConsistencia.atingiu("XYZ", 1, m), false);

        System.out.println(falhas == 0 ? "\nTodos os testes passaram." : "\n" + falhas + " teste(s) falharam.");
        if (falhas > 0) System.exit(1);
    }
}
