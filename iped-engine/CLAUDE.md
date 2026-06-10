# Módulo `iped-engine`

> **Coração do IPED.** Orquestra todo o ciclo de processamento: leitura de evidências (imagens forenses, pastas, casos UFED/IPED), pipeline paralelo de tarefas, indexação Lucene, busca, geração de relatórios, API Web e graph analytics.

> Mudanças em `Manager`, `Worker`, `ProcessingQueues`, `IndexWriter` ou nas configs base têm impacto em **tudo**. Trate este módulo com cuidado redobrado.

## 1. Propósito

- **Ingestão**: ler imagens forenses (Sleuthkit), casos IPED, relatórios UFED, AD1 e pastas comuns.
- **Pipeline paralelo**: workers consumindo filas prioritárias com uma cadeia configurável de `AbstractTask`s.
- **Análises**: hashing, signature/MIME, parsing (Tika), carving, OCR, regex, NER, detecção de idioma, similaridade de imagens, reconhecimento facial, DIE (CSAM), PhotoDNA, transcrição de áudio.
- **Indexação Lucene 9.2.0** com `AppAnalyzer` customizado e suporte multi-source.
- **Busca**: `IPEDSearcher`, `IItemSearcher`, `LuceneSearchResult`.
- **Sleuthkit out-of-process**: cliente/servidor C++ isola crashes nativos.
- **Relatórios**: HTML, CSV, casos portáteis, bookmarks.
- **Web API**: REST/Swagger (Jersey + Grizzly) para busca remota de casos.
- **Graph**: análise de relacionamentos em Neo4j (chamadas, e-mails, mensagens, redes wireless).
- **Extensibilidade**: scripts JavaScript (Nashorn) e Python (Jep) para tarefas customizadas.

Versão: `4.4.0-SNAPSHOT`. Java 21+. Lucene 9.2.0, Tika 2.4.0, Sleuthkit 4.12.0.p1.

## 2. Estrutura de pacotes

```
iped/engine/
├── core/         # Manager, Worker, ProcessingQueues, Statistics, EvidenceStatus, QueuesProcessingOrder
├── task/         # AbstractTask + ~100 tarefas concretas (HashTask, ParsingTask, IndexTask, CarverTask, ...)
│   ├── carver/   # BaseCarveTask, CarverTask, KnownMetCarveTask, LedCarveTask, XMLCarverConfiguration
│   ├── die/      # DIETask + RandomForestPredictor (nudity detection)
│   ├── index/    # IndexTask, IndexItem, ElasticSearchIndexTask
│   ├── jumplist/ # JumpListTask + AppIDCalculator
│   ├── regex/    # RegexTask + ~30 validadores (CPF, CNPJ, cartões, crypto, ...)
│   ├── similarity/  # ImageSimilarityTask, SimilarFaces, ...
│   └── transcript/  # AudioTranscriptTask + implementações Vosk/Microsoft/Google/Whisper
├── config/       # ConfigurationManager + ~55 Configurables (AbstractTaskConfig, IndexTaskConfig, ...)
├── data/         # Item, IPEDSource, CaseData, Bookmarks, Category, DataSource
├── datasource/   # SleuthkitReader, IPEDReader, FolderTreeReader, UfedXmlReader, AD1DataSourceReader
├── sleuthkit/    # SleuthkitClient (pool de processos), SleuthkitInputStreamFactory, SleuthkitJNI binding
├── lucene/       # AppAnalyzer, StandardASCIIAnalyzer, ConfiguredFSDirectory, CustomIndexDeletionPolicy
├── search/       # IPEDSearcher, ItemSearcher, LuceneSearchResult, QueryBuilder, SimilarImagesSearch
├── io/           # ParsingReader, ParsingProcess (out-of-process), MetadataInputStreamFactory, FragmentingReader
├── graph/        # GraphTask, GraphService (Neo4j), Cypher templates
├── webapi/       # Jersey REST endpoints: Search, Sources, Content, Text, Thumbnail, Bookmarks
├── preview/      # PreviewRepositoryManager, MinIO, filesystem
├── localization/ # Messages bundle
├── log/          # Setup de log4j2
├── hashdb/       # HashDBTool (CLI), HashDBLookup, NSRL/ProjectVic import
├── tika/         # Tika config customizado
└── util/         # Util, UIPropertyListenerProvider, ...
```

Resources em `iped-engine/src/main/resources/`:
- `iped/engine/graph/links/*.cypher` — templates Cypher para análise de comunicação.
- `swift/*.csv` — códigos SWIFT.
- `META-INF/services/`:
  - `iped.engine.task.regex.RegexValidatorService` — plugins de validadores.
  - `iped.engine.graph.links.SearchLinksQuery` — plugins de queries de graph.

## 3. Conceitos centrais

### Manager
Orquestrador único do caso. [`iped/engine/core/Manager.java`](src/main/java/iped/engine/core/Manager.java).
- Cria `IndexWriter` (Lucene) compartilhado entre workers.
- Inicializa o pipeline (via `TaskInstaller`).
- Lança o `counter` (conta itens sem enfileirar) e o `producer` (efetivamente enfileira).
- Lança N `Worker`s (tipicamente `min(processors, cores configurados)`).
- Loop `monitorProcessing()`: reporta progresso, checa exceções, faz commits periódicos.
- Pós-processamento: mapeamentos reversos (lucene-id ↔ item-id), filtros, logs.

