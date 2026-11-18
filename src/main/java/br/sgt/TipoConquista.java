package br.sgt;

/** Tipos de conquista que o administrador pode cadastrar. O valor "requisito" é sempre um número inteiro. */
public enum TipoConquista {
    SEQUENCIA("Dias consecutivos", "dias consecutivos"),
    TOTAL_TAREFAS("Total de tarefas concluídas", "tarefas concluídas"),
    DIAS_ALTA_CONCLUSAO("Dias com alta conclusão", "dias com alta conclusão"),
    DIAS_TURNO_COMPLETO("Dias com um turno 100% concluído", "dias com um turno 100% concluído");

    private final String rotulo, unidade;

    TipoConquista(String rotulo, String unidade) { this.rotulo = rotulo; this.unidade = unidade; }

    public String getRotulo() { return rotulo; }
    public String getUnidade() { return unidade; }

    public static TipoConquista de(String codigo) {
        for (TipoConquista t : values()) if (t.name().equals(codigo)) return t;
        return null;
    }
}
