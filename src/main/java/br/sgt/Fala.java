package br.sgt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transforma o texto das respostas (feito para tela) em algo que a Alexa lê bem em voz alta. */
final class Fala {
    private Fala() { }

    private static final Pattern H_MIN = Pattern.compile("(\\d+)h(\\d{1,2})(?![\\da-z])");
    private static final Pattern H = Pattern.compile("(\\d+)h(?![a-z])");
    private static final Pattern MIN = Pattern.compile("(\\d+)min(?![a-z])");
    private static final Pattern S = Pattern.compile("(\\d+)s(?![a-z])");

    private static String un(Pattern p, String t, String um, String varios) {
        Matcher m = p.matcher(t);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, m.group(1) + " " + (m.group(1).equals("1") ? um : varios));
        m.appendTail(b);
        return b.toString();
    }

    static String paraVoz(String texto) {
        String t = texto.replace("«", "").replace("»", "").replace("\"", "")
                .replace("(s)", "s").replace("(es)", "es");
        Matcher hm = H_MIN.matcher(t);   // "1h30" -> "1 hora e 30 minutos"
        StringBuilder b = new StringBuilder();
        while (hm.find()) hm.appendReplacement(b, hm.group(1) + (hm.group(1).equals("1") ? " hora" : " horas") + " e " + hm.group(2) + " minutos");
        hm.appendTail(b);
        t = un(H, b.toString(), "hora", "horas");
        t = un(MIN, t, "minuto", "minutos");
        t = un(S, t, "segundo", "segundos");
        return t.replace("→", "para").replace("…", "").replace("•", "").replace("—", ",").replace("·", ",")
                .replaceAll("\\s*\\n+\\s*", ". ").replaceAll("\\.\\s*\\.", ".").replaceAll(":\\.", ":").replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ").trim();
    }
}
