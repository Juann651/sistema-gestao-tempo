package br.sgt;

/** Níveis de prioridade, urgência e complexidade. O peso é o valor usado no score e salvo no banco (1 a 3). */
public enum Nivel {
    BAIXA(1, "Baixa", "n1"), MEDIA(2, "Média", "n2"), ALTA(3, "Alta", "n3");

    private final int peso;
    private final String rotulo, css;

    Nivel(int peso, String rotulo, String css) { this.peso = peso; this.rotulo = rotulo; this.css = css; }

    public int getPeso() { return peso; }
    public String getRotulo() { return rotulo; }
    public String getCss() { return css; }

    public static Nivel de(int peso) {
        for (Nivel n : values()) if (n.peso == peso) return n;
        return MEDIA;
    }
}
