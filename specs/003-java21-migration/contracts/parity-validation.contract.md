# Contrato — Validação de Paridade Forense (Java 11 baseline ↔ Java 21)

Define **o que** comparar e **como**, materializando FR-003 e SC-002 conforme a [Clarification Q2](../spec.md) (igualdade **semântica por campo**, ignorando cosmético/não-determinístico).

## Insumos

- **Caso-baseline**: gerado pelo release **Java 11** sobre o conjunto de dados de referência, com um **profile fixo** (recomendado: `forensic`), congelado.
- **Caso-candidato**: gerado pelo release **Java 21** sobre o **mesmo** conjunto, **mesmo** profile, **mesmo** timezone (`-tz`) e mesmas flags.

## Campos comparados (devem ser IGUAIS — zero divergência)

| # | Campo | Como comparar |
|---|---|---|
| C1 | Hashes por item (MD5, SHA-1/256/512, Edonkey) | Igualdade exata, casado por trackID/caminho. |
| C2 | Detecção de assinatura/MIME (`type`) | Igualdade exata por item. |
| C3 | Contagem total de itens e por categoria | Igualdade de contagens. |
| C4 | Categorização (`category`) por item | Igualdade do conjunto de categorias por item. |
| C5 | Texto extraído (full-text) | Igualdade **normalizada** (ver normalização abaixo). |
| C6 | Itens recuperados por carving | Igualdade de contagem + offsets/tamanhos. |
| C7 | Matches YARA (`yara:tag`, `yara:match:<rule>`) | Igualdade do conjunto de matches por item. |
| C8 | Eventos de timeline | Igualdade do conjunto de eventos (tipo + timestamp + item). |

## Exclusões explícitas (NÃO contam como divergência)

- **E1**: timestamps de geração de relatório / metadados de execução (data do processamento, duração).
- **E2**: ordem de itens dependente de multi-threading (comparar como **conjunto**, não como sequência; ordenar por trackID antes de comparar).
- **E3**: bytes de thumbnail / reencode de imagem por bibliotecas atualizadas (comparar **existência** do thumbnail, não os bytes).
- **E4**: caminhos absolutos da máquina de teste; IDs de sessão; nomes de host.
- **E5**: diferenças puramente de whitespace/normalização Unicode no texto (ver normalização).

## Normalização (para C5 — texto)

Antes de comparar: trim, colapsar whitespace repetido, normalizar quebras de linha (`\r\n`→`\n`), normalização Unicode NFC. Divergência **real** = diferença de conteúdo após normalização (indica regressão de parser/extração).

## Procedimento

1. Processar o dataset no release 11 → `caso-baseline` (congelar).
2. Processar o **mesmo** dataset no release 21 → `caso-candidato`.
3. Extrair os campos C1–C8 de ambos (via export CSV/Web API/consulta ao índice), casando itens por **trackID**.
4. Aplicar exclusões E1–E5 e a normalização de texto.
5. **Resultado esperado (SC-002)**: **zero divergências** em C1–C8. Qualquer divergência é triada:
   - Divergência benigna não prevista → adicionar à lista de exclusões **com justificativa documentada** (e revisão).
   - Divergência de conteúdo (hash, texto, match, contagem) → **regressão** → corrigir antes de prosseguir.

## Performance (SC-005)

- Medir throughput (itens/s ou GB/h) de baseline e candidato no **mesmo hardware/dataset**.
- **Critério**: candidato ≥ baseline, admitida **regressão máxima de 5%**. Regressão > 5% é tratada como defeito de migração.

## ~~Abertura de casos antigos~~ — **Removido (2026-06-01)**

> Toda a seção saiu do escopo: FR-004 (abrir índice/storage/bookmarks antigos), FR-005 (portáteis antigos) e FR-007 (graph store 4.x) foram retirados. Casos são **autocontidos** (acompanham JRE + libs do seu processamento e são analisados com elas), então o release novo não abre casos de outra versão. A busca/navegação/render de um caso **recém-processado** é coberta pela própria geração do caso-candidato + validação de viewers (FR-011).
