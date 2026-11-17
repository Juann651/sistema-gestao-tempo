package br.sgt;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entende frases em português e as transforma em um {@link Comando}.
 * Não depende de Spring nem do Supabase. Todas as buscas rodam num texto "sem acento e minúsculo"
 * que tem EXATAMENTE o mesmo tamanho do original, então os índices servem para recortar o texto original.
 */
final class AssistenteParser {
    enum Acao { AJUDA, CRIAR, EDITAR, CONCLUIR, REABRIR, REMOVER, MOVER, INICIAR, CAPACIDADE, LISTAR, CONTAR, STATUS, DESCONHECIDO }

    static final class Comando {
        Acao acao = Acao.DESCONHECIDO;
        Tarefa alvo;
        List<Tarefa> ambiguas = List.of();
        String alvoTexto;
        String nome, descricao;
        Nivel prioridade, urgencia, complexidade;
        Integer duracaoSeg, gastoSeg, capacidadeSeg;
        Periodo turno;
        Boolean concluidas;   // listar: true = só concluídas, false = só pendentes
    }

    private AssistenteParser() { }

    // ---------- texto ----------
    /** minúsculo e sem acento, mantendo o tamanho. */
    static String sem(String t) {
        String nfc = Normalizer.normalize(t, Normalizer.Form.NFC);
        StringBuilder b = new StringBuilder(nfc.length());
        for (char c : nfc.toCharArray()) {
            String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            b.append(Character.toLowerCase(d.charAt(0)));
        }
        return b.toString();
    }

    private static void branquear(StringBuilder b, int ini, int fim) {
        for (int i = Math.max(0, ini); i < Math.min(fim, b.length()); i++) b.setCharAt(i, ' ');
    }

    private static String aparar(String t) {
        return t.replaceAll("^[\\s,;:.\\-\"“”«»]+|[\\s,;:.\\-\"“”«»]+$", "");
    }

    // ---------- tempo ----------
    /** Lê expressões como "1h30", "1 hora e 30 minutos", "45 min", "meia hora", "uma hora e meia". */
    static final class TempoParser {
        private static final Pattern COMPACTO = Pattern.compile(
                "(?<![a-z0-9])(\\d+)\\s*h\\s*(\\d{1,2})(?:\\s*(?:minutos|mins|min|m))?(?![a-z0-9])");
        private static final Pattern TOKEN = Pattern.compile(
                "(?<![a-z0-9])(?:(\\d+)\\s*(horas?|hrs?|h|minutos?|mins?|m|segundos?|segs?|s)"
                + "|(um|uma|dois|duas|tres|quatro|cinco|seis|sete|oito|nove|dez)\\s+(horas?|minutos?|segundos?)"
                + "|(meia)\\s+(hora))(?![a-z0-9])");
        private static final Pattern E_MEIA = Pattern.compile("^\\s*e\\s+meia(?![a-z])");
        private static final Pattern LIGACAO = Pattern.compile("^\\s*(?:e|,)?\\s*$");
        private static final List<String> NUMEROS = List.of("zero", "um", "dois", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez");

        private static int numero(String palavra) {
            if (palavra.equals("uma")) return 1;
            if (palavra.equals("duas")) return 2;
            return NUMEROS.indexOf(palavra);
        }

        /** Cada item: {inicio, fim, segundos}. Expressões coladas ("1h" "30min") viram uma só. */
        static List<int[]> achar(String w) {
            List<int[]> toks = new ArrayList<>();   // {ini, fim, seg, terminaEmHora(0/1)}
            StringBuilder resto = new StringBuilder(w);
            Matcher c = COMPACTO.matcher(w);
            while (c.find()) {
                toks.add(new int[]{c.start(), c.end(), Integer.parseInt(c.group(1)) * 3600 + Integer.parseInt(c.group(2)) * 60, 0});
                branquear(resto, c.start(), c.end());
            }
            Matcher m = TOKEN.matcher(resto);
            while (m.find()) {
                int seg, hora = 0;
                if (m.group(1) != null) {
                    long n = Long.parseLong(m.group(1));
                    char u = m.group(2).charAt(0);
                    seg = (int) Math.min(n * (u == 'h' ? 3600 : u == 'm' ? 60 : 1), 10_000_000);
                    hora = u == 'h' ? 1 : 0;
                } else if (m.group(3) != null) {
                    char u = m.group(4).charAt(0);
                    seg = numero(m.group(3)) * (u == 'h' ? 3600 : u == 'm' ? 60 : 1);
                    hora = u == 'h' ? 1 : 0;
                } else { seg = 1800; }
                toks.add(new int[]{m.start(), m.end(), seg, hora});
            }
            toks.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> res = new ArrayList<>();
            int[] atual = null;
            for (int[] t : toks) {
                if (atual != null && LIGACAO.matcher(w.substring(atual[1], t[0])).matches()) {
                    atual[1] = t[1]; atual[2] += t[2]; atual[3] = t[3];
                } else { atual = t.clone(); res.add(atual); }
            }
            for (int[] r : res) {      // "1 hora e meia"
                Matcher em = E_MEIA.matcher(w.substring(r[1]));
                if (r[3] == 1 && em.find()) { r[1] += em.end(); r[2] += 1800; }
            }
            return res;
        }
    }

