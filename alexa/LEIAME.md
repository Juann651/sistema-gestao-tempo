# SGT na Alexa (Amazon)

Controle suas tarefas por voz nos aparelhos Echo e no app Alexa: criar, editar, mover, iniciar (modo foco),
concluir com tempo gasto, reabrir, remover, mudar a capacidade de um turno, listar e ver o resumo.

## Como funciona

```
Você fala  ->  Alexa (Amazon)  ->  POST https://SEU-APP/alexa  ->  mesmas regras do site  ->  Supabase
```

* A skill usa o mesmo "cérebro" do site (`Assistente` + `TarefaService`): **o modo foco bloqueia a voz também**.
* **Vinculação de conta (OAuth):** na primeira vez, o app da Alexa abre uma tela do SGT (`/oauth/authorize`)
  onde você entra com seu e-mail e senha. A Alexa guarda o token do próprio Supabase e o renova sozinha
  (`/oauth/token`). Cada pedido roda com as permissões do seu usuário, como no site.
* Nada é guardado no servidor: o código do OAuth é um pacote lacrado (AES-GCM) que vale 5 minutos e só funciona uma vez.
* Toda requisição é validada como vinda da Amazon (certificado, assinatura, horário até 150 s e id da skill).

## 1) Variáveis de ambiente no servidor (onde o SGT está hospedado)

| Variável | Para quê | Exemplo |
|---|---|---|
| `ALEXA_SECRET` | Segredo que lacra os códigos do OAuth. Longo e aleatório. | `openssl rand -base64 36` |
| `ALEXA_CLIENT_ID` | Identificador do cliente OAuth (opcional, padrão `sgt-alexa`) | `sgt-alexa` |
| `ALEXA_CLIENT_SECRET` | Senha do cliente OAuth. Longa e aleatória. | `openssl rand -base64 36` |
| `ALEXA_SKILL_ID` | Id da skill (vem do console, passo 3). **Recomendado.** | `amzn1.ask.skill.xxxxxxxx` |
| `ALEXA_VERIFICAR` | `true` (padrão). **Só `false` em testes locais**, nunca em produção. | `true` |

Sem `ALEXA_SECRET` e `ALEXA_CLIENT_SECRET`, a integração fica desligada (o site funciona normal).
O app precisa estar em **HTTPS com certificado de autoridade confiável** (Render, Railway, Fly etc. já têm).

## 2) Crie a skill no console da Alexa

1. Acesse <https://developer.amazon.com/alexa/console/ask> -> **Create Skill**.
2. Nome: *SGT*. Idioma: **Portuguese (BR)**. Tipo: **Custom**. Hospedagem: **Provision your own**.
3. Em **Build -> Interaction Model -> JSON Editor**, cole o conteúdo de `interaction-model-pt-BR.json` -> **Save** -> **Build Model**.
   * Nome de invocação: `meu s g t` (letras separadas por espaço). Pode trocar em *Invocations*.
4. Em **Build -> Endpoint**: **HTTPS**, Default Region: `https://SEU-APP/alexa`
   e marque *"My development endpoint has a certificate from a trusted certificate authority"*.
5. Copie o **Skill ID** (topo da página, em *Your Skills*) e coloque em `ALEXA_SKILL_ID` no servidor.
6. Em **Build -> Account Linking** (ligue **Do you allow users to create an account or link...**):

   | Campo | Valor |
   |---|---|
   | Auth Code Grant | selecionado |
   | Authorization URI | `https://SEU-APP/oauth/authorize` |
   | Access Token URI | `https://SEU-APP/oauth/token` |
   | Your Client ID | o mesmo de `ALEXA_CLIENT_ID` |
   | Your Secret | o mesmo de `ALEXA_CLIENT_SECRET` |
   | Your Authentication Scheme | **HTTP Basic (Recommended)** |
   | Scope | `sgt` |
   | **Default Access Token Expiration Time** | **`3600`** |

   > **Importante:** o valor de expiração do console sobrescreve o do servidor. Mantenha em `3600` (igual ao
   > padrão do Supabase, *Auth -> JWT expiry*). Se você mudou o prazo do JWT no Supabase, use o mesmo valor
   > aqui ou um menor.
7. Salve tudo e vá em **Test** -> mude para **Development**.

## 3) Vincule sua conta e use

* No **app Alexa** (celular): *Mais -> Skills e jogos -> Suas skills -> Skills de desenvolvedor -> SGT -> Ativar para usar* -> entra a tela do SGT -> e-mail e senha.
* A skill de desenvolvimento só funciona nos aparelhos da **mesma conta Amazon** do desenvolvedor.
* Abra a skill: **"Alexa, abrir meu s g t"** (ou teste no simulador do console, aba *Test*).

### O que dizer

| Quero... | Diga |
|---|---|
| Criar | *criar tarefa revisar contrato prioridade alta urgência alta duração uma hora e meia na madrugada* |
| Editar | *editar revisar contrato duração duas horas* / *mudar prioridade de revisar contrato para baixa* |
| Renomear | *renomear revisar contrato para revisar contrato final* |
| Mover de turno | *mover revisar contrato para a noite* |
| Iniciar (modo foco) | *iniciar revisar contrato* |
| Concluir | *concluir revisar contrato gastei quarenta e cinco minutos* (se faltar o tempo, a Alexa pergunta) |
| Reabrir / remover | *reabrir revisar contrato* / *remover revisar contrato* (pede "sim") |
| Capacidade | *capacidade da manhã para três horas e trinta minutos* |
| Consultar | *listar minhas tarefas*, *listar tarefas da noite*, *quantas tarefas eu tenho*, *resumo*, *quanto tempo falta* |

Dicas: o nome da tarefa pode ser parcial (*"concluir relatório"* acha *"Revisar relatório"*); se houver mais de uma
parecida, a Alexa pede o nome completo. A frase precisa **começar com o verbo** (criar, editar, mover, iniciar...).
Durante o modo foco, só é possível concluir a tarefa em andamento. A Alexa avisa quanto tempo falta.

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| "Há um problema com a resposta da skill" | Endpoint fora do ar, ou `ALEXA_SKILL_ID` diferente do id real. Veja os logs: aparece `Requisição da Alexa recusada: ...`. |
| Pede para vincular a conta toda hora | Expiração do token no console maior que a do Supabase, ou refresh token inválido. Desvincule e vincule de novo. |
| Tela de vínculo diz "Endereço de retorno não permitido" | O app só aceita os retornos oficiais da Amazon (`pitangui.amazon.com`, `layla.amazon.com`, `alexa.amazon.co.jp`). |
| Erro 503 em `/alexa` ou `/oauth/token` | `ALEXA_SECRET` ou `ALEXA_CLIENT_SECRET` não definidos. |
| A Alexa entende errado o nome da tarefa | Use nomes curtos e distintos. Veja o que ela ouviu em *Test -> Utterance Profiler* / *Intent History*. |

## Publicar para outras pessoas (opcional)

Para uso pessoal, o modo de desenvolvimento basta. Para publicar na loja é preciso enviar para certificação
(*Distribution* e *Certification* no console), com política de privacidade e termos de uso públicos. Como a skill
lida com e-mail e senha de uma conta, confira os requisitos de segurança de vinculação de conta da Amazon antes.
