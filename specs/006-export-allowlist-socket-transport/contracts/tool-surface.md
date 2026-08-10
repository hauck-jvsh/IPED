# Contrato — efeito na superfície de ferramentas MCP

**Nenhuma ferramenta é criada, removida ou renomeada.** As 25 existentes continuam com os mesmos
nomes e os mesmos parâmetros, e se comportam de forma idêntica nos dois transportes (FR-015). O que
muda são vereditos, campos de resposta e advertências — em três ferramentas.

## `iped_export`

O parâmetro `destination` continua existindo, com o mesmo nome e o mesmo tipo. O que muda é o que o
servidor faz com ele.

| Antes | Depois |
|---|---|
| Recusa se o pai canônico é a pasta do caso; permite o resto do disco | Recusa se o caminho real não está sob nenhuma raiz declarada, **e** se está dentro da pasta do caso |
| Pastas intermediárias criadas antes de qualquer decisão | Criadas só depois do veredito `ALLOWED` (FR-002) |
| `getCanonicalPath()` — não atravessa junção de diretório | `toRealPath()` sobre o ancestral existente mais profundo |
| Fluxo alternativo de dados vira `IOException` genérica | `InvalidPathException` vira recusa nomeada |
| `bytes` reportado sem verificação | Existência e tamanho conferidos após a escrita — ver nota sobre lacuna do spec |

Recusa devolve `DESTINATION_REFUSED` com as raízes permitidas nomeadas, para que o agente corrija na
tentativa seguinte sem intervenção do perito (FR-008). Toda recusa vai para a trilha com o destino
pedido e a regra aplicada (FR-007), no mesmo padrão que FR-041 de 001 usa para conteúdo bloqueado
por política de egresso.

Em sessão de rede, a resposta declara explicitamente que `destination` é caminho **no sistema de
arquivos do servidor** (FR-019). O perito está em outra máquina e, sem isso, não tem como distinguir
qual sistema de arquivos produziu o caminho devolvido.

A verificação pós-escrita atende **FR-034**, criado a partir da sondagem de
[research.md R2](../research.md): um destino como `<raiz>\NUL` passa na contenção — corretamente, já
que não escapa de raiz alguma —, a escrita é aceita e o artefato não existe. O mecanismo é conferir o
resultado, nunca julgar o nome: o conjunto de nomes que se comportam assim varia por sistema e por
versão, e na mesma sondagem `CON` criou um arquivo real enquanto `NUL` não criou.

## `iped_session_info`

Ganha campos, não perde nenhum. A postura vigente passa a incluir (FR-022):

- transporte ativo — `STDIO` ou `SOCKET`;
- ponto de escuta, quando houver;
- raízes de escrita declaradas, com o estado de cada uma;
- reivindicação de escrita por caso aberto, com a sessão detentora;
- identidade do operador como par, com a alegada rotulada como não verificada.

Responde inclusive quando o transporte de rede está inativo, no mesmo padrão de FR-042 de 001 para a
política de egresso: uma configuração de segurança que não pode ser verificada de dentro é uma
configuração em que ninguém confia.

## Advertências de abertura de sessão

A lista que `Session` já monta (FR-043 de 001) ganha, em sessão de rede, que o conteúdo de evidência
**trafega por conexão de rede e o canal não é protegido** (FR-023). Sob transporte local esse conteúdo
nunca deixava o processo; a mudança é material e é dela que depende a decisão do perito de manter o
trânsito confinado a uma máquina física.

## Erros

Sem código novo. Dois existentes ganham conteúdo:

| Código | Mudança |
|---|---|
| `DESTINATION_REFUSED` | Passa a distinguir `OUTSIDE_ROOTS`, `INSIDE_CASE` e `UNRESOLVABLE`, e a nomear as raízes permitidas |
| `CONCURRENT_ACCESS` | Passa a distinguir "outra sessão deste servidor" de "outro processo nesta máquina", e a nomear a sessão detentora (FR-029). A mensagem atual afirma "by another process on this machine", que se torna falsa quando a detentora é outra sessão do mesmo processo |

## O que não muda

- Paginação, agregação, vocabulário, inspeção de item, curadoria: nada.
- Política de egresso: aplicada na mesma fronteira, com a mesma força.
- Formato da trilha de auditoria: a ordem de campos de `AuditRecord.toNodeWithoutHash` faz parte do
  hash de trilhas já emitidas e não é tocada. Campos novos entram sem reordenar os existentes.
- Comportamento sob `transport = stdio`: idêntico ao de hoje, exceto pelo confinamento de escrita,
  que vale nos dois transportes.
