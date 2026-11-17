package br.sgt;

import br.sgt.Assistente.Memoria;
import br.sgt.Assistente.Pendente;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint da skill da Alexa (Amazon). A Alexa entende a fala, manda a intenção para cá,
 * e a resposta volta como fala (e cartão no app). Os pedidos reaproveitam a {@link Assistente}:
 * mesmas regras, mesmo bloqueio do modo foco, mesmas respostas do site.
 */
@RestController
public class AlexaSkill {
    private static final Logger LOG = LoggerFactory.getLogger(AlexaSkill.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOAS_VINDAS = "Oi! Aqui é o S G T. Peça para criar, iniciar ou concluir uma tarefa, listar suas tarefas, ou mudar a capacidade de um turno. Diga ajuda para ver exemplos.";
    private static final String AJUDA = "Você pode dizer: criar tarefa revisar contrato prioridade alta duração uma hora e meia na tarde. "
            + "Iniciar revisar contrato. Concluir revisar contrato. Mover revisar contrato para a noite. "
            + "Listar minhas tarefas. Quantas tarefas eu tenho. Capacidade da manhã para três horas. O que você quer fazer?";

    private final SupabaseService sb;
    private final Assistente assistente;
    private final AlexaVerificador verificador;
    private final boolean configurado;

    public AlexaSkill(SupabaseService sb, Assistente assistente, AlexaVerificador verificador,
                      @Value("${alexa.secret:}") String segredo, @Value("${alexa.client-secret:}") String clientSecret) {
        this.sb = sb; this.assistente = assistente; this.verificador = verificador;
        this.configurado = !segredo.isBlank() && !clientSecret.isBlank();
    }

    /** Pergunta guardada nos atributos da sessão da Alexa (o servidor não guarda estado). */
    private static final class MemoriaVoz implements Memoria {
        private Pendente p;
        MemoriaVoz(JsonNode attrs) {
            JsonNode n = attrs == null ? null : attrs.get("pendente");
            if (n != null && n.hasNonNull("tipo")) p = new Pendente(n.get("tipo").asText(), n.path("id").asText(), n.path("nome").asText());
        }
        @Override public Pendente pegar() { return p; }
        @Override public void guardar(Pendente x) { p = x; }
        @Override public void limpar() { p = null; }
        @Override public int limiteLista() { return 5; }
    }

    @PostMapping(value = "/alexa", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> receber(@RequestBody byte[] corpo,
                                          @RequestHeader(value = "SignatureCertChainUrl", required = false) String urlCadeia,
                                          @RequestHeader(value = "Signature-256", required = false) String s256,
                                          @RequestHeader(value = "Signature", required = false) String s1) {
        if (!configurado) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("{\"erro\":\"Alexa não configurada\"}");
        JsonNode req;
        try {
            req = JSON.readTree(corpo);
            verificador.verificar(corpo, urlCadeia, s256, s1, req);
        } catch (AlexaVerificador.Recusada e) {
            LOG.warn("Requisição da Alexa recusada: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"erro\":\"requisição inválida\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"erro\":\"requisição inválida\"}");
        }

        String tipo = req.at("/request/type").asText("");
        ObjectNode r;
        try {
            r = switch (tipo) {
                case "LaunchRequest" -> lancar(req);
                case "IntentRequest" -> intencao(req);
                default -> JSON.createObjectNode().put("version", "1.0").set("response", JSON.createObjectNode());
            };
        } catch (ErroSupabase e) {
            r = e.status == 401 ? vincular() : fala(e.getMessage(), null, true, null);
        } catch (Exception e) {
            LOG.error("Falha ao atender a Alexa", e);
            r = fala("Tive um problema agora. Tente de novo em instantes.", null, true, null);
        }
        return ResponseEntity.ok(r.toString());
    }

    // ---------- tipos de pedido ----------
    private static String token(JsonNode req) {
        String t = req.at("/session/user/accessToken").asText("");
        return t.isEmpty() ? req.at("/context/System/user/accessToken").asText("") : t;
    }

    private ObjectNode lancar(JsonNode req) {
        if (token(req).isEmpty()) return vincular();
        return fala(BOAS_VINDAS, null, false, null);
    }

    private ObjectNode intencao(JsonNode req) {
        String token = token(req);
        if (token.isEmpty()) return vincular();
        JsonNode intent = req.at("/request/intent");
        String nome = intent.path("name").asText("");
        switch (nome) {
            case "AMAZON.StopIntent", "AMAZON.CancelIntent": return fala("Até logo!", null, true, null);
            case "AMAZON.HelpIntent": return fala(AJUDA, null, false, null);
            case "AMAZON.FallbackIntent": return fala("Não entendi. Diga ajuda para ouvir exemplos.", null, false, null);
            default: break;
        }
        String texto = montarTexto(nome, intent);
        MemoriaVoz m = new MemoriaVoz(req.at("/session/attributes"));
        if (texto == null) return fala("Não entendi. Diga ajuda para ouvir exemplos.", null, false, null);
        if (texto.isEmpty()) return fala("Não entendi o tempo. Diga por exemplo: quarenta e cinco minutos.", null, false, m.pegar());

        Assistente.Resposta resp = assistente.responder(sb.porToken(token), m, texto);
        return fala(resp.texto(), resp.texto(), !resp.aguarda(), m.pegar());
    }

    /** Converte a intenção da Alexa na mesma frase que o usuário digitaria no site. null = intenção desconhecida; "" = tempo inválido. */
    static String montarTexto(String intencao, JsonNode intent) {
        String t = intent.at("/slots/texto/value").asText("").trim();
        return switch (intencao) {
            case "CriarIntent" -> "criar tarefa " + t;
            case "EditarIntent" -> "editar " + t;
            case "RenomearIntent" -> "renomear " + t;
            case "MoverIntent" -> "mover " + t;
            case "IniciarIntent" -> "iniciar " + t;
            case "ConcluirIntent" -> "concluir " + t;
            case "ReabrirIntent" -> "reabrir " + t;
            case "RemoverIntent" -> "remover " + t;
            case "CapacidadeIntent" -> "capacidade " + t;
            case "ListarIntent" -> "listar tarefas " + t;
            case "ContarIntent" -> "quantas tarefas";
            case "StatusIntent" -> "resumo";
            case "AMAZON.YesIntent" -> "sim";
            case "AMAZON.NoIntent" -> "não";
            case "TempoIntent" -> {
                try {
                    long seg = Duration.parse(intent.at("/slots/tempo/value").asText("")).getSeconds();
                    yield seg < 1 ? "" : Tempo.legivel(seg);
                } catch (Exception e) { yield ""; }
            }
            default -> null;
        };
    }

    // ---------- respostas ----------
    private static ObjectNode base() {
        ObjectNode r = JSON.createObjectNode();
        r.put("version", "1.0");
        return r;
    }

    private ObjectNode fala(String texto, String cartao, boolean encerra, Pendente pendente) {
        ObjectNode r = base();
        if (pendente != null) {
            ObjectNode a = r.putObject("sessionAttributes").putObject("pendente");
            a.put("tipo", pendente.tipo()); a.put("id", pendente.id()); a.put("nome", pendente.nome());
        }
        String voz = Fala.paraVoz(texto);
        ObjectNode resp = r.putObject("response");
        resp.putObject("outputSpeech").put("type", "PlainText").put("text", voz);
        if (cartao != null) resp.putObject("card").put("type", "Simple").put("title", "SGT").put("content", cartao);
        if (!encerra) resp.putObject("reprompt").putObject("outputSpeech").put("type", "PlainText")
                .put("text", pendente != null ? voz : "Pode falar. O que você quer fazer?");
        resp.put("shouldEndSession", encerra);
        return r;
    }

    private ObjectNode vincular() {
        ObjectNode r = base();
        ObjectNode resp = r.putObject("response");
        resp.putObject("outputSpeech").put("type", "PlainText")
                .put("text", "Para usar o S G T, vincule sua conta no aplicativo Alexa. Enviei as instruções para o aplicativo.");
        resp.putObject("card").put("type", "LinkAccount");
        resp.put("shouldEndSession", true);
        return r;
    }
}
