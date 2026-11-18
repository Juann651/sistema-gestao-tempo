package br.sgt;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Grupos: criar, entrar por código, dividir tarefas entre os membros e acompanhar o progresso. */
@Controller
@RequestMapping("/grupos")
public class GrupoController {
    private final SupabaseService sb;
    private final TarefaService svc;
    private final GrupoService grupos;
    private final Contexto ctx;

    public GrupoController(SupabaseService sb, TarefaService svc, GrupoService grupos, Contexto ctx) {
        this.sb = sb; this.svc = svc; this.grupos = grupos; this.ctx = ctx;
    }

    private static String uid(HttpSession s) { return Contexto.sessao(s).userId(); }

    @GetMapping
    String lista(HttpSession s, Model m) {
        ctx.preparar(m, s, svc.listar(sb.web(s)), "grupos");
        m.addAttribute("ativo", grupos.ativo());
        if (grupos.ativo()) {
            try { m.addAttribute("itens", grupos.listar(uid(s))); }
            catch (ErroSupabase e) { m.addAttribute("falha", "Não foi possível carregar os grupos. Confira se a seção 6 do schema.sql foi executada no Supabase. Detalhe: " + e.getMessage()); }
        }
        return "grupos";
    }

    @PostMapping
    String criar(@RequestParam String nome, @RequestParam String tipo, HttpSession s) {
        Grupo g = grupos.criar(uid(s), nome, tipo);
        s.setAttribute("aviso", "Grupo criado! Compartilhe o código " + g.codigo() + " com quem vai participar.");
        return "redirect:/grupos/" + g.id();
    }

    @PostMapping("/entrar")
    String entrar(@RequestParam String codigo, HttpSession s) {
        Grupo g = grupos.entrar(uid(s), codigo);
        s.setAttribute("aviso", "Você entrou no grupo «" + g.nome() + "».");
        return "redirect:/grupos/" + g.id();
    }

    @GetMapping("/{id}")
    String ver(@PathVariable String id, HttpSession s, Model m) {
        ctx.preparar(m, s, svc.listar(sb.web(s)), "grupos");
        ctx.opcoes(m);
        m.addAttribute("p", grupos.painel(uid(s), id));
        m.addAttribute("hojeIso", Tempo.hoje().toString());
        return "grupo";
    }

    @PostMapping("/{id}/tarefas")
    String novaTarefa(@PathVariable String id, @RequestParam String nome, @RequestParam(defaultValue = "") String descricao,
                      @RequestParam int prioridade, @RequestParam int urgencia, @RequestParam int complexidade,
                      @RequestParam int duracao, @RequestParam String periodo,
                      @RequestParam(required = false) String dia, @RequestParam(required = false) List<String> atribuidos,
                      HttpSession s) {
        LocalDate quando = null;
        if (dia != null && !dia.isBlank()) {
            try { quando = LocalDate.parse(dia); } catch (Exception e) { throw new ErroSupabase(400, "Data inválida."); }
        }
        Map<String, Object> d = TarefaService.dados(nome, descricao, Nivel.de(prioridade), Nivel.de(urgencia),
                Nivel.de(complexidade), duracao, Periodo.de(periodo), quando);
        grupos.criarTarefa(uid(s), id, d, atribuidos);
        int n = atribuidos == null ? 0 : atribuidos.size();
        s.setAttribute("aviso", "Tarefa enviada para " + n + (n == 1 ? " pessoa." : " pessoas."));
        return "redirect:/grupos/" + id;
    }

    @PostMapping("/{id}/tarefas/{gtid}/remover")
    String removerTarefa(@PathVariable String id, @PathVariable String gtid, HttpSession s) {
        grupos.removerTarefa(uid(s), id, gtid);
        s.setAttribute("aviso", "Tarefa removida de todos os membros.");
        return "redirect:/grupos/" + id;
    }

    @PostMapping("/{id}/membros/{alvo}/remover")
    String removerMembro(@PathVariable String id, @PathVariable String alvo, HttpSession s) {
        grupos.removerMembro(uid(s), id, alvo);
        s.setAttribute("aviso", "Membro removido. As tarefas que ele já recebeu viraram tarefas pessoais.");
        return "redirect:/grupos/" + id;
    }

    @PostMapping("/{id}/codigo")
    String novoCodigo(@PathVariable String id, HttpSession s) {
        grupos.novoCodigo(uid(s), id);
        s.setAttribute("aviso", "Novo código gerado. O código antigo deixou de funcionar.");
        return "redirect:/grupos/" + id;
    }

    @PostMapping("/{id}/sair")
    String sair(@PathVariable String id, HttpSession s) {
        grupos.sair(uid(s), id);
        s.setAttribute("aviso", "Você saiu do grupo. As tarefas que recebeu continuam como tarefas pessoais.");
        return "redirect:/grupos";
    }

    @PostMapping("/{id}/excluir")
    String excluir(@PathVariable String id, HttpSession s) {
        grupos.excluir(uid(s), id);
        s.setAttribute("aviso", "Grupo excluído. As tarefas já enviadas continuam com cada pessoa.");
        return "redirect:/grupos";
    }
}
