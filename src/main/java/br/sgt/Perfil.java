package br.sgt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Perfil do usuário: guarda o papel (USER ou ADMIN). Só o servidor consegue alterar o papel. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Perfil(String id, String email, String role, @JsonProperty("criado_em") String criadoEm) {
    public boolean admin() { return "ADMIN".equals(role); }
    public String criadoFmt() { return criadoEm == null || criadoEm.length() < 10 ? "" : criadoEm.substring(8, 10) + "/" + criadoEm.substring(5, 7) + "/" + criadoEm.substring(0, 4); }
}
