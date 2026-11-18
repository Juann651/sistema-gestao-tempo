package br.sgt;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Telas de consistência 🔥: painel, conquistas e histórico. Cada usuário só vê os próprios dados. */
@Controller
public class ConsistenciaController {
    private final SupabaseService sb;
    private final TarefaService svc;
    private final ConsistenciaService cons;
    private final Contexto ctx;

    public ConsistenciaController(SupabaseService sb, TarefaService svc, ConsistenciaService cons, Contexto ctx) {
        this.sb = sb; this.svc = svc; this.cons = cons; this.ctx = ctx;
    }

    private static final String SEM_TABELAS = "Não foi possível carregar a consistência. Confira se a seção 5 do schema.sql foi executada no Supabase. Detalhe: ";

    @GetMapping("/consistencia")
    String painel(HttpSession s, Model m) {
        List<Tarefa> ts = svc.listar(sb.web(s));
        ctx.base(m, ts, "consistencia");
        m.addAttribute("sub", "painel");
        m.addAttribute("ativo", cons.ativo());
        if (cons.ativo()) {
            String uid = Contexto.sessao(s).userId();
            ConsistenciaService.Painel p = cons.painel(uid, ts);
            if (p == null) m.addAttribute("falha", SEM_TABELAS + "veja o log do servidor.");
            else {
                ctx.aplicar(m, p.seq(), p.novas());
                m.addAttribute("painel", p);
                try { m.addAttribute("stats", cons.estatisticas(uid, ts)); } catch (ErroSupabase e) { m.addAttribute("falha", SEM_TABELAS + e.getMessage()); }
            }
        }
        m.addAttribute("amanha", Tempo.hoje().plusDays(1).toString());
        m.addAttribute("limite", Tempo.hoje().plusDays(60).toString());
        return "consistencia";
    }

    @GetMapping("/conquistas")
    String conquistas(HttpSession s, Model m) {
        List<Tarefa> ts = svc.listar(sb.web(s));
        ctx.base(m, ts, "conquistas");
        m.addAttribute("sub", "conquistas");
        m.addAttribute("ativo", cons.ativo());
        if (cons.ativo()) {
            ConsistenciaService.PaginaConquistas p = cons.conquistasDoUsuario(Contexto.sessao(s).userId(), ts);
            if (p == null) m.addAttribute("falha", SEM_TABELAS + "veja o log do servidor.");
            else { ctx.aplicar(m, p.seq(), p.novas()); m.addAttribute("pagina", "conquistas"); m.addAttribute("itens", p.itens()); m.addAttribute("desbloqueadas", p.desbloqueadas()); }
        }
        return "conquistas";
    }

    @GetMapping("/historico")
    String historico(HttpSession s, Model m) {
        List<Tarefa> ts = svc.listar(sb.web(s));
        ctx.preparar(m, s, ts, "historico");
        m.addAttribute("sub", "historico");
        m.addAttribute("ativo", cons.ativo());
        if (cons.ativo()) {
            try { m.addAttribute("dias", cons.historico(Contexto.sessao(s).userId(), ts)); }
            catch (ErroSupabase e) { m.addAttribute("falha", SEM_TABELAS + e.getMessage()); }
        }
        return "historico";
    }

    /** Dia de descanso planejado (de amanhã em diante): conta como dia neutro e não interrompe a sequência. */
    @PostMapping("/consistencia/descanso")
    String descanso(@RequestParam String data, @RequestParam(defaultValue = "marcar") String acao, HttpSession s) {
        LocalDate d;
        try { d = LocalDate.parse(data); } catch (Exception e) { throw new ErroSupabase(400, "Data inválida."); }
        String uid = Contexto.sessao(s).userId();
        if (acao.equals("remover")) cons.removerDescanso(uid, d); else cons.marcarDescanso(uid, d);
        return "redirect:/consistencia";
    }
}
