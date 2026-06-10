# Contrato — Detecção e aviso de versão de Java

Único comportamento visível ao usuário que **muda** nesta migração. Os demais contratos (flags da CLI, Web API REST, formato de caso) permanecem **invariantes** (FR-016/FR-018) — ver nota ao final.

## Componentes

- `iped.engine.util.Util.getJavaVersionWarn()` — fonte da decisão.
- `iped.engine.util.Util.MIN_JAVA_VER` / `MAX_JAVA_VER` — limites.
- Consumidores: `Bootstrap` (CLI), `AppMain` (UI), `Statistics`, `ProgressFrame`.
- Mensagens: `iped-app/resources/localization/iped-engine-messages*.properties` (`JavaVersion.*`).

## Contrato (estado-alvo)

| Entrada (`java.version`) | Saída esperada |
|---|---|
| `< 21` | Mensagem **`JavaVersion.Error`** ("versão não suportada; atualize para 21"). |
| `== 21` (qualquer `21.x.y`) | **`null`** (suportado; **sem** aviso). |
| `> 21` (ex.: 22, 25) | Mensagem **`JavaVersion.Warn`** ("versão não testada; podem ocorrer erros"). |
| arquitetura sem "64" | `JavaVersion.Arch` (inalterado). |

### Regras
- **CTR-1**: `MIN_JAVA_VER = 21` e `MAX_JAVA_VER = 21` (hoje 11/14). Resolve o falso aviso de "não testada" em 21 (FR-012/SC-008).
- **CTR-2**: o parsing de versão deve tratar o esquema do Java ≥ 9 (`"21"`, `"21.0.5"`) — o código atual já lida com `1.x` legado e com `major.minor`; manter robusto para `21.0.x`.
- **CTR-3**: `buggedVersions` (bug WebView JDK-8196011, específico de Java 8) pode permanecer — é inócuo em 21; não adicionar entradas sem motivo.
- **CTR-4**: as 4 chaves `JavaVersion.{Error,Warn,Bug,Arch}` permanecem em **PT-BR + EN** (no mínimo); textos revisados se mencionarem "11"/"14".

### Teste
- Unit: alimentar `getJavaVersionWarn()` com `21`, `21.0.5`, `17`, `25`, `11` e asseverar a chave de mensagem correta (ou `null` para 21).
- Smoke: iniciar o release no runtime 21 e confirmar ausência de aviso (FR-012).

## Contratos invariantes (não mudam — apenas reafirmados)

- **CLI** (`Bootstrap`/`Main`): flags `-d`, `-o`, `-profile`, `--append`/`--continue`/`--restart`, `--yara-only`, etc. — **sem mudança** (FR-018).
- **Web API REST** (`/search`, `/sources`, `/content`, `/text`, `/thumbnail`, `/bookmarks`): rotas, parâmetros e respostas — **sem mudança** (FR-010); apenas o stack (Jersey 2.41) é atualizado por baixo.
- **Formato de caso** (índice Lucene, storage SQLite, bookmarks): formato **inalterado** — chaves de campo e `AppAnalyzer` congelados (Princípio I; evita churn e mantém o caso legível com suas próprias libs). (FR-004 retirado em 2026-06-01 — não é mais requisito o release novo abrir casos antigos.)
- **Configs/profiles/localização**: inalterados (FR-016).