### Worker
Thread. [`iped/engine/core/Worker.java`](src/main/java/iped/engine/core/Worker.java). Estados `RUNNING`, `PAUSING`, `PAUSED`.
- Pop de item em `ProcessingQueues.nextItem()`.
- Executa cadeia de tasks: `firstTask.process(item)` → `task.nextTask.process(item)` → ...
- Subitens (zip extraído, arquivo recuperado por carving) chamam `worker.processNewItem(item)` → reenfileirados.
- Cada worker tem **instâncias próprias** de cada `AbstractTask` (evita sincronização).
- Excepção → propagada ao Manager, que aborta processamento.

### ProcessingQueues
[`iped/engine/core/ProcessingQueues.java`](src/main/java/iped/engine/core/ProcessingQueues.java). `TreeMap<Integer prioridade, FilaEnumPrioridades>` — filas indexadas por prioridade (subitens normalmente > itens de disco). Cada fila combina `LinkedList` (prioridade interna) com `ArrayList` (seleção aleatória para evitar contenção de recurso). Tamanho máximo configurável (auto-escalado pelo heap). Bloqueante com timeout.

### AbstractTask
[`iped/engine/task/AbstractTask.java`](src/main/java/iped/engine/task/AbstractTask.java). Base de toda tarefa do pipeline:
```java
public abstract class AbstractTask {
    protected Worker worker;
    protected Statistics stats;
    protected File output;
    protected ICaseData caseData;
    protected AbstractTask nextTask;

    public void init(ConfigurationManager cm) throws Exception { ... }
    public abstract void process(IItem evidence) throws Exception;
    public void finish() throws Exception { ... }
    public abstract List<Configurable<?>> getConfigurables();
    public boolean isEnabled() { ... }
}
```

### TaskInstaller
[`iped/engine/task/TaskInstaller.java`](src/main/java/iped/engine/task/TaskInstaller.java). Lê `conf/TaskInstaller.xml`, instancia tarefas via reflexão e encadeia (`task.nextTask = ...`). Suporta:
```xml
<tasks>
  <task class="iped.engine.task.SignatureTask"/>
  <task script="tasks/myTask.js"/>
  <task script="tasks/myTask.py"/>
</tasks>
```

## 4. Pipeline padrão (ordem importa)

| # | Task | O que faz |
|---|---|---|
| 1 | `SignatureTask` | Detecta MIME via Tika `Detector`, define `MediaType`. |
| 2 | `SetTypeTask` | Normaliza extensão a partir do MIME. |
| 3 | `IgnoreHardLinkTask` | Filtra hardlinks duplicados. |
| 4 | `TempFileTask` | Filtra arquivos temporários/system. |
| 5 | `SkipCommitedTask` | Pula itens já processados (modo `--continue`). |
| 6 | `HashTask` | MD5, SHA-1/256/512, Edonkey (paralelo). |
| 7 | `HashDBLookupTask` | Consulta NSRL/ProjectVic (marca "known"). |
| 8 | `PhotoDNATask` / `PhotoDNALookup` | PhotoDNA + consulta NCMEC. |
| 9 | `ParsingTask` | Extração de texto/metadata via Tika; expande containers. |
| 10 | `CarverTask` / `KnownMetCarveTask` / `LedCarveTask` | Recupera arquivos por assinatura. |
| 11 | `YaraScanTask` | Aplica regras YARA-X (via `libyara-x-capi` em `tools/yara-x/`) ao conteúdo binário. Roda **após carving** para ver subitems carved e **antes do `IndexTask`** para que `yara:tag` e `yara:match:<rule_id>` entrem no documento Lucene. Ver §22. |
| 12 | `SetCategoryTask` | Atribui categorias. |
| 13 | `LanguageDetectTask` | Detecção de idioma (>70). |
| 14 | `NamedEntityTask` | NER via Tika + Stanford NLP. |
| 15 | `RegexTask` | CPF/CNPJ/cartões/bitcoin/... (com validators plugáveis). |
| 16 | `ImageThumbTask` / `VideoThumbTask` / `DocThumbTask` | Geração de thumbnails. |
| 17 | `ImageSimilarityTask`, `DIETask`, `RemoteImageClassifierTask` | IA visual. |
| 18 | `MakePreviewTask` | Previews para repositório (MinIO/FS). |
| 19 | `AudioTranscriptTask` (Vosk/Whisper/Google/Microsoft) | Transcrição. |
| 20 | `JumpListTask`, `EmbeddedDiskProcessTask`, `DuplicateTask`, `EntropyTask`, `QRCodeTask` | Análises auxiliares. |
| 21 | **`IndexTask`** | Cria `Document` Lucene e adiciona ao `IndexWriter`. |
| 22 | `ElasticSearchIndexTask` | (opcional) indexa em paralelo no OpenSearch/ES. |
| 23 | `GraphTask` | Constrói grafo Neo4j. |
| 24 | `HTMLReportTask`, `ExportFileTask`, `ExportCSVTask` | Relatórios finais. |
| 25 | `ScriptTask`, `PythonTask` | Tarefas customizadas. |
| 26 | `MinIOTask` | Upload para storage. |
| 27 | `P2PBookmarker` | Bookmarks automáticos de P2P. |

