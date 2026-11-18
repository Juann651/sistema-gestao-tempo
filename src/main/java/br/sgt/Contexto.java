package br.sgt;

import br.sgt.SupabaseService.Sessao;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Dados comuns a todas as telas: modo foco, relógio do servidor e a chama 🔥 do topo. */
@Component
public class Contexto {
    private final ConsistenciaService cons;
    private final TarefaService svc;
    private final SupabaseService sb;

    public Contexto(ConsistenciaService cons, TarefaService svc, SupabaseService sb) {
        this.cons = cons; this.svc = svc; this.sb = sb;
    }

    public static Sessao sessao(HttpSession s) {
        Sessao x = (Sessao) s.getAttribute("sessao");
        if (x == null) throw new ErroSupabase(401, "Entre para continuar.");
        return x;
    }

    /** Estado do modo foco (tarefa em andamento, bloqueio, relógio do servidor). Devolve se está bloqueado. */
    public boolean base(Model m, List<Tarefa> ts, String pagina) {
        long agora = System.currentTimeMillis();
        Tarefa ativa = TarefaService.emAndamento(ts);
        boolean bloqueado = TarefaService.travadora(ts, agora) != null;
        m.addAttribute("ativa", ativa);
        m.addAttribute("bloqueado", bloqueado);
        m.addAttribute("agora", agora);
        m.addAttribute("pagina", pagina);
        return bloqueado;
    }

    /** base + fechamento dos dias (no máximo a cada 30 s) para mostrar a chama e avisar de conquistas novas. */
    public boolean preparar(Model m, HttpSession s, List<Tarefa> ts, String pagina) {
        boolean b = base(m, ts, pagina);
        ConsistenciaService.Atualizacao a = cons.atualizar(sessao(s).userId(), ts, false);
        aplicar(m, a.seq(), a.novas());
        return b;
    }

    public void aplicar(Model m, Sequencia seq, List<Conquista> novas) {
        if (seq != null) m.addAttribute("chama", seq.atual());
        if (novas != null && !novas.isEmpty()) {
            Object antes = m.asMap().get("aviso");
            m.addAttribute("aviso", (antes == null ? "" : antes + " ") + texto(novas));
        }
    }

    /** Fecha os dias vencidos agora, antes de uma ação que poderia alterar o resultado de um dia (ex.: remover tarefa). */
    public void fecharDias(HttpSession s) {
        cons.atualizar(sessao(s).userId(), svc.listar(sb.web(s)), true);
    }

    /** Depois de concluir uma tarefa: confere conquistas novas e deixa o aviso para a próxima tela. */
    public void avisarConquistas(HttpSession s) {
        try {
            ConsistenciaService.Atualizacao a = cons.atualizar(sessao(s).userId(), svc.listar(sb.web(s)), true);
            if (!a.novas().isEmpty()) s.setAttribute("aviso", texto(a.novas()));
        } catch (RuntimeException ignorada) { /* a conclusão já foi feita; o aviso é só um extra */ }
    }

    public static String texto(List<Conquista> novas) {
        return "🏆 Conquista desbloqueada: " + novas.stream().map(c -> c.icone() + " " + c.nome()).collect(Collectors.joining(", "));
    }
}