    /** Tempo solto numa resposta curta: "45 min", "1h20" ou só "45" (= minutos). Null se não entender. */
    static Integer tempoLivre(String n) {
        List<int[]> t = TempoParser.achar(n);
        if (!t.isEmpty()) return t.get(0)[2];
        Matcher b = Pattern.compile("^\\D*(\\d+)\\D*$").matcher(n);
        return b.find() ? Integer.parseInt(b.group(1)) * 60 : null;
    }

    // ---------- intenção ----------
    private static final Pattern SAUDACAO = Pattern.compile("^(?:(?:oi|ola|ei|hey|e ai|bom dia|boa tarde|boa noite)[ ,]+)?(?:alexa|sgt)[ ,:;!.\\-]*");
    private static final Pattern EDUCACAO = Pattern.compile("^(?:(?:por favor|pode|poderia|quero|queria|preciso|gostaria de|vou|eu|favor|me)[\\s,]+)+");

    private static final Map<Acao, Pattern> VERBOS = new LinkedHashMap<>();
    static {
        VERBOS.put(Acao.CRIAR, Pattern.compile("^(?:criar|crie|cria|adicionar|adicione|adiciona|cadastrar|cadastre|cadastra|incluir|inclua|inclui|add|nova tarefa|agendar|agende|anotar|anote)(?![a-z])"));
        VERBOS.put(Acao.CONCLUIR, Pattern.compile("^(?:concluir|conclua|conclui|concluido|finalizar|finalize|finaliza|terminar|termine|termina|terminei|completar|complete|completei|acabei)(?![a-z])"));
        VERBOS.put(Acao.REABRIR, Pattern.compile("^(?:reabrir|reabra|reabre|reativar|reative|desmarcar|desmarque)(?![a-z])"));
        VERBOS.put(Acao.REMOVER, Pattern.compile("^(?:remover|remova|remove|excluir|exclua|exclui|apagar|apague|apaga|deletar|delete|deleta|tirar|tire)(?![a-z])"));
        VERBOS.put(Acao.MOVER, Pattern.compile("^(?:mover|mova|move|passar|passe|passa|transferir|transfira)(?![a-z])"));
        VERBOS.put(Acao.INICIAR, Pattern.compile("^(?:iniciar|inicie|inicia|iniciei|comecar|comece|comeca|comecei|start|focar|foque)(?![a-z])"));
        VERBOS.put(Acao.EDITAR, Pattern.compile("^(?:editar|edite|edita|alterar|altere|altera|mudar|mude|muda|renomear|renomeie|renomeia|trocar|troque|troca|atualizar|atualize|atualiza|definir|defina|define|ajustar|ajuste|ajusta|colocar|coloque|coloca|aumentar|aumente|diminuir|diminua|reduzir|reduza)(?![a-z])"));
    }
    private static final Pattern MARCAR = Pattern.compile("^marc[a-z]*(?![a-z])");
    private static final Pattern FEITA = Pattern.compile("(?<![a-z])(?:concluid[ao]|feit[ao]|pront[ao]|finalizad[ao])(?![a-z])");
    private static final Pattern LISTAR_VERBO = Pattern.compile("^(?:listar|liste|lista|mostrar|mostre|mostra|exibir|exiba|ver|veja|quais)(?![a-z])");

    private static Acao detectar(String r) {
        if (r.isBlank()) return Acao.AJUDA;
        if (r.matches(".*(?<![a-z])(?:ajuda|ajudar|help|comandos)(?![a-z]).*") || r.contains("o que voce")) return Acao.AJUDA;
        if (r.contains("capacidade")) return Acao.CAPACIDADE;
        if (MARCAR.matcher(r).find() && FEITA.matcher(r).find()) return Acao.CONCLUIR;
        for (Map.Entry<Acao, Pattern> e : VERBOS.entrySet()) if (e.getValue().matcher(r).find()) return e.getKey();
        if (r.matches(".*(?:quantas|quantidade|contador|contagem).*")) return Acao.CONTAR;
        if (r.matches(".*(?:resumo|status|situacao|andamento|quanto tempo|foco|como estou).*")) return Acao.STATUS;
        if (LISTAR_VERBO.matcher(r).find() || r.matches(".*(?:tarefas|agenda|pendentes|o que tenho|o que falta).*")) return Acao.LISTAR;
        return Acao.DESCONHECIDO;
    }

