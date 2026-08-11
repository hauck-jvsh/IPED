# Phase 1 — Roteiro de validação

**Feature**: 006-export-allowlist-socket-transport | **Data**: 2026-08-10

Dez cenários, cada um ligado ao critério de sucesso que ele fecha. Os quatro primeiros validam a US1 e
**não dependem de nada do transporte** — é assim que se comprova que a primeira entrega é autônoma.

Detalhes de configuração estão em [contracts/config-surface.md](./contracts/config-surface.md); o
handshake, em [contracts/transport-handshake.md](./contracts/transport-handshake.md).

## Pré-requisitos

| | |
|---|---|
| Release montado | `mvn clean install`, com `JAVA_HOME` apontando para um JDK 11 com JavaFX |
| Caso processado | Pasta de saída do IPED contendo subpasta `iped`. Referida como `<CASO>` |
| Ambiente isolado | Para Q3 e Q5–Q7: uma VM (Lima/QEMU, WSL2 ou Hyper-V) ou uma segunda máquina, **sem acesso ao sistema de arquivos onde `<CASO>` está** |
| Plataforma | Windows é a plataforma de referência de Q1: os mecanismos de escape medidos em [research.md](./research.md) R1 e R2 existem lá |

> **Um teste pulado não é um teste que passou.** A feature 001 encerrou com o caso de referência não
> construído (T006 dispensada) e 47 testes pulando por isso. Os cenários abaixo que exigem `<CASO>`
> estão marcados; se forem pulados, registre isso como não verificado, não como verde.

---

## Q1 — Confinamento de escrita resiste aos vetores medidos (SC-001)

Fecha a US1. **Não exige `<CASO>` para a parte unitária.**

```powershell
mvn -pl iped-mcp test -Dtest=PathConfinementTest
```

A bateria precisa cobrir, no mínimo, os vetores que a sondagem de R1 e R2 confirmou como reais nesta
plataforma:

