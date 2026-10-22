package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Tarefa(String id, String nome, String descricao, int prioridade, int urgencia,
                     int complexidade, int duracao, String periodo, boolean concluida) {
    public int score() { return prioridade * 2 + urgencia * 3; }
    public String scoreCss() { int s = score(); return s >= 13 ? "n3" : s >= 9 ? "n2" : "n1"; }
    public Periodo turno() { return Periodo.de(periodo); }
    public Nivel prio() { return Nivel.de(prioridade); }
    public Nivel urg() { return Nivel.de(urgencia); }
    public Nivel comp() { return Nivel.de(complexidade); }
}
