package br.sgt;

/** Turnos do dia: nome, cor e capacidade padrão (minutos) ficam aqui. */
public enum Periodo {
    MANHA("manha", "Manhã", 240, "#f59e0b"),
    TARDE("tarde", "Tarde", 240, "#0ea5e9"),
    NOITE("noite", "Noite", 120, "#7c3aed");

    private final String codigo, rotulo, cor;
    private final int capacidadePadrao;

    Periodo(String codigo, String rotulo, int capacidadePadrao, String cor) {
        this.codigo = codigo; this.rotulo = rotulo; this.capacidadePadrao = capacidadePadrao; this.cor = cor;
    }

    public String getCodigo() { return codigo; }
    public String getRotulo() { return rotulo; }
    public String getCor() { return cor; }
    public int getCapacidadePadrao() { return capacidadePadrao; }

    public static Periodo de(String codigo) {
        for (Periodo p : values()) if (p.codigo.equals(codigo)) return p;
        return MANHA;
    }
}
