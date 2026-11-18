package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Grupo de pessoas que dividem tarefas.
 * FAMILIA = qualquer membro cria tarefas e todos veem o progresso de todos.
 * TURMA   = só o responsável (dono) cria tarefas e só ele vê o progresso; cada aluno vê apenas as suas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Grupo(String id, String nome, String tipo, @JsonProperty("dono_id") String donoId, String codigo) {
    public boolean familia() { return "FAMILIA".equals(tipo); }
    public String tipoRotulo() { return familia() ? "Família / equipe" : "Turma"; }
    public String tipoIcone() { return familia() ? "🏠" : "🎓"; }
}