    private static int fimDoVerbo(String r, Acao a) {
        Pattern p = a == Acao.CONCLUIR && MARCAR.matcher(r).find() ? MARCAR : VERBOS.get(a);
        if (p == null) return 0;
        Matcher m = p.matcher(r);
        return m.find() ? m.end() : 0;
    }

    // ---------- atributos ----------
    private static final String NIV = "(alt[ao]|medi[ao]|baix[ao])";
    private static final String[] CAMPOS = {"prioridade", "urgencia", "complexidade"};
    private static final String FILL = "(?:\\s*(?:de|da|do|para|pra|como|em|[:=]))*\\s*";
    private static final Pattern URGENTE = Pattern.compile("(?<![a-z])urgente(?![a-z])");
    private static final Pattern ASPAS = Pattern.compile("[\"“«](.+?)[\"”»]");
    private static final Pattern DESCRICAO = Pattern.compile("(?:(?:com|e)\\s+)?descricao(?![a-z])(?:\\s*(?:de|da|do|para|pra|como|em|[:=]))*\\s*");
    private static final Pattern TURNO = Pattern.compile(
            "(?:(?<![a-z])(?:para|pra|no|na|ao|de|em|a|do|da)\\s+)?(?:(?:o|a)\\s+)?(?:(?:turno|periodo)\\s+(?:(?:da|do|de)\\s+)?)?(?<![a-z])(madrugada|manha|tarde|noite)(?![a-z])");
    private static final Pattern PREFIXO_TEMPO = Pattern.compile(
            "(?:(?:duracao|tempo|durando|dura|leva|levando|levou|gastei|gastou|demorou|estimad[oa]|por|em|com|de|ate|total|aproximadamente|cerca de)\\s+)+$");

    private static Nivel nivel(String palavra) {
        return palavra.startsWith("alt") ? Nivel.ALTA : palavra.startsWith("medi") ? Nivel.MEDIA : Nivel.BAIXA;
    }

    private static void niveis(StringBuilder wb, Comando c, int[] corte) {
        int depois = 0, antes = 0;
        String w = wb.toString();
        for (String kw : CAMPOS) {
            if (Pattern.compile("(?<![a-z])(?:(?:com|de|e)\\s+)?" + kw + "(?![a-z])" + FILL + NIV + "(?![a-z])").matcher(w).find()) depois++;
            if (Pattern.compile("(?<![a-z])(?:(?:com|de|e)\\s+)?" + NIV + "\\s+(?:(?:de|em)\\s+)?" + kw + "(?![a-z])").matcher(w).find()) antes++;
        }
        boolean usaDepois = depois >= antes;
        for (String kw : CAMPOS) {
            String re = usaDepois
                    ? "(?<![a-z])(?:(?:com|de|e)\\s+)?" + kw + "(?![a-z])" + FILL + NIV + "(?![a-z])"
                    : "(?<![a-z])(?:(?:com|de|e)\\s+)?" + NIV + "\\s+(?:(?:de|em)\\s+)?" + kw + "(?![a-z])";
            Matcher m = Pattern.compile(re).matcher(wb);
            if (!m.find()) continue;
            Nivel n = nivel(m.group(1));
            switch (kw) {
                case "prioridade" -> c.prioridade = n;
                case "urgencia" -> c.urgencia = n;
                default -> c.complexidade = n;
            }
            corte[0] = Math.min(corte[0], m.start());
            branquear(wb, m.start(), m.end());
        }
        Matcher u = URGENTE.matcher(wb);
        if (u.find()) {
            if (c.urgencia == null) c.urgencia = Nivel.ALTA;
            corte[0] = Math.min(corte[0], u.start());
            branquear(wb, u.start(), u.end());
        }
    }

    /** Procura o primeiro tempo no texto de trabalho e o apaga. Devolve os segundos ou null. */
    private static Integer tirarTempo(StringBuilder wb, int[] corte) {
        List<int[]> ts = TempoParser.achar(wb.toString());
        if (ts.isEmpty()) return null;
        int[] t = ts.get(0);
        int ini = t[0];
        Matcher pm = PREFIXO_TEMPO.matcher(wb.substring(0, ini));
        if (pm.find()) ini = pm.start();
        corte[0] = Math.min(corte[0], ini);
        branquear(wb, ini, t[1]);
        return t[2];
    }

