package br.sgt;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Painel administrativo. O acesso é checado AQUI, no servidor, a cada requisição (o menu escondido na tela
 * não protege nada). Papéis só mudam por este painel; ninguém altera o próprio papel.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {
    public record LinhaUsuario(Perfil perfil, Sequencia seq, boolean voce) { }
    public record LinhaConquista(Conquista c, int desbloqueios) { }

    private final SupabaseService sb;
    private final ConsistenciaService cons;

    public AdminController(SupabaseService sb, ConsistenciaService cons) { this.sb = sb; this.cons = cons; }

    private Perfil exigirAdmin(HttpSession s) {
        Perfil p;
        try { p = sb.web(s).chamar(sb::perfil); }
        catch (ErroSupabase e) { if (e.status == 401) throw e; p = null; }
        if (p == null || !p.admin()) throw new ErroSupabase(403, "Acesso restrito a administradores.");
        return p;
    }

    @GetMapping
    String painel(@RequestParam(defaultValue = "visao") String aba, @RequestParam(required = false) String editar,
                  HttpSession s, Model m) {
        Perfil eu = exigirAdmin(s);
        m.addAttribute("pagina", "admin");
        m.addAttribute("aba", aba);
        m.addAttribute("ativo", cons.ativo());
        if (!cons.ativo()) return "admin";

        List<Perfil> perfis = sb.perfis();
        Map<String, Sequencia> seqs = new HashMap<>();
        for (Sequencia q : sb.sequencias()) seqs.put(q.userId(), q);

        switch (aba) {
            case "usuarios" -> m.addAttribute("linhas", perfis.stream()
                    .map(p -> new LinhaUsuario(p, seqs.get(p.id()), p.id().equals(eu.id()))).toList());
            case "conquistas" -> {
                Map<String, Integer> cont = sb.desbloqueiosPorConquista();
                List<Conquista> catalogo = sb.conquistas();
                m.addAttribute("linhas", catalogo.stream().map(c -> new LinhaConquista(c, cont.getOrDefault(c.id(), 0))).toList());
                m.addAttribute("tipos", TipoConquista.values());
                m.addAttribute("edit", editar == null ? null : catalogo.stream().filter(c -> c.id().equals(editar)).findFirst().orElse(null));
            }
            case "regras" -> m.addAttribute("cfg", cons.config());
            default -> {
                m.addAttribute("aba", "visao");
                m.addAttribute("usuarios", perfis.size());
                m.addAttribute("admins", perfis.stream().filter(Perfil::admin).count());
                m.addAttribute("ativas", seqs.values().stream().filter(q -> q.atual() > 0).count());
                List<Conquista> catalogo = sb.conquistas();
                m.addAttribute("cadastradas", catalogo.size());
                m.addAttribute("desbloqueios", sb.desbloqueiosPorConquista().values().stream().mapToInt(Integer::intValue).sum());
            }
        }
        return "admin";
    }

    @PostMapping("/usuarios/{id}/papel")
    String papel(@PathVariable String id, @RequestParam String papel, HttpSession s) {
        Perfil eu = exigirAdmin(s);
        if (id.equals(eu.id())) throw new ErroSupabase(400, "Você não pode alterar o seu próprio papel.");
        if (!papel.equals("ADMIN") && !papel.equals("USER")) throw new ErroSupabase(400, "Papel inválido.");
        sb.definirPapel(id, papel);
        return "redirect:/admin?aba=usuarios";
    }

    @PostMapping("/conquistas")
    String salvarConquista(@RequestParam(required = false) String id, @RequestParam String nome,
                           @RequestParam(defaultValue = "") String descricao, @RequestParam(defaultValue = "🏆") String icone,
                           @RequestParam String tipo, @RequestParam int requisito, HttpSession s) {
        exigirAdmin(s);
        if (nome.isBlank()) throw new ErroSupabase(400, "Informe o nome da conquista.");
        if (TipoConquista.de(tipo) == null) throw new ErroSupabase(400, "Tipo de conquista inválido.");
        if (requisito < 1 || requisito > 100000) throw new ErroSupabase(400, "O requisito deve estar entre 1 e 100000.");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nome", nome.trim());
        d.put("descricao", descricao.trim());
        d.put("icone", icone.isBlank() ? "🏆" : icone.trim());
        d.put("tipo", tipo);
        d.put("requisito", requisito);
        sb.salvarConquista(id, d);   // editar não remove desbloqueios que usuários já tenham
        return "redirect:/admin?aba=conquistas";
    }

    /** Desativar esconde a conquista de quem ainda não a tem; quem já desbloqueou continua com ela. */
    @PostMapping("/conquistas/{id}/ativa")
    String ativar(@PathVariable String id, @RequestParam boolean ativa, HttpSession s) {
        exigirAdmin(s);
        sb.salvarConquista(id, Map.of("ativa", ativa));
        return "redirect:/admin?aba=conquistas";
    }

    @PostMapping("/regras")
    String regras(@RequestParam int limiarDia, @RequestParam int limiarAlto, @RequestParam int protetorACada,
                  @RequestParam int maxProtetores, HttpSession s) {
        exigirAdmin(s);
        if (limiarDia < 1 || limiarDia > 100 || limiarAlto < 1 || limiarAlto > 100) throw new ErroSupabase(400, "As porcentagens devem ficar entre 1 e 100.");
        if (protetorACada < 0 || protetorACada > 365) throw new ErroSupabase(400, "O intervalo do protetor deve ficar entre 0 e 365 dias.");
        if (maxProtetores < 0 || maxProtetores > 10) throw new ErroSupabase(400, "O máximo de protetores deve ficar entre 0 e 10.");
        sb.salvarConfiguracao("limiar_dia", String.valueOf(limiarDia));
        sb.salvarConfiguracao("limiar_alto", String.valueOf(limiarAlto));
        sb.salvarConfiguracao("protetor_a_cada", String.valueOf(protetorACada));
        sb.salvarConfiguracao("max_protetores", String.valueOf(maxProtetores));
        cons.invalidarConfig();
        return "redirect:/admin?aba=regras";
    }
}