| Vetor | Esperado |
|---|---|
| Caminho relativo com `..` saindo da raiz | Recusado |
| **Junção de diretório** dentro da raiz apontando para fora | Recusado — este é o vetor que a implementação atual deixa passar |
| Fluxo alternativo de dados sobre arquivo permitido | Recusado, com veredito `UNRESOLVABLE`, não com exceção técnica |
| Nome curto 8.3 e diferença de caixa nomeando destino externo | Recusado |
| Prefixo `\\?\` nomeando destino externo | Recusado |
| Destino dentro da pasta do caso, com a raiz declarada contendo o caso | Recusado — FR-004 prevalece |
| **Nome de dispositivo reservado dentro da raiz** (`NUL`, `CON`, `COM1`) | **Aprovado na contenção** e depois reprovado na verificação pós-escrita: falha reportada, nunca sucesso sobre arquivo inexistente (FR-034, SC-015) |
| Destino válido dentro da raiz | Permitido, e conferido como existente com o tamanho escrito |

**Verificação adicional exigida por FR-002**: após cada recusa, nenhum arquivo e **nenhuma pasta**
existem no destino pedido. É o que separa esta implementação da atual, que cria as pastas
intermediárias antes de decidir.

**A linha do dispositivo reservado testa outra garantia.** Ela não é sobre contenção — o destino está
legitimamente dentro da raiz — e sim sobre o resultado da escrita. Um teste que só verifique o veredito
de contenção passa com o defeito presente. O que precisa ser afirmado é a resposta devolvida ao agente:
falha com diagnóstico, nunca sucesso com `bytes: 0`.

Criar a junção no teste:

```powershell
cmd /c mklink /J "<raiz>\escape" "<fora-da-raiz>"
```

Removê-la com `cmd /c rmdir "<raiz>\escape"` — `Remove-Item -Recurse` sobre junção alcança o alvo.

## Q2 — Instalação padrão não abre porta (SC-002)

```powershell
# subir o servidor sem configurar transporte
java -Diped.mcp.ipedRoot=<IPED_ROOT> -cp "<IPED_ROOT>\lib\*" iped.mcp.McpServerMain
# noutro terminal
Get-NetTCPConnection -State Listen -OwningProcess <pid>
```

Esperado: **nenhuma porta** do processo. FR-011 exige que a ausência de configuração baste; o perito
não precisa saber que existe algo a desligar.

## Q3 — Harness em ambiente isolado obtém os mesmos resultados (SC-003) — exige `<CASO>`

Servidor no hospedeiro com `transport = socket`; relay e harness na VM, sem montagem do caso.

Roteiro de consulta de referência executado nos dois transportes, comparando o conjunto de itens
devolvido. Esperado: **divergência zero**.

Verificação que dá sentido ao cenário: de dentro da VM, confirmar que `<CASO>` **não é alcançável**
pelo sistema de arquivos. Se for, o teste está medindo outra coisa.

## Q4 — Autenticação (SC-011)

| Tentativa | Esperado |
|---|---|
| Conexão sem segredo | Fechada, nenhuma resposta de ferramenta, nada revelado sobre casos |
| Conexão com segredo incorreto | Idem, e tentativa registrada com a origem (FR-027) |
| `transport = socket` sem segredo resolvível | **Ponto de escuta não estabelecido**, causa reportada (FR-026) |
| Conexão com segredo correto | Serve normalmente |

O terceiro é o que importa mais: uma configuração incompleta não pode degradar para transporte aberto.

## Q5 — Sessões concorrentes e exclusividade de escrita (SC-012) — exige `<CASO>`

Duas sessões autenticadas sobre o mesmo caso.

1. Ambas consultam → ambas respondem, **nenhuma bloqueia a outra**, resultados idênticos aos de uma
   sessão isolada.
2. A segunda pede escrita enquanto a primeira detém a reivindicação → recusada com
   `CONCURRENT_ACCESS` **nomeando a sessão detentora**.
3. A primeira cai → a segunda obtém a escrita, **sem reinício do servidor** (FR-030).
4. Com a UI do IPED abrindo o caso na mesma máquina → escrita recusada mesmo para a detentora
   (FR-031). As duas condições são independentes.

Vale medir aqui, e não só observar: com duas sessões sobre um caso grande, a abertura da segunda deve
ser barata. Se custar os mesmos 30 s da primeira, o pool de casos não está compartilhando e SC-006
está ameaçado.

## Q6 — Queda de conexão (SC-005) — exige `<CASO>`

Derrubar o cliente no meio de uma exportação de milhares de itens.

Esperado: o servidor permanece disponível; o caso é liberado; a operação interrompida **consta da
trilha com desfecho**. Um registro `STARTED` sem desfecho correspondente não pode ser o resultado
normal de uma desconexão.

## Q7 — Reconciliação da trilha (SC-013) — exige `<CASO>`

Conduzir um exame com **duas sessões simultâneas**, encerrar, e entregar a um segundo examinador
apenas o que acompanha o caso.

Esperado: ele reconstitui a sequência completa e chega ao mesmo conjunto de itens **sem saber de
antemão quantas sessões existiram**. O manifesto de sessões é o que responde "são todas?" e "em que
ordem?".

## Q8 — Invariante somente-leitura sob socket (SC-004) — exige `<CASO>`

Repetir a verificação bit a bit de SC-003 de 001 — evidência, índice e estado de análise idênticos
após sessão somente-leitura, subpasta de auditoria excluída **por nome** — desta vez com a sessão
estabelecida por rede.

```powershell
mvn -pl iped-mcp test -Dtest=ReadOnlyInvariantTest -Diped.mcp.test.referenceCase=<CASO>
```

## Q11 — O relay encerra quando o harness encerra (SC-016)

Não exige `<CASO>`. É o cenário que o primeiro teste de campo reprovou.

Suba o servidor com transporte de rede, alimente o relay com um par de requisições por sua entrada
padrão e **feche essa entrada**, como um harness faz ao sair.

Esperado: as respostas chegam **e o processo termina**, sem espera. Um relay que responde tudo
corretamente e fica pendurado é o defeito exato que este cenário existe para pegar — ele passa em
qualquer verificação de requisição/resposta, porque as respostas estão certas.

Verificar também do lado do servidor: a sessão correspondente encerrou e o caso não ficou retido.

```powershell
mvn -pl iped-mcp test -Dtest=RelayShutdownTest
```

O teste automatizado cobre o mecanismo; o cenário acima cobre o processo real, e foi o processo real
que revelou o problema.

## Q9 — Postura vigente é verificável de dentro (SC-008)

Em cada configuração — sem transporte de rede, com transporte de rede —, consultar `iped_session_info`
e comparar com o estado observável do sistema operacional (`Get-NetTCPConnection`) e com o arquivo de
configuração.

Esperado: coincidência em 100%. Um perito que não participou da configuração precisa conseguir
determinar o que está exposto sem ler código.

## Q10 — Desempenho preservado (SC-006) — exige caso grande

```powershell
mvn -pl iped-mcp test -Dtest=ScalePerformanceTest -Diped.mcp.test.largeCase=<CASO_GRANDE>
```

Repetir sob `transport = socket` com servidor e harness na mesma máquina física. Esperado: primeira
página < 5 s em 95% das consultas, abertura de caso < 30 s. O `ScalePerformanceTest` contra caso
grande continua inegociável pelo motivo já registrado no módulo — uma implementação que materializa o
conjunto passa em todas as outras suítes e só falha em campo.

---

## Mapa de cobertura

| Cenário | Critérios | História |
|---|---|---|
| Q1 | SC-001, SC-015 | US1 |
| Q2 | SC-002 | US2 |
| Q3 | SC-003 | US2 |
| Q4 | SC-011 | US2 |
| Q5 | SC-012 | US2 |
| Q6 | SC-005 | US2 |
| Q7 | SC-013 | US2 |
| Q8 | SC-004 | US1 + US2 |
| Q11 | SC-016 | US2 |
| Q9 | SC-008 | US3 |
| Q10 | SC-006 | US2 |

Sem cenário próprio, verificados por inspeção durante os demais: **SC-007** (diagnóstico acionável em
toda falha de configuração, exercitado em Q4 e no diagnóstico de inicialização), **SC-009**
(documentação da topologia dividida, medida na primeira instalação por um perito que não a escreveu),
**SC-010** (advertência de abertura, observada em Q3 e Q8) e **SC-014** (identidade alegada
distinguível, observada em Q7).
