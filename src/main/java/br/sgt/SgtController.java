package br.sgt;

import br.sgt.SupabaseService.Sessao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SgtController {
    private final SupabaseService sb;

    public SgtController(SupabaseService sb) { this.sb = sb; }

    public record Coluna(Periodo periodo, List<Tarefa> tarefas, int usado, int capacidade) {
        public int percentual() { return capacidade == 0 ? 100 : Math.min(100, usado * 100 / capacidade); }
        public int excesso() { return Math.max(0, usado - capacidade); }
    }

    @ModelAttribute
    void aviso(HttpSession s, Model m) {
        Object a = s.getAttribute("aviso");
        if (a != null) { m.addAttribute("aviso", a); s.removeAttribute("aviso"); }
    }

    @ExceptionHandler(ErroSupabase.class)
    String erro(ErroSupabase e, HttpServletRequest rq, HttpSession s, Model m) {
        if (e.status == 401) { s.removeAttribute("sessao"); s.setAttribute("aviso", e.getMessage()); return "redirect:/login"; }
        if (rq.getMethod().equals("GET")) { m.addAttribute("mensagem", e.getMessage()); return "erro"; }
        s.setAttribute("aviso", e.getMessage());
        return "redirect:/";
    }

    // ---------- conta ----------
    @GetMapping("/login")
    String login() { return "login"; }

    @PostMapping("/login")
    String entrar(@RequestParam String email, @RequestParam String senha, HttpSession s, Model m) {
        try { s.setAttribute("sessao", sb.entrar(email.trim(), senha)); return "redirect:/"; }
        catch (ErroSupabase e) { m.addAttribute("erro", e.getMessage()); return "login"; }
    }

    @PostMapping("/cadastro")
    String cadastrar(@RequestParam String email, @RequestParam String senha, HttpSession s, Model m) {
        try {
            Sessao nova = sb.cadastrar(email.trim(), senha);
            if (nova != null) { s.setAttribute("sessao", nova); return "redirect:/"; }
            m.addAttribute("ok", "Conta criada. Confirme o e-mail que você recebeu e depois entre.");
        } catch (ErroSupabase e) { m.addAttribute("erro", e.getMessage()); }
        return "login";
    }

    @GetMapping("/sair")
    String sair(HttpSession s) { s.invalidate(); return "redirect:/login"; }

    // ---------- tarefas ----------
    @GetMapping("/")
    String tarefas(@RequestParam(required = false) String editar, HttpSession s, Model m) {
        List<Tarefa> ts = new ArrayList<>(sb.chamar(s, sb::tarefas));
        ts.sort(Comparator.comparing(Tarefa::concluida).thenComparing(Comparator.comparingInt(Tarefa::score).reversed()));
        m.addAttribute("tarefas", ts);
        m.addAttribute("niveis", Nivel.values());
        m.addAttribute("periodos", Periodo.values());
        m.addAttribute("edit", editar == null ? null : ts.stream().filter(t -> t.id().equals(editar)).findFirst().orElse(null));
        m.addAttribute("pagina", "tarefas");
        return "tarefas";
    }

    @PostMapping("/tarefas")
    String salvar(@RequestParam(required = false) String id, @RequestParam String nome,
                  @RequestParam(defaultValue = "") String descricao, @RequestParam int prioridade,
                  @RequestParam int urgencia, @RequestParam int complexidade, @RequestParam int duracao,
                  @RequestParam String periodo, HttpSession s) {
        if (nome.isBlank()) throw new ErroSupabase(400, "Informe o nome da tarefa.");
        if (duracao < 1) throw new ErroSupabase(400, "A duração deve ser maior que zero.");
        Map<String, Object> d = Map.of("nome", nome.trim(), "descricao", descricao.trim(),
                "prioridade", Nivel.de(prioridade).getPeso(), "urgencia", Nivel.de(urgencia).getPeso(),
                "complexidade", Nivel.de(complexidade).getPeso(), "duracao", duracao,
                "periodo", Periodo.de(periodo).getCodigo());
        sb.chamar(s, x -> { sb.salvarTarefa(x, id, d); return null; });
        return "redirect:/";
    }

    @PostMapping("/tarefas/{id}/concluir")
    String concluir(@PathVariable String id, @RequestParam boolean concluida, HttpSession s) {
        sb.chamar(s, x -> { sb.salvarTarefa(x, id, Map.of("concluida", concluida)); return null; });
        return "redirect:/";
    }

    @PostMapping("/tarefas/{id}/remover")
    String remover(@PathVariable String id, HttpSession s) {
        sb.chamar(s, x -> { sb.remover(x, id); return null; });
        return "redirect:/";
    }

    @PostMapping("/tarefas/{id}/turno")
    String turno(@PathVariable String id, @RequestParam String periodo, HttpSession s) {
        sb.chamar(s, x -> { sb.salvarTarefa(x, id, Map.of("periodo", Periodo.de(periodo).getCodigo())); return null; });
        return "redirect:/agenda";
    }

    // ---------- agenda ----------
    private List<Coluna> montarColunas(List<Tarefa> ts, Map<Periodo, Integer> caps) {
        List<Coluna> colunas = new ArrayList<>();
        for (Periodo p : Periodo.values()) {
            List<Tarefa> l = ts.stream().filter(t -> !t.concluida() && t.turno() == p)
                    .sorted(Comparator.comparingInt(Tarefa::score).reversed()).toList();
            colunas.add(new Coluna(p, l, l.stream().mapToInt(Tarefa::duracao).sum(), caps.get(p)));
        }
        return colunas;
    }

    @GetMapping("/agenda")
    String agenda(HttpSession s, Model m) {
        List<Tarefa> ts = sb.chamar(s, sb::tarefas);
        m.addAttribute("colunas", montarColunas(ts, sb.chamar(s, sb::capacidades)));
        m.addAttribute("periodos", Periodo.values());
        m.addAttribute("pagina", "agenda");
        return "agenda";
    }

    // ---------- resumo do dia ----------
    @GetMapping("/resumo")
    String resumo(HttpSession s, Model m) {
        List<Tarefa> ts = sb.chamar(s, sb::tarefas);
        List<Coluna> colunas = montarColunas(ts, sb.chamar(s, sb::capacidades));
        long pendentes = ts.stream().filter(t -> !t.concluida()).count();
        String data = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
                .format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR")));
        m.addAttribute("data", Character.toUpperCase(data.charAt(0)) + data.substring(1));
        m.addAttribute("pendentes", pendentes);
        m.addAttribute("concluidas", ts.size() - pendentes);
        m.addAttribute("usado", colunas.stream().mapToInt(Coluna::usado).sum());
        m.addAttribute("capacidade", colunas.stream().mapToInt(Coluna::capacidade).sum());
        m.addAttribute("excesso", colunas.stream().mapToInt(Coluna::excesso).sum());
        m.addAttribute("proximas", ts.stream().filter(t -> !t.concluida())
                .sorted(Comparator.comparingInt(Tarefa::score).reversed()).limit(3).toList());
        m.addAttribute("colunas", colunas);
        m.addAttribute("pagina", "resumo");
        return "resumo";
    }

    @PostMapping("/capacidade")
    String capacidade(@RequestParam String periodo, @RequestParam int minutos, HttpSession s) {
        if (minutos < 0) throw new ErroSupabase(400, "A capacidade não pode ser negativa.");
        sb.chamar(s, x -> { sb.salvarCapacidade(x, Periodo.de(periodo), minutos); return null; });
        return "redirect:/agenda";
    }
}
