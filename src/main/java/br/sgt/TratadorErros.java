package br.sgt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Comportamento comum a todos os controllers: aviso de uma tela para a outra, papel do usuário e tratamento de erros. */
@ControllerAdvice
public class TratadorErros {
    private final SupabaseService sb;

    public TratadorErros(SupabaseService sb) { this.sb = sb; }

    @ModelAttribute
    void comum(HttpSession s, Model m) {
        Object a = s.getAttribute("aviso");
        if (a != null) { m.addAttribute("aviso", a); s.removeAttribute("aviso"); }
        m.addAttribute("papel", papel(s));
    }

    /** USER ou ADMIN, lido uma vez por sessão. Só serve para mostrar o menu: o acesso de verdade é checado no servidor. */
    private String papel(HttpSession s) {
        Object p = s.getAttribute("papel");
        if (p != null) return (String) p;
        if (s.getAttribute("sessao") == null) return "USER";
        String papel = "USER";
        try {
            Perfil pf = sb.web(s).chamar(sb::perfil);
            if (pf != null && pf.admin()) papel = "ADMIN";
        } catch (RuntimeException ignorada) { /* tabela de perfis ainda não criada: segue como USER */ }
        s.setAttribute("papel", papel);
        return papel;
    }

    @ExceptionHandler(ErroSupabase.class)
    String erro(ErroSupabase e, HttpServletRequest rq, HttpSession s, Model m) {
        if (e.status == 401) { s.removeAttribute("sessao"); s.removeAttribute("papel"); s.setAttribute("aviso", e.getMessage()); return "redirect:/login"; }
        if (rq.getMethod().equals("GET")) { m.addAttribute("mensagem", e.getMessage()); return "erro"; }
        s.setAttribute("aviso", e.getMessage());
        String uri = rq.getRequestURI();
        return uri.startsWith("/admin") ? "redirect:/admin" : uri.startsWith("/consistencia") ? "redirect:/consistencia" : "redirect:/";
    }
}