A ordem real vive em `iped-app/resources/config/conf/TaskInstaller.xml` e nos profiles (`profiles/{forensic,pedo,triage,fastmode,blind}/`).

## 5. Sistema de Configuração

### `ConfigurationManager`
Singleton (`ConfigurationManager.get()`). Mapeia `Configurable<T>` → caminhos de arquivo, carregando lazy via `IConfigurationDirectory.lookUpResource(configurable)`.

### Hierarquia base
```
Configurable<T> (iped-api)
└─ AbstractPropertiesConfigurable        (parsing UTF-8 via UTF8Properties)
   └─ AbstractTaskPropertiesConfig
      └─ AbstractTaskConfig<T>           (base para configs de Tasks)
```

### Padrão por Task
```java
public class MyTaskConfig extends AbstractTaskConfig<String> {
    @Override public String getTaskEnableProperty() { return "MyTask.enabled"; }
    @Override public String getTaskConfigFileName() { return "MyTaskConfig.properties"; }
    @Override public void processTaskConfig(Path resource) { /* parse */ }
    @Override public boolean isEnabled() { return enabledProp.isEnabled(); }
}
```

### Configurações relevantes (~80 classes em `iped/engine/config/`)
- **Pipeline/Engine**: `Configuration`, `LocalConfig`, `AnalysisConfig`, `ProcessingPriorityConfig`, `TaskInstallerConfig`, `PluginConfig`.
- **I/O & Sleuthkit**: `FileSystemConfig` (`numberOfImageReaders`).
- **Tasks**: `HashTaskConfig`, `IndexTaskConfig`, `ParsingTaskConfig`, `SignatureConfig`, `CategoryConfig`, `CarverTaskConfig`, `OCRConfig`, `RegexConfigurable`, `AudioTranscriptConfig`, `ImageThumbTaskConfig`, `VideoThumbsConfig`, `DocThumbTaskConfig`, `MakePreviewConfig`, `HTMLReportTaskConfig`, `PhotoDNAConfig`, `FaceRecognitionConfig`, `AgeEstimationConfig`, `DIEConfig`, `CSAMDetectorConfig`, `MinIOConfig`, `ElasticSearchTaskConfig`, `HashDBLookupConfig`, `YaraConfig`.
- **UI**: `LocaleConfig`, `SplashScreenConfig`.
- **AI/Graph**: `AIFiltersConfig`, `RemoteImageClassifierConfig`, `NamedEntityRecognitionConfig`.

Arquivos físicos vivem em `iped-app/resources/config/conf/` e `profiles/{forensic,pedo,triage,fastmode,blind}/conf/`.

## 6. Modelo de Caso

| Classe | Responsabilidade |
|---|---|
| [`Item`](src/main/java/iped/engine/data/Item.java) | Implementa `IItem`. Metadados, hashes, categorias, bookmarks, atributos extras. Lazy I/O via `ISeekableInputStreamFactory`. |
| [`IPEDSource`](src/main/java/iped/engine/data/IPEDSource.java) | Implementa `IIPEDSource`. `IndexReader`, `IndexSearcher`, mapeia id ↔ lucene-doc, bookmarks, categoria. Diretório: `{caseDir}/iped/{index,data,lib}`. |
| `IPEDMultiSource` | Agregação multi-caso. |
| `CaseData` | Estado global mutável compartilhado entre tasks (`objectMap`), flags (`containsReport`, `ipedReport`), contadores (`discoveredVolume`, `discoveredEvidences`). Serializa para GZIP. |
| `Bookmarks` | `TreeMap<Integer, byte[]>` (bitset de itens), nomes, comentários, cores. |
| `MultiBookmarks` | Multi-caso. |
| `Category` | Árvore (parent/children/cor) com filtragem hierárquica. |
| `DataSource` | Caminho/nome/timezone da fonte. |

## 7. Data Sources

`iped/engine/datasource/`:
- `SleuthkitReader` — E01, DD/raw, VHD, VMDK, ISO 9660, Logical Evidence Container. Cria `SleuthkitCase` (SQLite), itera o filesystem (NTFS, FAT, ext, HFS+, UFS). Configura `SleuthkitInputStreamFactory` para lazy stream.
- `IPEDReader` — carrega outro caso IPED (index + thumbs + bookmarks).
- `FolderTreeReader` — pastas via `Files.walkFileTree` (NIO), respeita patterns de exclusão.
- `UfedXmlReader` — relatórios Cellebrite (XML).
- `AD1DataSourceReader` — AccessData AD1 (em `ad1/AD1Extractor.java`).
- `ItemProducer` — interface usada por counter/producer.

Imagens embutidas dentro de imagens (recursão) são abertas por `EmbeddedDiskProcessTask` + `SleuthkitReader`.

## 8. Sleuthkit out-of-process

