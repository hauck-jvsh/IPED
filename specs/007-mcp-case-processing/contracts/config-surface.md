# Contract — superfície de configuração

**Feature**: [007-mcp-case-processing](../spec.md)

Chaves novas de `conf/McpServerConfig.txt`. Princípio IV: tudo o que varia vive aqui, nunca em
constante de código. Os valores no código continuam sendo **fallback de último recurso** para quando o
arquivo não existe; o arquivo distribuído é a autoridade.

## Chaves

| Chave | Padrão | Efeito |
|---|---|---|
| `processingEnabled` | `false` | Habilita a capacidade. **Desabilitada por padrão** (FR-001). Independente do modo de acesso de leitura e curadoria: habilitar curadoria não habilita processar |
| `processingSourceAreas` | *(vazio)* | Raízes sob as quais evidência pode ser lida, separadas por `;`. Vazio com `processingEnabled=true` é **erro de configuração**, não permissão total |
| `processingCaseRoots` | *(vazio)* | Raízes sob as quais casos podem nascer, separadas por `;`. Distintas de `exportRoots` (FR-009) |
| `processingProfiles` | `forensic,fastmode,triage` | Perfis que o agente pode nomear (FR-013). `blind` e `pedo` fora do padrão: têm efeito que o perito deve escolher deliberadamente |
| `processingMinFreeSpacePercentOfSource` | `50` | Percentual do **tamanho da evidência de origem** que precisa estar livre na unidade de destino. Em `50`, uma imagem de 500 GB exige 250 GB livres. Abaixo disso, adverte — **nunca recusa** (FR-044) |
| `processingSecretsFile` | *(vazio)* | Arquivo do lado do servidor que resolve referência de segredo → senha. Diz **onde**, nunca **qual** (FR-015) |
| `processingLocale` | `en` | Locale declarado para o processo filho. Existe porque o progresso é lido de mensagens localizadas e herdar o locale da máquina tornaria a leitura dependente da instalação (R2) |
| `processingStallThresholdSeconds` | `300` | Silêncio do fluxo do filho acima do qual o avanço é declarado **parado**, não apenas lento (FR-047). Generoso de propósito: consolidação de índice num caso grande fica minutos sem emitir nada, legitimamente |
| `processingJvm` | *(derivado)* | JVM usada para o filho. Derivado como `<ipedRoot>/jre/bin/java` no Linux e `<ipedRoot>\jre\bin\java.exe` no Windows — o sufixo é da plataforma, não da configuração. Declarável para instalação fora do padrão |

## Regras de leitura

**Lista de caminhos usa `;`; lista comum usa `,`.** É a regra que o arquivo já segue desde a 006, e
tem motivo: caminho de arquivo carrega vírgula, e cortar por vírgula partiria um caminho do Windows ao
meio. Por isso `processingSourceAreas` e `processingCaseRoots` usam `;`, e `processingProfiles` — que
são nomes, não caminhos — usa `,`, como as demais listas do arquivo.

**Sem padrão para as raízes, de propósito.** Nem `processingSourceAreas` nem `processingCaseRoots` têm
valor embutido. Uma raiz padrão inventada seria uma permissão que ninguém concedeu. Com
`processingEnabled=true` e qualquer das duas vazia, o servidor **recusa processar** e diz qual chave
falta — não passa a operar em silêncio como se nada estivesse permitido, e não passa a permitir tudo.

**Áreas de leitura são resolvidas no momento do pedido** (FR-039), não na inicialização. Uma área
declarada e ausente é reportada como indisponível, resposta distinta de origem não permitida.

**A exigência de espaço é relativa à origem, não a uma estimativa interna.** `mínimo = tamanho da
origem × percentual`. A escolha é deliberada: uma estimativa de quanto o índice vai ocupar depende do
perfil, do material e de a exportação estar ligada, e o perito não teria como conferi-la. Um
percentual sobre um tamanho que ele conhece é regra que ele consegue explicar e ajustar. O padrão `50`
é conservador de propósito — o índice de um perfil forense costuma ficar bem abaixo disso, e a folga
cobre exportação de itens, que é o que estoura disco na prática.

**O tamanho é o do conjunto.** Imagem forense quase sempre vem segmentada; medir só o `.E01` daria uma
fração do real e a advertência nunca sairia justamente no caso que mais precisa dela. Pasta lógica
conta recursivamente. Quando a medição não cabe no orçamento de aceite de 5 s — compartilhamento de
rede grande, tipicamente — a exigência é declarada **indisponível**, nunca suposta (FR-046).

**`processingEnabled=false` é postura, não ausência.** Com a capacidade desabilitada as ferramentas de
processamento não aparecem na superfície, e um pedido é recusado antes da leitura de qualquer argumento
e sem tocar o sistema de arquivos (FR-002).

## Interação com chaves existentes

| Chave existente | Interação |
|---|---|
| `exportRoots` | **Nenhuma.** As duas listas podem se sobrepor no sistema de arquivos; a regra aplicada a cada operação é a da sua própria classe (FR-009) |
| `allowExportIntoCaseFolder` | Não se aplica ao destino de caso. Suprime `INSIDE_CASE` só para artefato de exportação, semântica já estreitada na 006 |
| modo de acesso (leitura/curadoria) | Ortogonal. Processar é terceira classe (FR-001) |
| política de egresso | **Aplica-se ao trecho diagnóstico de falha** (FR-043), que é declarado conteúdo derivado de evidência e atravessa a mesma fronteira que texto de item |

## Exemplo distribuído

```properties
# Processing is off unless an examiner turns it on. Turning it on without declaring
# both root lists is a configuration error, not a grant of full access.
processingEnabled = false

# Where evidence may be read from, and where cases may be created. Semicolon-separated:
# a comma would cut a Windows path in half. No default: an invented root is a permission
# nobody granted.
processingSourceAreas =
processingCaseRoots =

# Profiles the agent may name. blind and pedo are deliberately absent: their effects are
# the examiner's call, not a default.
processingProfiles = forensic,fastmode,triage

# Free space required at the destination, as a percentage of the SOURCE evidence size.
# At 50, a 500 GB image needs 250 GB free. Below that the server warns; it never refuses
# on this basis. Segmented images count as the whole set, not the first segment.
processingMinFreeSpacePercentOfSource = 50

# Where container passwords are resolved from. This key says where, never which.
processingSecretsFile =

# Declared locale for the child process. The current phase is only ever reported as
# localized prose with no numeric anchor, so pinning the locale is what makes the phase
# readable at all -- not a secondary safeguard.
processingLocale = en

# Silence from the child above this is reported as stalled rather than merely slow.
# Deliberately generous: index commit on a large case is legitimately quiet for minutes,
# which is why the phase is always reported alongside.
processingStallThresholdSeconds = 300
```
