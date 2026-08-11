# Phase 0 — Pesquisa

**Feature**: 006-export-allowlist-socket-transport | **Data**: 2026-08-10

Oito questões precisavam de resposta antes do desenho. Quatro foram resolvidas **experimentalmente**,
com programa de sondagem executado nesta máquina (Windows 11, sistema de arquivos NTFS); as demais,
por leitura do código existente. Cada decisão registra o que foi descartado e por quê.

---

## R1 — Como verificar que um destino está realmente dentro de uma raiz declarada

**Decisão**: fazer toda a verificação por `java.nio.file.Path`, resolvendo o **ancestral existente
mais profundo** do destino com `toRealPath()` e comparando com a raiz também resolvida por
`toRealPath()`. Abandonar `java.io.File` no caminho de decisão e no caminho de escrita.

**Rationale**: sondagem executada em `PathProbe`, sobre uma raiz `allowed/` e um alvo `outside/`
fora dela. Os resultados desmontam a abordagem atual:

| Vetor | `File.getCanonicalPath()` (usado hoje) | `Path.toRealPath()` |
|---|---|---|
| Junção de diretório `allowed\escape` → `outside` | **NÃO resolve** — devolve `...\allowed\escape\loot.txt`, o prefixo continua parecendo permitido | Resolve para `...\probe\outside`; contenção dá **false** |
| Fluxo alternativo de dados `ok.txt:hidden` | Lança `IOException` | `Paths.get` lança `InvalidPathException` na entrada |
| Nome curto 8.3 (`JOAOPA~1`) | Expande | Expande |
| Diferença de caixa | Normaliza | Normaliza |
| Prefixo estendido `\\?\` | — | **Depende da versão** — ver abaixo |
| Travessia relativa `..` | — | `startsWith` cru dá **true**; só após `normalize()`/`toRealPath()` dá **false** |

**Correção registrada durante a implementação.** A linha do prefixo estendido foi medida primeiro em
um JDK 25, onde `Paths.get("\\?\C:\...")` aceita a entrada e remove o prefixo, resultando em
`OUTSIDE_ROOTS`. Repetida no **JDK 11 que o release embarca**, a mesma expressão lança
`InvalidPathException: Illegal character [?]`, resultando em `UNRESOLVABLE`. As duas são recusa e a
garantia vale nos dois runtimes, mas o veredito difere — e é exatamente o caso que a restrição de
plataforma da constituição descreve: Java 11 é restrição de **runtime**, e medir na JVM da bancada
responde por outra máquina. O teste correspondente afirma "recusado", não um veredito específico.

O achado que decide a questão é o primeiro: **`getCanonicalPath()` não atravessa junções de
diretório no Windows.** Uma junção criada dentro de uma raiz declarada passaria por uma verificação
de prefixo canônica e a escrita cairia fora da raiz. `toRealPath()` a resolve. Isso não é hipótese —
está medido acima.

O segundo achado corrige uma suposição minha anterior, e vale registrar para que ninguém repita o
erro: **o fluxo alternativo de dados não é um buraco aberto hoje.** `checkDestination` chama
`getCanonicalFile()`, que lança `IOException` nesse caminho, e o `catch` existente já recusa. Mas
recusa **por acidente**, com a mensagem errada ("Pass an absolute path whose parent folder exists"),
e a recusa **desaparece** quando `allowExportIntoCaseFolder = true`, porque nesse caso
`checkDestination` retorna antes de canonicalizar e o `ArtifactWriter` escreve com
`FileOutputStream` — que a sondagem confirmou gravar no fluxo oculto, deixando o tamanho visível do
arquivo hospedeiro inalterado.

**Consequência de desenho não óbvia**: `toRealPath()` lança `NoSuchFileException` quando o arquivo
ainda não existe — e o destino de uma exportação nunca existe. A verificação precisa subir até o
ancestral existente mais profundo, resolvê-lo, e recompor o restante do caminho por cima. Isso
também resolve a ordem exigida por FR-002: hoje o `ArtifactWriter` cria as pastas intermediárias
antes de qualquer decisão; o algoritmo passa a ser **resolver → verificar → só então criar**.

**Alternativas descartadas**:
- *Comparação de prefixo textual sobre caminho normalizado* — falha na junção, conforme medido.
- *Manter `getCanonicalPath()` e acrescentar detecção de reparse point* — reimplementa mal o que
  `toRealPath()` já faz, e a lista de mecanismos a detectar não tem fim conhecido.
- *`Path.normalize()` sem tocar o sistema de arquivos* — resolve `..` e nada mais; junção passa.

---

## R2 — Nomes de dispositivo reservados dentro de uma raiz permitida

**Decisão**: verificar, **depois de escrever**, que o artefato existe no caminho resolvido e que seu
tamanho corresponde ao que foi gravado. Não manter lista de nomes proibidos.

**Rationale**: sondagem `DeviceProbe`, com um destino `allowed\NUL`:

```
under allowed root          = true      <- passa na allow-list
write                       -> OK       <- FileOutputStream não reclama
File.length() reported      = 0
Files.exists(normal path)   = false     <- o artefato não existe
```

O pedido não escapa da raiz, então **a allow-list de FR-001 não o pega**. O resultado devolvido ao
agente diria `destination` preenchido e `bytes: 0`, com sucesso — um artefato que sumiu, reportado
como gerado. É a mesma classe de defeito que a invariante "ausência ≠ vazio" do módulo existe para
impedir, e a única razão de não termos visto antes é que ninguém exportou para um nome de
dispositivo.

Na mesma sondagem, `CON` **criou um arquivo real** nesta máquina, e um nome com espaço ao final foi
rejeitado por `InvalidPathException`. Ou seja: o comportamento varia por nome e por versão do
Windows. Uma lista de nomes proibidos seria incompleta por construção; a verificação posterior é
insensível a qual mecanismo fez o arquivo sumir.

**Isto era lacuna do spec**, encontrada na pesquisa. **FR-034** e **SC-015** foram criados em
2026-08-10, por decisão do perito, e a quinta entrada da seção Clarifications do spec registra a
origem do achado.

**Alternativas descartadas**: lista de nomes reservados (incompleta e dependente de versão);
recusar antes de escrever por heurística de nome (mesmo problema, e recusaria nomes legítimos).

---

## R3 — Forma do transporte de rede

**Decisão**: `ServerSocket`/`Socket` de `java.net`, com um **relay stdio↔socket** distribuído no
mesmo jar como segunda classe `main`. O harness continua sendo configurado exatamente como hoje —
ele sobe um processo local que fala stdio — e esse processo é o relay, que autentica e bombeia bytes
até o servidor.

**Rationale**: três coisas caem no lugar.

1. `McpServerMain.start(InputStream, OutputStream)` **já é agnóstico de transporte** — foi escrito
   assim para FR-064 (inicialização programática). Servir um socket é passar
   `socket.getInputStream()`/`getOutputStream()` para o mesmo método. Nenhuma mudança em
   `JsonRpcCodec`, `McpDispatcher` ou em qualquer ferramenta, o que entrega FR-015 (paridade entre
   transportes) por construção e não por disciplina.
2. `java.net` está em `java.base`. O release embarca um JRE 11 montado como artefato
   (`java:jre:zip`); depender de qualquer módulo fora de `java.base` corre o risco de ele não estar
   presente. Sockets não correm esse risco.
3. O relay preserva a configuração dos três harnesses. `iped-mcp` não tem `mainClass` configurado no
   pom — é invocado por `-cp lib/* iped.mcp.McpServerMain`. Uma segunda classe `main` custa zero em
   empacotamento.

**Correção vinda do primeiro teste de campo (2026-08-11).** O relay foi escrito bombeando os dois
sentidos e retornando quando o servidor desligasse. Contra o servidor real ele respondeu as duas
requisições corretamente e **não terminou**: o bombeamento de subida para no fim da entrada e nada
avisava o servidor, que seguia esperando requisição enquanto o relay seguia esperando resposta. A
peça que faltava é o **meio-fechamento** da conexão (`Socket.shutdownOutput`) quando a entrada do
harness acaba — só isso faz o servidor ver fim de entrada, encerrar a sessão e devolver o caso.

Vale registrar por que a suíte não pegou: um relay pendurado **passa em qualquer teste de
requisição/resposta**, porque as respostas estão corretas. O que falha é o encerramento, e nada
exercitava o encerramento. `RelayShutdownTest` passa a exercitar, e FR-035 passa a exigir.

**Alternativas descartadas**:
- *Transporte MCP Streamable HTTP com `com.sun.net.httpserver`* — permitiria ao harness conectar
  nativamente por `type: remote`, sem relay. Descartado por dois motivos: `jdk.httpserver` é módulo
  fora de `java.base` e a presença dele no JRE embarcado não está garantida; e implementar a
  semântica de POST + SSE do transporte corretamente, sem o SDK oficial (que exige Java 17+, ver
  research de 001), é trabalho de protocolo que a feature não pede. Fica registrado como o caminho
  natural caso o módulo um dia migre para Java 17+.
- *Depender do suporte nativo a MCP remoto de cada harness* — a configuração passaria a divergir
  entre Claude Code, Codex e OpenCode, e o segredo de FR-013 teria que caber no formato de cada um.
  O relay concentra isso num lugar só.
- *Named pipe / domínio Unix* — resolveria o caso "outra conta na mesma máquina", que é o Nível 1 do
  plano operacional, mas não o caso "outra máquina", que é o que o spec pede. `AF_UNIX` em Java só
  chegou no 16.

---

## R4 — Onde o segredo compartilhado entra no protocolo

**Decisão**: handshake de transporte **antes** do JSON-RPC. A conexão emite uma primeira linha com o
segredo; o servidor responde aceite ou fecha. Só depois disso os fluxos são entregues ao
`McpServerMain.start`.

**Rationale**: manter a autenticação fora do JSON-RPC preserva a propriedade de que o dispatcher e
as ferramentas não sabem qual transporte as trouxe — que é o que sustenta FR-015. Se o segredo
viajasse em `initialize`, o `McpDispatcher` passaria a ter um estado "autenticado ou não" e todo
caminho de ferramenta precisaria consultá-lo; a primeira ferramenta que esquecesse a consulta seria
um vazamento. Fora do protocolo, uma conexão não autenticada **nunca chega** ao dispatcher, o que é
o que FR-013 pede ("não obtém resposta a nenhuma ferramenta, nem informação que revele a existência
de casos").

Comparação do segredo por tempo constante, para não transformar a latência de resposta em oráculo.

**Alternativas descartadas**: segredo em `initialize` params (acima); segredo por variável de
ambiente do processo servidor sem verificação por conexão (não autentica nada, só configura).

---

## R5 — Uma sessão por processo passa a ser uma sessão por conexão

**Decisão**: `Session` deixa de ser por processo e passa a ser **por conexão**. Os casos abertos
passam a viver em um **pool compartilhado por processo**, com contagem de referências por caminho de
caso; cada sessão mantém sua própria trilha de auditoria, seu próprio `ConcurrencyGuard` e sua
própria reivindicação de escrita.

**Rationale**: o Javadoc de `Session` diz literalmente "One session per process", e a classe possui
`caseRegistry`, `auditTrail`, `auditSync`, `concurrencyGuard` e `egressPolicy`. FR-014 exige
sessões simultâneas, então `Session` precisa se multiplicar. O que **não** pode se multiplicar junto
é o `IPEDSource`: abrir um caso de 10 milhões de itens custa até 30 segundos (SC-015 de 001) e
memória proporcional ao acervo. Duas sessões somente-leitura sobre o mesmo caso abrindo dois
`IPEDSource` dobrariam ambos, e SC-006 desta feature exige que as metas de desempenho de 001
continuem valendo.

Leitura concorrente sobre o mesmo `IndexSearcher` do Lucene é segura, que é o que sustenta o
compartilhamento.

**Alternativas descartadas**:
- *`CaseRegistry` por sessão, sem pool* — desenho mais simples e custo de memória multiplicado pelo
  número de sessões. Rejeitado por SC-006.
- *Um processo servidor por conexão* — resolveria isolamento sem nenhum trabalho de concorrência, e
  reintroduziria o custo de abertura de caso por sessão, além de multiplicar os `ConcurrencyGuard`
  em processos distintos, o que faria duas sessões do mesmo perito disputarem o lock de escrita como
  se fossem adversárias.

---

## R6 — Exclusividade de escrita entre sessões

**Decisão**: reaproveitar o `ConcurrencyGuard` existente, acrescentando um **registro em memória de
reivindicações** (`caseId` → `sessionId`) para que o diagnóstico possa nomear a sessão detentora.

**Rationale**: o mecanismo já implementa exatamente a semântica que FR-029 pede, e por acaso já
cobre o caso novo. `acquireWriteLock` abre um `RandomAccessFile` sobre `access.lock` na subpasta de
auditoria do caso e chama `tryLock()`. Duas sessões **no mesmo processo** abrem canais distintos
sobre o mesmo arquivo, e a segunda recebe `OverlappingFileLockException` — que o código **já captura**
e converte em `lock == null` → `CONCURRENT_ACCESS`. A exclusividade por caso entre sessões do mesmo
processo, portanto, já funciona, e é por caso e não por servidor, satisfazendo FR-029.

FR-031 também sai de graça: `probeBookmarksState` roda antes do lock e continua detectando a UI do
IPED independentemente de qual sessão pediu.

Duas coisas precisam mudar. A mensagem diz "by another process on this machine", que passa a ser
falsa quando a detentora é outra sessão do mesmo processo. E FR-029 exige identificar a detentora —
ler o conteúdo do `access.lock` não é caminho confiável enquanto o arquivo está travado, daí o
registro em memória.

**Alternativa descartada**: semáforo próprio por caso em memória, ignorando o arquivo de lock.
Perderia a detecção entre processos `iped-mcp` distintos, que é o que o arquivo de lock existe para
dar.

---

## R7 — Reconciliação de trilhas concorrentes

**Decisão**: manter uma trilha por sessão e acrescentar, na subpasta de auditoria do caso, um
**manifesto de sessões** append-only — uma linha por sessão que tocou o caso, com identificador,
instante de início e término, transporte, origem e as duas identidades de operador. A ordenação
entre trilhas se apoia no carimbo de tempo já presente em cada registro.

**Rationale**: as trilhas **já não colidem**. `AuditTrail` grava em
`session-<uuid>.jsonl` e `AuditSync.syncTarget` copia preservando o nome do arquivo para
`<caso>/mcp-audit/`. Sessões simultâneas produzem arquivos distintos no mesmo diretório, o que é
metade do que FR-033 pede. Falta a outra metade: um examinador que abre a pasta vê N arquivos e não
tem como saber se são todos, nem em que ordem lê-los.

O manifesto responde às duas. É append-only pelo mesmo motivo que a trilha é, e não reordena nem
reescreve nada — o que respeita a restrição registrada nas Assumptions do spec: a ordem dos campos
de `AuditRecord.toNodeWithoutHash` faz parte do hash de trilhas já emitidas e não pode ser tocada.

**Alternativas descartadas**:
- *Trilha única por caso, compartilhada entre sessões* — exigiria encadeamento por hash com escrita
  concorrente, serializando as sessões no ponto mais quente e invalidando o formato das trilhas já
  emitidas.
- *Reconciliar só na leitura, varrendo o diretório* — não distingue trilha ausente de trilha que
  nunca existiu, que é precisamente a distinção que FR-074 de 001 já se preocupa em preservar.

---

## R8 — Identidade dupla do operador

**Decisão**: `Session.operator` passa a ser um par. A identidade autoritativa continua sendo
`System.getProperty("user.name")` do processo servidor; a alegada chega no handshake do transporte,
junto do segredo, e é registrada em campo distinto e nomeado como alegação.

**Rationale**: o campo único de hoje carrega um comentário que amarra a decisão à premissa que esta
feature remove — "D2: single-operator workstation, no authentication of its own". Com a alegação
vindo no mesmo handshake do segredo, ela entra antes de qualquer ferramenta e vale para a sessão
inteira, sem depender de o agente declarar algo por ferramenta.

O ponto delicado é FR-032: a distinção precisa sobreviver à exportação legível por humano da trilha
(FR-036 de 001). Nome de campo por si só não basta em uma exportação para humano — a rotulagem
precisa dizer "não verificada" onde a alegação aparece.

**Alternativa descartada**: derivar a identidade da origem da conexão. Endereço de rede não é
pessoa, e apresentá-lo como operador num laudo seria pior do que não registrar.

**Revisão na implementação (2026-08-11).** O desenho previa acrescentar campos a `AuditRecord` para a
alegação, o transporte e a origem. Ao implementar ficou claro que isso colide com a restrição que o
módulo já documentava: a ordem dos campos de `toNodeWithoutHash` faz parte do hash, e `AuditTrail.verify`
recomputa esse nó a partir do que lê — um campo a mais mudaria o resultado para registros já emitidos.
A alegação passou a viajar dentro do campo `operator`, que já existe, renderizada com a palavra
"unverified" no próprio valor; transporte e origem foram para o manifesto de sessões, onde são
propriedade da sessão e não de cada operação. Ver a seção correspondente do [data-model.md](./data-model.md).