    private static Periodo tirarTurno(StringBuilder wb, int[] corte) {
        Matcher m = TURNO.matcher(wb);
        if (!m.find()) return null;
        Periodo p = Periodo.de(m.group(1));   // ler o grupo ANTES de apagar o trecho (o Matcher lê o StringBuilder)
        corte[0] = Math.min(corte[0], m.start());
        branquear(wb, m.start(), m.end());
        return p;
    }

    private static void lerAtributos(StringBuilder wb, String ro, Comando c, int[] corte, Acao a) {
        Matcher d = DESCRICAO.matcher(wb);
        if (d.find()) {
            c.descricao = aparar(ro.substring(d.end()));
            corte[0] = Math.min(corte[0], d.start());
            branquear(wb, d.start(), wb.length());
        }
        niveis(wb, c, corte);
        Integer t = tirarTempo(wb, corte);
        if (t != null) { if (a == Acao.CONCLUIR) c.gastoSeg = t; else c.duracaoSeg = t; }
        c.turno = tirarTurno(wb, corte);
    }

    // ---------- achar a tarefa citada ----------
    private record Achado(Tarefa tarefa, List<Tarefa> varias, int ini, int fim, String texto) { }

    private static final Set<String> STOP = Set.of("o", "a", "os", "as", "de", "do", "da", "dos", "das", "um", "uma", "tarefa", "atividade", "para", "pra", "em", "no", "na");
    private static final Pattern PREFIXO_ALVO = Pattern.compile(
            "^\\s*(?:(?:o|a|os|as|um|uma)\\s+)?(?:(?:nome|duracao|prioridade|urgencia|complexidade|descricao|turno|periodo|tempo)\\s+)?(?:(?:de|da|do|das|dos)\\s+)?(?:(?:o|a|os|as)\\s+)?(?:(?:tarefa|atividade)\\s+)?(?:(?:de nome|chamada|chamado)\\s+)?");
    private static final Pattern FIM_ALVO = Pattern.compile(
            "\\s+(?:para|pra|como|em|com|por|prioridade|urgencia|complexidade|duracao|turno|gastei|levou|demorou|no|na|ao)(?![a-z])|[:,;]|$");

    private static List<Tarefa> filtrar(Acao a, List<Tarefa> ts) {
        return switch (a) {
            case CONCLUIR, INICIAR, MOVER -> ts.stream().filter(t -> !t.concluida()).toList();
            case REABRIR -> ts.stream().filter(Tarefa::concluida).toList();
            default -> ts;
        };
    }

    private static Achado procurar(String r, String ro, int apos, List<Tarefa> pool) {
        // 1) o nome completo de alguma tarefa aparece na frase (vence o mais longo)
        int melhor = 0, ini = -1, fim = -1;
        List<Tarefa> topo = new ArrayList<>();
        for (Tarefa t : pool) {
            String nm = sem(t.nome().trim());
            if (nm.isEmpty()) continue;
            Matcher m = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(nm) + "(?![a-z0-9])").matcher(r);
            if (!m.find(apos)) continue;
            if (nm.length() > melhor) { melhor = nm.length(); topo.clear(); ini = m.start(); fim = m.end(); }
            if (nm.length() == melhor) topo.add(t);
        }
        if (topo.size() == 1) return new Achado(topo.get(0), List.of(), ini, fim, ro.substring(ini, fim));
        if (topo.size() > 1) return new Achado(null, topo, ini, fim, ro.substring(ini, fim));

        // 2) aproximado: entre aspas, ou o trecho logo depois do verbo/"tarefa"
        int pi, pf;
        Matcher q = ASPAS.matcher(ro);
        if (q.find(apos)) { pi = q.start(1); pf = q.end(1); }
        else {
            Matcher pre = PREFIXO_ALVO.matcher(r.substring(apos));
            pi = apos + (pre.lookingAt() ? pre.end() : 0);
            Matcher fm = FIM_ALVO.matcher(r);
            pf = fm.find(pi) ? fm.start() : r.length();
        }
        if (pf <= pi) return new Achado(null, List.of(), -1, -1, "");
        String frase = ro.substring(pi, pf).trim();
        List<String> toks = Arrays.stream(sem(frase).split("[^a-z0-9]+")).filter(x -> x.length() >= 2 && !STOP.contains(x)).toList();
        if (toks.isEmpty()) return new Achado(null, List.of(), -1, -1, frase);
        List<Tarefa> cand = new ArrayList<>();
        for (Tarefa t : pool) {
            List<String> nt = Arrays.stream(sem(t.nome()).split("[^a-z0-9]+")).filter(x -> !x.isEmpty()).toList();
            boolean todos = toks.stream().allMatch(tk -> nt.stream().anyMatch(x -> x.startsWith(tk) || (x.length() >= 4 && tk.startsWith(x))));
            if (todos) cand.add(t);
        }
        if (cand.size() == 1) return new Achado(cand.get(0), List.of(), pi, pf, frase);
        return new Achado(null, cand, pi, pf, frase);
    }