**Por quê**: libtsk em JNI tem risco de leak/crash/freeze. Solução: `SleuthkitClient` mantém pool de subprocessos C++ (`SleuthkitServer`), comunicando via memory-mapped files/sockets. Se um server cair, é respawnado e o worker faz retry.

Componentes em `iped/engine/sleuthkit/`:
- `SleuthkitClient` — pool, timeout (3600s default), reconnect.
- `SleuthkitInputStreamFactory` — entrega `SeekableInputStream` sob demanda.
- `SleuthkitJNI` — binding nativo.
- `SleuthkitServer` é binário externo distribuído em `tools/`.

Config: `FileSystemConfig.getNumImageReaders()`.

## 9. Indexação Lucene

`iped/engine/task/index/IndexTask.java` e `IndexItem.java`:
- Converte `IItem` em `org.apache.lucene.document.Document`.
- Campos: `BasicProps` + metadados + extras. Conteúdo (full-text) opcional.
- Fragmenta documentos grandes (`FragmentingReader`) para evitar OOM.
- `IndexWriter` único, compartilhado entre workers.
- Commits a cada ~30 min (`commitIntervalMillis = 1800000`) + commit final.

`iped/engine/lucene/`:
- `AppAnalyzer` — analyzer por campo: `StandardASCIIAnalyzer` (texto), `KeywordAnalyzer` (hashes/IDs/datas).
- Configurável: lowercase, ASCII fold, filtro de caracteres não-latinos, tamanho máximo de token, caracteres extras para tokenização (importante para PT-BR).
- `CustomIndexDeletionPolicy` — preserva múltiplos commits para rollback em crash.

Busca: `iped/engine/search/`:
- `IPEDSearcher` — implementa `IIPEDSearcher`. Executa `Query` Lucene.
- `ItemSearcher` — implementa `IItemSearcher`. Retorna `List<IItemReader>`.
- `QueryBuilder` — string → AST Lucene.
- `LuceneSearchResult` — iterador com scores.

## 10. Web API (`iped/engine/webapi/`)

Servidor HTTP **Grizzly + Jersey/JAX-RS** + Swagger. Entry point: `Main.java`.

Endpoints típicos:
- `GET /` — root + Swagger UI.
- `GET /sources` — lista de casos.
- `GET /search?q=<query>&sourceID=<id>` — busca full-text.
- `GET /content/{sourceID}/{itemID}` — download de bytes.
- `GET /text/{sourceID}/{itemID}` — texto extraído.
- `GET /thumbnail/{sourceID}/{itemID}` — thumbnail.
- `POST /bookmarks/...` — gerenciar bookmarks/tags.

Config: `WebApiConfig` (ou similar) define porta e auth.

## 11. Hashes e bases

- Algoritmos suportados: **MD5, SHA-1, SHA-256, SHA-512, Edonkey** (definidos em `HashTask.HASH`).
- Computação multi-thread via `ExecutorService`.
- **HashDBLookupTask** consulta SQLite local com bases importadas (NSRL, ProjectVic, custom CSV). Marca itens como "known" para exclusão de análise.
- **PhotoDNATask** + **PhotoDNALookup** — APIs externas (NCMEC) para CSAM (requer credencial; jar adicional `br.dpf:photodna-api`).
- `HashDBTool` (CLI, em `iped/engine/hashdb/`) constrói/manipula a base.

## 12. Profiles e configs

Em `iped-app/resources/config/profiles/`:
- `forensic` — pipeline completo.
- `pedo` — foco em CSAM (PhotoDNA, DIE, faces).
- `triage` — mais rápido, foco em triagem inicial.
- `fastmode` — preview, sem indexação/parsing pesados.
- `blind` — extração automática de dados sem UI.

Cada profile sobrescreve seletivamente arquivos em `conf/`.

## 13. Scripting

**JavaScript (Nashorn)** — `iped-app/resources/scripts/tasks/*.js`. Funções esperadas: `getName()`, `getConfigurables()`, `init(cm)`, `process(item)`, `finish()`.

**Python (Jep)** — `iped-app/resources/scripts/tasks/*.py`. Classe com mesmos métodos; instância global no final. Requer libpython + libjep + `python-jep-dlib` (distribuído junto).

Bindings globais expostos: `caseData`, `worker`, `stats`, `output`, `moduleDir`.

Veja exemplos: `ExampleScriptTask.js`, `PythonScriptTask.py`, `AgeEstimationTask.py`, `FaceRecognitionTask.py`, `CSAMDetectorTask.py`, `Wav2Vec2Process.py`, `WhisperProcess.py`.

## 14. Dependências principais

