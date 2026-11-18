package br.sgt;

import br.sgt.SupabaseService.Sessao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SgtController {
    private final SupabaseService sb;
    private final TarefaService svc;
    private final Contexto ctx;

    public SgtController(SupabaseService sb, TarefaService svc, Contexto ctx) { this.sb = sb; this.svc = svc; this.ctx = ctx; }

    /** Uma coluna da agenda. Tempos em SEGUNDOS. */
    public record Coluna(Periodo periodo, List<Tarefa> tarefas, int usado, int capacidade) {
        public int percentual() { return capacidade == 0 ? 100 : (int) Math.min(100, usado * 100L / capacidade); }
        public int excesso() { return Math.max(0, usado - capacidade); }
        public String usadoFmt() { return Tempo.legivel(usado); }
        public String capacidadeFmt() { return Tempo.legivel(capacidade); }
        public String excessoFmt() { return Tempo.legivel(excesso()); }
        public int capH() { return Tempo.horas(capacidade); }
        public int capM() { return Tempo.minutos(capacidade); }
        public int capS() { return Tempo.segundos(capacidade); }
    }

    /** Esforço das tarefas concluídas por usuário. */
    public record Esforco(String usuario, long tarefas, long previstoSeg, long gastoSeg) {
        public String previstoFmt() { return Tempo.legivel(previstoSeg); }
        public String gastoFmt() { return Tempo.legivel(gastoSeg); }
    }

    // ---------- conta ----------
    @GetMapping("/login")
    String login() { return "login"; }

    @PostMapping("/login")
    String entrar(@RequestParam String email, @RequestParam String senha, HttpSession s, Model m) {
        try { s.setAttribute("sessao", sb.entrar(email.trim(), senha)); s.removeAttribute("papel"); return "redirect:/"; }
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

    // ---------- contexto comum às telas ----------
    private boolean contexto(Model m, HttpSession s, List<Tarefa> ts, String pagina) { return ctx.preparar(m, s, ts, pagina); }

    private static String volta(String v) {
        return "/agenda".equals(v) ? "redirect:/agenda" : "/resumo".equals(v) ? "redirect:/resumo" : "redirect:/";
    }

    // ---------- tarefas ----------
    @GetMapping("/")
    String tarefas(@RequestParam(required = false) String editar, HttpSession s, Model m) {
        List<Tarefa> todas = new ArrayList<>(svc.listar(sb.web(s)));
        boolean bloqueado = contexto(m, s, todas, "tarefas");
        LocalDate hoje = Tempo.hoje();
        Comparator<Tarefa> ordem = Comparator.comparing(Tarefa::concluida).thenComparing(Comparator.comparingInt(Tarefa::score).reversed());
        List<Tarefa> ts = new ArrayList<>(TarefaService.doDia(todas, hoje));
        ts.sort(ordem);
        long feitas = ts.stream().filter(Tarefa::concluida).count();
        m.addAttribute("tarefas", ts);
        m.addAttribute("total", ts.size());
        m.addAttribute("feitas", feitas);
        m.addAttribute("pendentes", ts.size() - feitas);
        m.addAttribute("esforco", Tempo.legivel(ts.stream().filter(Tarefa::concluida).mapToLong(Tarefa::tempoGasto).sum()));
        m.addAttribute("anteriores", TarefaService.pendentesAnteriores(todas, hoje).stream().sorted(Comparator.comparing(Tarefa::dia).reversed()).toList());
        m.addAttribute("proximos", todas.stream().filter(t -> t.dia().isAfter(hoje)).sorted(Comparator.comparing(Tarefa::dia)).toList());
        m.addAttribute("hojeIso", hoje.toString());
        m.addAttribute("dataHoje", Tempo.diaSemana(hoje) + ", " + Tempo.data(hoje));
        m.addAttribute("niveis", Nivel.values());
        m.addAttribute("periodos", Periodo.values());
        m.addAttribute("edit", editar == null || bloqueado ? null : todas.stream().filter(t -> t.id().equals(editar)).findFirst().orElse(null));
        return "tarefas";
    }

    @PostMapping("/tarefas")
    String salvar(@RequestParam(required = false) String id, @RequestParam String nome,
                  @RequestParam(defaultValue = "") String descricao, @RequestParam int prioridade,
                  @RequestParam int urgencia, @RequestParam int complexidade, @RequestParam int duracao,
                  @RequestParam String periodo, @RequestParam(required = false) String dia, HttpSession s) {
        boolean novo = id == null || id.isBlank();
        LocalDate quando = null;   // null = hoje (padrão do banco). Ao editar, o dia da tarefa não muda.
        if (novo && dia != null && !dia.isBlank()) {
            try { quando = LocalDate.parse(dia); } catch (Exception e) { throw new ErroSupabase(400, "Data inválida."); }
        }
        Map<String, Object> d = TarefaService.dados(nome, descricao, Nivel.de(prioridade), Nivel.de(urgencia),
                Nivel.de(complexidade), duracao, Periodo.de(periodo), quando);
        if (novo) svc.criar(sb.web(s), d); else svc.editar(sb.web(s), id, d);
        return "redirect:/";
    }

    /** Pop-up "tempo gasto": conclui a tarefa gravando o esforço informado em horas, minutos e segundos. */
    @PostMapping("/tarefas/{id}/concluir")
    String concluir(@PathVariable String id, @RequestParam(defaultValue = "0") int horas,
                    @RequestParam(defaultValue = "0") int minutos, @RequestParam(defaultValue = "0") int segundos,
                    @RequestParam(required = false) String volta, HttpSession s) {
        if (horas < 0 || minutos < 0 || segundos < 0) throw new ErroSupabase(400, "O tempo gasto não pode ser negativo.");
        svc.concluir(sb.web(s), id, (int) Math.min(Integer.MAX_VALUE, horas * 3600L + minutos * 60L + segundos));
        ctx.avisarConquistas(s);
        return volta(volta);
    }

    @PostMapping("/tarefas/{id}/iniciar")
    String iniciar(@PathVariable String id, @RequestParam(required = false) String volta, HttpSession s) {
        svc.iniciar(sb.web(s), id);
        return volta(volta);
    }

    @PostMapping("/tarefas/{id}/reabrir")
    String reabrir(@PathVariable String id, @RequestParam(required = false) String volta, HttpSession s) {
        ctx.fecharDias(s);
        svc.reabrir(sb.web(s), id);
        return volta(volta);
    }

    @PostMapping("/tarefas/{id}/remover")
    String remover(@PathVariable String id, HttpSession s) {
        ctx.fecharDias(s);   // fecha os dias vencidos antes, para remover tarefas não "apagar" um dia já encerrado
        svc.remover(sb.web(s), id);
        return "redirect:/";
    }

    /** Tarefa pendente de um dia anterior: cria uma cópia para hoje. Nada é movido sozinho. */
    @PostMapping("/tarefas/{id}/replanejar")
    String replanejar(@PathVariable String id, HttpSession s) {
        ctx.fecharDias(s);
        Tarefa t = svc.replanejar(sb.web(s), id);
        s.setAttribute("aviso", "«" + t.nome() + "» foi replanejada para hoje.");
        return "redirect:/";
    }

    @PostMapping("/tarefas/{id}/turno")
    String turno(@PathVariable String id, @RequestParam String periodo, HttpSession s) {
        svc.mover(sb.web(s), id, Periodo.de(periodo));
        return "redirect:/agenda";
    }

    // ---------- agenda ----------
    private List<Coluna> montarColunas(List<Tarefa> ts, Map<Periodo, Integer> caps) {
        List<Coluna> colunas = new ArrayList<>();
        for (Periodo p : Periodo.values()) {
            List<Tarefa> l = ts.stream().filter(t -> !t.concluida() && t.turno() == p)
                    .sorted(Comparator.comparingInt(Tarefa::score).reversed()).toList();
            colunas.add(new Coluna(p, l, l.stream().mapToInt(Tarefa::duracaoSeg).sum(), caps.get(p)));
        }
        return colunas;
    }

    @GetMapping("/agenda")
    String agenda(HttpSession s, Model m) {
        List<Tarefa> ts = svc.listar(sb.web(s));
        contexto(m, s, ts, "agenda");
        m.addAttribute("colunas", montarColunas(TarefaService.doDia(ts, Tempo.hoje()), svc.capacidades(sb.web(s))));
        m.addAttribute("periodos", Periodo.values());
        return "agenda";
    }

    // ---------- resumo do dia ----------
    @GetMapping("/resumo")
    String resumo(HttpSession s, Model m) {
        List<Tarefa> ts = svc.listar(sb.web(s));
        contexto(m, s, ts, "resumo");
        List<Tarefa> hoje = TarefaService.doDia(ts, Tempo.hoje());
        List<Coluna> colunas = montarColunas(hoje, svc.capacidades(sb.web(s)));
        long pendentes = hoje.stream().filter(t -> !t.concluida()).count();
        String data = LocalDate.now(ZoneId.of("America/Sao_Paulo"))
                .format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR")));
        m.addAttribute("data", Character.toUpperCase(data.charAt(0)) + data.substring(1));
        m.addAttribute("pendentes", pendentes);
        m.addAttribute("concluidas", hoje.size() - pendentes);
        m.addAttribute("usado", Tempo.legivel(colunas.stream().mapToInt(Coluna::usado).sum()));
        m.addAttribute("capacidade", Tempo.legivel(colunas.stream().mapToInt(Coluna::capacidade).sum()));
        int excesso = colunas.stream().mapToInt(Coluna::excesso).sum();
        m.addAttribute("excesso", excesso);
        m.addAttribute("excessoFmt", Tempo.legivel(excesso));
        m.addAttribute("proximas", hoje.stream().filter(t -> !t.concluida())
                .sorted(Comparator.comparingInt(Tarefa::score).reversed()).limit(3).toList());
        m.addAttribute("colunas", colunas);
        // esforço gasto: total e por usuário (quem concluiu)
        List<Tarefa> feitas = ts.stream().filter(Tarefa::concluida).toList();
        m.addAttribute("esforcoTotal", Tempo.legivel(feitas.stream().mapToLong(Tarefa::tempoGasto).sum()));
        Map<String, long[]> porUsuario = new LinkedHashMap<>();
        for (Tarefa t : feitas) {
            long[] a = porUsuario.computeIfAbsent(t.quemFez().isBlank() ? "(sem usuário)" : t.quemFez(), k -> new long[3]);
            a[0]++; a[1] += t.duracaoSeg(); a[2] += t.tempoGasto();
        }
        m.addAttribute("esforcos", porUsuario.entrySet().stream()
                .map(e -> new Esforco(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(Esforco::gastoSeg).reversed()).toList());
        return "resumo";
    }

    @PostMapping("/capacidade")
    String capacidade(@RequestParam String periodo, @RequestParam(defaultValue = "0") int horas,
                      @RequestParam(defaultValue = "0") int minutos, @RequestParam(defaultValue = "0") int segundos, HttpSession s) {
        if (horas < 0 || minutos < 0 || segundos < 0) throw new ErroSupabase(400, "A capacidade não pode ser negativa.");
        long total = horas * 3600L + minutos * 60L + segundos;
        if (total > TarefaService.LIMITE_CAPACIDADE_SEG) throw new ErroSupabase(400, "A capacidade de um turno não pode passar de 24 horas.");
        svc.capacidade(sb.web(s), Periodo.de(periodo), (int) total);
        return "redirect:/agenda";
    }
}