    // ---------- ponto de entrada ----------
    static Comando interpretar(String original, List<Tarefa> ts) {
        Comando c = new Comando();
        String o = Normalizer.normalize(original.trim(), Normalizer.Form.NFC);
        String n = sem(o);
        int ini = 0;
        Matcher g = SAUDACAO.matcher(n);
        if (g.find()) ini = g.end();
        Matcher e = EDUCACAO.matcher(n.substring(ini));
        if (e.find()) ini += e.end();
        String r = n.substring(ini), ro = o.substring(ini);

        c.acao = detectar(r);
        int fv = fimDoVerbo(r, c.acao);
        StringBuilder wb = new StringBuilder(r);

        switch (c.acao) {
            case CRIAR -> {
                int[] corte = {r.length()};
                Matcher q = ASPAS.matcher(ro);
                if (q.find(fv)) { c.nome = q.group(1).trim(); branquear(wb, q.start(), q.end()); }
                lerAtributos(wb, ro, c, corte, Acao.CRIAR);
                if (c.nome == null) {
                    String bruto = ro.substring(fv, Math.max(fv, Math.min(corte[0], ro.length())));
                    Matcher lim = Pattern.compile("^\\s*(?:uma?\\s+)?(?:nova\\s+)?(?:tarefa|atividade)?\\s*(?:chamada|chamado|de nome|com o nome|com nome)?\\s*[:\\-]?\\s*").matcher(sem(bruto));
                    String nome = aparar(bruto.substring(lim.lookingAt() ? lim.end() : 0));
                    c.nome = nome.isEmpty() ? null : nome;
                }
            }
            case EDITAR, MOVER, CONCLUIR, REABRIR, REMOVER, INICIAR -> {
                Achado a = procurar(r, ro, fv, filtrar(c.acao, ts));
                c.alvo = a.tarefa(); c.ambiguas = a.varias(); c.alvoTexto = a.texto();
                if (a.ini() >= 0) branquear(wb, a.ini(), a.fim());
                int[] corte = {r.length()};
                if (c.acao == Acao.EDITAR) {
                    boolean renomear = r.startsWith("renome") || Pattern.compile("(?<![a-z])nome(?![a-z])").matcher(r).find();
                    if (renomear) {
                        Matcher q = ASPAS.matcher(ro);
                        Matcher pm = Pattern.compile("(?<![a-z])(?:para|pra|como)\\s+").matcher(r);
                        int de = a.fim() >= 0 ? a.fim() : fv;
                        if (q.find(de)) { c.nome = q.group(1).trim(); branquear(wb, q.start(), q.end()); }
                        else if (pm.find(de)) { c.nome = aparar(ro.substring(pm.end())); branquear(wb, pm.start(), wb.length()); }
                    }
                    lerAtributos(wb, ro, c, corte, Acao.EDITAR);
                } else if (c.acao == Acao.MOVER) {
                    c.turno = tirarTurno(wb, corte);
                } else if (c.acao == Acao.CONCLUIR) {
                    c.gastoSeg = tirarTempo(wb, corte);
                }
            }
            case CAPACIDADE -> {
                int[] corte = {r.length()};
                c.turno = tirarTurno(wb, corte);
                c.capacidadeSeg = tirarTempo(wb, corte);
                if (c.capacidadeSeg == null) {
                    Matcher b = Pattern.compile("(?<![a-z0-9])(\\d+)(?![a-z0-9])").matcher(wb);
                    if (b.find()) c.capacidadeSeg = Integer.parseInt(b.group(1)) * 60;   // número solto = minutos
                }
            }
            case LISTAR -> {
                int[] corte = {r.length()};
                c.turno = tirarTurno(wb, corte);
                if (r.matches(".*(?:concluid|feit|finalizad).*")) c.concluidas = true;
                else if (r.matches(".*(?:pendente|falta|aberta).*")) c.concluidas = false;
            }
            default -> { }
        }
        return c;
    }
}