| Lib | Versão | Para que |
|---|---|---|
| `org.apache.lucene:lucene-*` (core, analysis-common, backward-codecs, highlighter, queryparser, misc, join) | 9.2.0 | Indexação + busca |
| `org.apache.tika:tika-*` (core, parser-nlp-module, langdetect-optimaize) | 2.4.0(-p1) | Detecção + parsing |
| `org.sleuthkit:sleuthkit` | 4.12.0.p1 | Imagens forenses |
| `org.apache.pdfbox:pdfbox(+tools+xmpbox)`, `jbig2-imageio` | 2.0.27 / 3.0.4 | PDF |
| `org.xerial:sqlite-jdbc` | 3.41.2.2 | SQLite |
| `org.neo4j:neo4j-graphdb-api` + `org.neo4j.driver:neo4j-java-driver` | 5.26.0 | Graph (engine full Neo4j 5 isolado no módulo `iped-graph-server` → `lib/neo4j/`, acessado **out-of-process via Bolt**; o engine só carrega a API + o driver) |
| `org.glassfish.jersey.containers:jersey-container-grizzly2-servlet` + jersey-hk2 | 2.41 | Web API |
| `io.swagger:swagger-jersey2-jaxrs` | 1.6.10 | Swagger |
| `org.glassfish.jersey.media:jersey-media-json-jackson` | 2.41 | JSON |
| `io.minio:minio` | 8.3.8 | Object storage |
| `org.opensearch.client:opensearch-rest-high-level-client` | 2.1.0 | ES/OpenSearch |
| `com.alphacephei:vosk` | 0.3.32 | Speech offline |
| `com.microsoft.cognitiveservices.speech:client-sdk` | 1.19.0 (provided) | Azure speech |
| `com.google.cloud:google-cloud-speech` | 1.22.5 (provided) | Google speech |
| `org.openjdk.nashorn:nashorn-core` | 15.4 | JS scripting |
| `dk.brics.automaton:automaton` | 1.11-8 | Regex automata |
| `org.bouncycastle:bcpkix-jdk15on` | 1.70 | Crypto |
| `com.zaxxer:HikariCP` | 7.0.2 | Pool de conexões |
| `com.h2database:h2` | 2.3.232 | DB embarcado (cache) |
| `org.apache.commons:commons-compress` | 1.27.1 | ZIP, RAR, 7z, tar (#1068) |
| `com.googlecode.libphonenumber:libphonenumber` | 8.9.14 | Validação de telefone |
| `com.github.luben:zstd-jni` | 1.5.6-9 | Compressão Zstd |
| `com.mchange:c3p0`, `mchange-commons-java` | 0.9.5.5 / 0.2.20 | JDBC pool legado |
| `com.zaxxer:SparseBitSet` | 1.1 | Bitsets esparsos |
| `org.apache.httpcomponents:httpmime` | 4.5.13 | HTTP MIME |
| `com.github.oshi:oshi-core-java11` | 6.2.2 | Info de sistema |
| `com.google.zxing:core+javase` | 3.5.1 / 3.5.0 | QR code |
| `br.dpf:photodna-api` | 1.0 | PhotoDNA (restrito) |
| `iped:iped-ahocorasick` | 1.1 | Aho-Corasick |
| `iped:iped-parsers-impl`, `iped-carvers-{api,impl}`, `iped-viewers-{api,impl}`, `iped-utils`, `iped-api` | `${project.version}` | Internas |

## 15. Padrões de design

- **Producer/Consumer** — `ProcessingQueues` entre `ItemProducer` e `Worker`s.
- **Pipeline / Chain of Responsibility** — `AbstractTask` encadeado por `nextTask`.
- **Strategy** — Configurables determinam comportamento por arquivo.
- **Out-of-process** — `SleuthkitClient`/`SleuthkitServer`, `ParsingProcess` para isolamento de crash.
- **Lazy initialization** — `ConfigurationManager.loadConfig(configurable)` só lê arquivo quando necessário.
- **Per-worker state** — Tasks instanciadas N vezes (uma por worker) evitam sincronização.
- **Reflection + XML wiring** — `TaskInstaller.xml` define a cadeia; Spring-style sem Spring.

## 16. Como adicionar uma nova tarefa

```java
package com.example;

import iped.engine.config.AbstractTaskConfig;
import iped.engine.task.AbstractTask;
import iped.configuration.Configurable;
import iped.data.IItem;
import java.util.List;

public class MyTask extends AbstractTask {

    private MyTaskConfig config;

    @Override
    public List<Configurable<?>> getConfigurables() {
        return List.of(ConfigurationManager.get().findObject(MyTaskConfig.class));
    }

    @Override
    public void init(ConfigurationManager cm) throws Exception {
        config = cm.findObject(MyTaskConfig.class);
    }

    @Override
    public boolean isEnabled() { return config != null && config.isEnabled(); }

    @Override
    public void process(IItem evidence) throws Exception {
        if (evidence.isDir() || !isEnabled()) return;
        // ... seu processamento
        evidence.setExtraAttribute("my:result", value);
    }

    @Override
    public void finish() throws Exception { /* cleanup */ }
}
```

Config (opcional):
```java
public class MyTaskConfig extends AbstractTaskConfig<String> {
    @Override public String getTaskEnableProperty()  { return "MyTask.enabled"; }
    @Override public String getTaskConfigFileName() { return "MyTaskConfig.txt"; }
    @Override public void processTaskConfig(Path p) throws IOException { /* parse */ }
    @Override public boolean isEnabled() { return enabledProp.isEnabled(); }
}
```

Registro:
1. Edite `iped-app/resources/config/conf/TaskInstaller.xml` adicionando `<task class="com.example.MyTask"/>` na posição correta.
2. Edite `IPEDConfig.txt` para `MyTask.enabled = true`.
3. Coloque `MyTaskConfig.txt` em `conf/`.

## 17. Como adicionar um novo `DataSourceReader`

1. Estenda `DataSourceReader` em `iped/engine/datasource/`.
2. Implemente `read(File)` chamando `caseData.incDiscoveredEvidences(...)`, `produceItem(...)` etc.
3. Registre em `iped-engine` (busca por instâncias é feita por `Configuration.loadDataSourceReaders` — confira o código atual).

## 18. ⚠️ Áreas críticas

| Área | Cuidado |
|---|---|
| `Manager.startProcessing()` | Sequência de init/monitor/close — alterar pode quebrar workers. |
| `Worker.run()` | Item fetching + pipeline — race conditions podem comer/duplicar itens. |
| `ProcessingQueues` | Synchronization complexo. |
| `IndexWriter` | **Apenas Manager comanda commits**; workers só fazem `addDocument`. Não chame `commit()` manualmente. |
| `SleuthkitClient` pool | Mudanças aqui afetam estabilidade global. |
| `TaskInstaller` order | A ordem das tarefas implica dependências; mover algo arbitrariamente quebra cadeia. |
| Strings literais em `BasicProps`/`IndexItem` | São nomes de campo Lucene; renomear invalida casos existentes. |
| `AppAnalyzer` configs | Mudar fold/ASCII/lowercase invalida queries antigas. |
| `commitIntervalMillis` | Muito baixo derruba performance; muito alto aumenta perda em crash. |

## 19. Debugging

- **Logs** Log4j2: `iped.engine.core.Manager`, `iped.engine.core.Worker`, `iped.engine.task.*`, `iped.engine.sleuthkit.SleuthkitClient`, `iped.engine.search.IPEDSearcher`.
- **Estatísticas** em `Manager.stats` e via `caseData.getObjectMap()`.
- Sintomas comuns:
  - Task não executa → confira `isEnabled()` + `IPEDConfig.txt`.
  - Index não busca → verifique `IndexTask` ativo e commit final.
  - OOM → reduza fragmento de documento, `maxQueueSize`, threads.
  - Sleuthkit crash → veja `SleuthkitClient` log + número de servers + formato da imagem.
  - Script error → bindings em `init()` + sintaxe.

## 20. Convenções de nomenclatura

- Task: `*Task` (ex.: `HashTask`, `SignatureTask`).
- Config: `*Config` (ex.: `HashTaskConfig`).
- Propriedade enable: `&lt;TaskNameSemTask&gt;.enabled` (ex.: `HashTask.enabled`).
- Config file: `&lt;TaskNameSemTask&gt;Config.txt` ou `&lt;TaskNameSemTask&gt;Config.properties`.
- Campos UI: `ExtraProperties.*`.
- Campos Lucene: constantes em `IndexItem` (alguns mapeiam direto de `BasicProps`).

## 21. Bons hábitos

✅ Use `ConfigurationManager.get().findObject(MyConfig.class)` para acessar configs.  
✅ Adicione itens via `worker.processNewItem()` (não diretamente em filas).  
✅ Compartilhe estado entre tasks via `caseData.getObjectMap()` com cleanup.  
✅ Implemente `isEnabled()` corretamente — respeita config.  
✅ Tarefas que abrem stream devem fechar (try-with-resources).  
✅ Itens diretórios (`item.isDir()`) geralmente não devem ter conteúdo aberto.  

❌ Não instancie `IndexWriter` manualmente.  
❌ Não faça commit manual em Lucene.  
❌ Não chame `Sleuthkit*` direto — use `SleuthkitClient`.  
❌ Não armazene listas grandes em `caseData.objectMap` sem `finish()` que limpe.  
❌ Não assuma ordem de tasks — sempre marque dependências em `TaskInstaller.xml`.  
❌ Não bloqueie threads de worker em I/O lento sem timeout (regressão de throughput).

## 22. YARA Rules Engine (subpacote `iped.engine.task.yara`)

Feature adicionada na release 4.4.0 — ver `specs/001-yara-rules-engine/`.

### Componentes

| Classe | Responsabilidade |
|---|---|
| `YaraConfig` (em `iped.engine.config`) | `AbstractTaskPropertiesConfig` que lê `conf/YaraConfig.txt`: `ruleDirectories`, `maxFileSizeBytes`, `perItemTimeoutMs`, `scanAllItems`, `matchHexMaxBytes`, `engineLibraryHint`. Enable property: `enableYara` em `IPEDConfig.txt`. |
| `YaraEngine` | Bindings JNA finos para `libyara-x-capi` (YARA-X 1.16.0). API: `ensureAvailable/compileSources/createScanner(matchHexMaxBytes)/close`. Bindings incluem introspecção de regras (`yrx_rule_identifier`/`namespace`/`iter_tags`/`iter_patterns`) e de patterns/matches (`yrx_pattern_identifier`/`yrx_pattern_iter_matches`) + struct `YRX_MATCH{offset, length}`. Lib carregada via `Native.load("yara_x_capi", LibYaraX.class)` a partir de `tools/yara-x/<os>/`. |
| `YaraScanner` | Per-worker wrapper `AutoCloseable` em torno de `YRX_SCANNER*`. Instala callback uma vez na construção, reusa entre `scan()` calls. **Não é thread-safe** (cada worker tem o seu). No callback de rule: itera patterns (`yrx_rule_iter_patterns`) → matches (`yrx_pattern_iter_matches`), recorta os bytes do buffer Java mantido pelo `MatchCollector` durante o scan e codifica em hex lowercase (cap em `matchHexMaxBytes` do `YaraConfig`). Produz `MatchedString{id="$name", offset, hex, truncated}`. |
| `YaraRulesetLoader` | Descoberta recursiva determinística de `.yar`/`.yara` nos `ruleDirectories`. Pré-compilados (`.yarc`) fora de escopo na v1. |
| `YaraMatch` / `MatchedString` | POJOs imutáveis representando um match (namespace, name, tags, meta, matched-strings). Existem apenas em memória durante o scan — `YaraScanTask` consome via `YaraScanner.scan()` e denormaliza no formato `yara:match:<id>` antes do IndexTask. |
| `YaraScanTask` (em `iped.engine.task.yara`) | `AbstractTask` que: (a) shared-init via `synchronized` sobre `AtomicBoolean` (compila o catálogo uma vez), (b) per-worker `YaraScanner`, (c) `process(IItem)` aplica gate de elegibilidade (R-06), respeita `maxFileSizeBytes`/`perItemTimeoutMs`, persiste `ExtraProperties.YARA_TAGS` (união de tags cross-rule) e um campo `ExtraProperties.YARA_MATCH_PREFIX + namespace/name` por regra que casou, multi-valorado, com cada valor sendo um matched-string distinto decodificado (texto ASCII imprimível ou hex lowercase, via `YaraHighlightSupport.decodeHexForFacet`). Mirror do `Regex:CPF`/`Regex:EMAIL` faz a faceta do `MetadataPanel` funcionar idêntica à do `RegexTask` (drill-down + highlight automático no viewer de texto via o branch literal de `getHighlightTerms`). Os campos agregados `yara:rule` e `yara:matches` (JSON de auditoria) da v1 foram removidos na rev-5. |
| `YaraHighlightSupport` | Utilitário stateless. `decodeHexForFacet(hex)` converte o `hex` de uma `MatchedString` em texto ASCII imprimível trimmed (preferência) ou no hex lowercase original (fallback p/ binário) — consumido por `YaraScanTask.persistMatches()` para gerar os valores dos campos `yara:match:<rule_id>`. `decodePrintable(hex)` (package-private) é o caminho estrito que retorna `null` para qualquer byte fora de printable-ASCII + tab/LF/CR. Coberto por `YaraHighlightSupportTest` (13 testes). |
| _`--yara-only` rerun (via `SkipCommitedTask` + `IndexTask`)_ | Caminho de re-aplicação do catálogo sobre um caso já processado: passa pelo `Manager` normal (FR-011 redesenhado). `SkipCommitedTask` em modo `yara-only` marca itens commitados com `IS_COMMITTED=true` mas **NÃO** chama `setToIgnore(true)` — os itens seguem o pipeline. `IndexTask`, ao final, detecta `isAlreadyCommited && cmdArgs.isYaraOnly()` e usa `worker.writer.updateDocuments(new Term(IndexItem.TRACK_ID, Util.getTrackID(item)), docs)` em vez de `addDocuments`. A classe standalone `YaraRerunRunner` da v1 foi removida porque o ciclo `Document → IItem → Document` não é round-trip-safe (NPE em `setName` de docs-fragmento, conflito `SORTED` vs `SORTED_SET` em metadados multi-valor). |

### Ciclo de vida da engine nativa

1. **Process-wide bootstrap**: `YaraEngine.ensureAvailable(hint)` chama `Native.load(...)` na primeira invocação. Idempotente, sincronizado.
2. **Por catálogo (shared init)**: `YaraScanTask.init()` em `synchronized(initialized)` chama `YaraRulesetLoader.discover` + `YaraEngine.compileSources` UMA vez. O `YRX_RULES*` resultante é compartilhado read-only entre workers.
3. **Por worker**: cada instância de `YaraScanTask` cria seu próprio `YaraScanner` via `engine.createScanner()`.
4. **Por item**: `scanner.scan(buffer, len, timeout)` no hot path. O scanner zera a lista de matches antes do scan e reusa o `YRX_SCANNER*`.
5. **Shutdown**: `YaraScanTask.finish()` destrói o scanner per-worker; o último worker chama `engine.close()` (libera `YRX_RULES*`).

### Hooks de configuração

- `enableYara = true|false` em `IPEDConfig.txt` ou em profiles (`forensic`/`pedo` default `true`; `triage`/`fastmode`/`blind` default `false`).
- `conf/YaraConfig.txt` (canônico) + `profiles/<X>/conf/YaraConfig.txt` (overrides).
- Bin nativo em `tools/yara-x/{win64,linux64}/`. Linux ainda precisa de build from source (`cargo build -p yara-x-capi --release` — ver `tools/yara-x/README.md`).
- Faceta UI: `ColumnsManager.updateDinamicFields()` agrupa campos com prefixo `yara:` sob a label `ColumnsManager.Yara`.

### `--yara-only` (rerun)

Modo CLI para refrescar `yara:*` num caso já processado, mantendo os demais campos do índice consistentes.

```text
iped --yara-only -d <DATASOURCE> -o <CASE_OUTPUT_DIR>
```

- **Requer `-d` e `-o`**: o datasource original é necessário porque a `YaraScanTask` escaneia bytes; sem ele, não há fluxo de conteúdo.
- Implica `--continue` automaticamente (`CmdLineArgsImpl.isContinue()` retorna `true` quando `yaraOnly`).
- Pipeline completo roda também para itens commitados — alteração mínima em duas tasks:
  - `SkipCommitedTask.process(IItem)`: em modo `yara-only`, marca `IS_COMMITTED=true` mas pula `setToIgnore(true)`.
  - `IndexTask.process(IItem)`: detecta `isAlreadyCommited && cmdArgs.isYaraOnly()` e usa `updateDocuments(new Term(TRACK_ID, ...), docs)` no lugar de `addDocuments(...)`.
- Combinações rejeitadas: `--append`, `--restart`, `-remove`, `--continue` explícito.
- Pre-check: `Main.startManager()` falha rápido se `enableYara=false` (evitaria `updateDocuments` apagar `yara:*` sem reescrever).
- Histórico: a v1 standalone (`YaraRerunRunner`) foi removida — o round-trip `Document → IItem → Document` quebrou em produção (NPE/schema conflict).

> ⚠️ **Feche a UI do `iped`/`IPED-SearchApp.exe` apontando para o mesmo caso antes de rodar `--yara-only`.** A UI mantém conexões SHARED de leitura nos `<caso>/iped/storage/storage-*.db` (SQLite); o commit final do `ExportFileTask` precisa de lock EXCLUSIVE e fica em busy-wait nativo (`NativeDB.step` RUNNABLE) indefinidamente até a UI fechar. Sintoma: ProgressFrame mostra "Todos os Workers ociosos"; `jcmd <pid> Thread.print` mostra Worker-0 em `ExportFileTask.finish:888`. Solução: fechar a janela do SearchApp libera os locks e o commit conclui em segundos. Pre-check automático fica como melhoria futura ([feedback memory entry](../../../C:/Users/joaopaulo_jpva/.claude/projects/h--java-workspaces-workspace-iped-IPED/memory/feedback_iped_yara_only_appmain_lock.md)).

### Testes (`iped-engine/src/test/.../yara/` + `iped-engine/src/test/.../config/YaraConfigTest.java`)

| Classe | Cobertura |
|---|---|
| `YaraConfigTest` | 19 testes (parsing K/M/G suffixes, validation, defaults). |
| `YaraRulesetLoaderTest` | 9 testes (discovery recursiva, case-insensitive, ignora .yarc, etc.). |
| `YaraEngineTest` | 5 testes integration-gated (`assumeTrue` em `libyara-x-capi`). |
| `YaraScanTaskIntegrationTest` | 7 testes integration-gated end-to-end do task. Cobre: matched-item → yara:tag + per-rule field; non-matching item → zero campos yara:*; size cap; isolation de regras inválidas; múltiplas regras casando (uma per-rule field cada); per-rule values decoded ASCII; disabled config no-op. |
| `YaraHighlightSupportTest` | 13 testes (`decodeHexForFacet` retorna texto se imprimível ou hex como fallback; `decodePrintable` strict path; edge cases de trim/whitespace/odd-length). |
| _(removidos na rev-5)_ | `YaraMatchSerializerTest` (10 testes) e `YaraReportRendererTest` (10 testes) foram deletados junto com `YaraMatchSerializer` e `YaraReportRenderer` — JSON `yara:matches` e o bloco estruturado do HTML report não existem mais. |
| _(removido na rev-2)_ | A v1 `YaraRerunRunnerTest` foi deletada junto com o `YaraRerunRunner`; o caminho atual `--yara-only` (via Manager + `SkipCommitedTask` + `IndexTask`) é validado por execução manual ([specs/001-yara-rules-engine/quickstart.md §6](../specs/001-yara-rules-engine/quickstart.md)). |

Para rodar a suite YARA contra a libyara-x-capi real:

```powershell
$env:YARA_X_LIB_PATH = "$PWD\tools\yara-x\win64\yara_x_capi.dll"
mvn -pl iped-engine -Dtest='Yara*' test
```

Sem a engine nativa, os testes `YaraEngineTest`/`YaraScanTaskIntegrationTest` skipam via `Assume`; os demais (sem dependência nativa) sempre rodam.

## 23. Checklist de PR

- [ ] Nova task tem `Config` correspondente (mesmo que minimal).
- [ ] `IPEDConfig.txt` documenta a nova flag.
- [ ] `TaskInstaller.xml` foi atualizado na posição correta.
- [ ] `isEnabled()` é respeitado em `process`.
- [ ] Logging via SLF4J.
- [ ] Não modificou `Manager`, `Worker`, `ProcessingQueues` sem teste de regressão.
- [ ] Não renomeou strings que viram chave de campo Lucene.
- [ ] Não introduziu dependência nativa sem documentação em `tools/`.
- [ ] Adicionou teste JUnit em `iped-engine/src/test/java/...` se for caminho importante.
