# `tools/yara-x/` — native YARA-X engine for IPED

This directory ships the **YARA-X 1.x** runtime (`libyara-x-capi`) that the
`YaraScanTask` consumes via JNA. YARA-X is the official Rust rewrite of YARA by
Victor M. Alvarez and is the successor of the classic libyara — IPED migrated
to YARA-X on 2026-05-19 (see `specs/001-yara-rules-engine/research.md` §R-01
for the rationale).

## Layout

```
tools/yara-x/
├── README.md              (this file)
├── LICENSE                (BSD 3-clause from upstream YARA-X — placeholder until binaries land)
├── win64/
│   └── yara_x_capi.dll        (placeholder — upstream prebuilt binary)
└── linux64/
    └── libyara_x_capi.so      (placeholder — upstream prebuilt binary)
```

The Java side loads the library via `Native.load("yara_x_capi", LibYaraX.class)`
(JNA) after `Bootstrap` adds the platform-specific subdirectory to
`jna.library.path`. When the binary is missing, the `YaraScanTask` logs a single
warning and disables itself for the case — the rest of IPED continues to work
normally.

## Versão pinned

- **YARA-X 1.16.0** (release oficial; congelada por release do IPED — atualize
  esta versão, a constante `YaraEngine.ENGINE_VERSION`, o `YARAX_VERSION` no
  workflow de CI (`.github/workflows/maven.yml`) e o SHA-256 dos binários
  sempre que trocar).
- Módulos `pe`, `elf`, `math`, `hash`, `magic`, `dotnet`, `time` vêm habilitados
  no release oficial. O módulo `cuckoo` é **banido em runtime** pelo
  `YaraEngine` via `yrx_compiler_ban_module(...)`, então rules com
  `import "cuckoo"` falham na compilação com mensagem clara.

## Como atualizar a versão do `libyara-x-capi`

Diferente do YARA clássico, o upstream do YARA-X **publica binários
self-contained pré-compilados** para Windows e Linux — sem build manual.

1. **Identifique a versão alvo** em https://github.com/VirusTotal/yara-x/releases.
   Procure os assets que começam com `libyara-x-capi-vX.Y.Z-...`.

2. **Linux (x86_64)** — baixe e extraia:
   ```bash
   YARAX_VERSION="1.16.0"  # ajuste para a versão alvo
   curl -L -o yara-x-capi-linux.tar.gz \
     https://github.com/VirusTotal/yara-x/releases/download/v${YARAX_VERSION}/libyara-x-capi-v${YARAX_VERSION}-x86_64-unknown-linux-gnu.tar.gz
   tar -xzf yara-x-capi-linux.tar.gz
   # O tarball contém um diretório com lib/, include/, etc.
   cp <extracted>/lib/libyara_x_capi.so path/to/IPED/tools/yara-x/linux64/
   ```

3. **Windows (x64)** — baixe e extraia:
   ```powershell
   $YARAX_VERSION = "1.16.0"  # ajuste para a versão alvo
   Invoke-WebRequest `
     -Uri "https://github.com/VirusTotal/yara-x/releases/download/v$YARAX_VERSION/libyara-x-capi-v$YARAX_VERSION-x86_64-pc-windows-msvc.zip" `
     -OutFile yara-x-capi-windows.zip
   Expand-Archive yara-x-capi-windows.zip -DestinationPath yara-x-capi-windows
   # O zip contém lib/, include/, etc.
   Copy-Item yara-x-capi-windows\lib\yara_x_capi.dll path\to\IPED\tools\yara-x\win64\
   ```

4. **Gerar SHA-256 dos novos binários** e registrar em `ReleaseNotes.txt` na
   entrada da versão correspondente do IPED:
   ```bash
   sha256sum tools/yara-x/linux64/libyara_x_capi.so
   sha256sum tools/yara-x/win64/yara_x_capi.dll
   ```

5. **Atualizar `licenses/YARA-X.txt`** se o arquivo `LICENSE` do upstream tiver
   mudado entre versões (raro — é BSD 3-clause estável).

## Verificação rápida

A partir do release construído (`target/release/iped-<version>/`):

```bash
# Linux
ldd tools/yara-x/linux64/libyara_x_capi.so
# Confirmar que não há dependências quebradas (UNRESOLVED). Como o YARA-X
# linka estaticamente OpenSSL e dependências Rust, o output deve ser
# essencialmente libc/libpthread/libdl.

# Windows (PowerShell + Dependencies.exe ou similar)
# Listar dependências dinâmicas e confirmar que são apenas system DLLs
# (kernel32, ucrtbase, etc.).
```

## Por que YARA-X e não libyara clássica?

Resumo (detalhe completo em `specs/001-yara-rules-engine/research.md` §R-01):

- O upstream do YARA clássico entrou em modo manutenção; novas features migraram
  para YARA-X.
- O YARA clássico **não publica `libyara.dll` pré-compilada** para Windows; só
  os executáveis estáticos `yara64.exe`/`yarac64.exe`. Forçaria o IPED a manter
  um build próprio da DLL. YARA-X resolve isso publicando os artefatos
  `libyara-x-capi-vX.Y.Z-*-msvc.zip`.
- Linguagem de regras ~99% retrocompatível; flag `YRX_RELAXED_RE_SYNTAX` cobre
  o gap residual de regex.
- C API (`yrx_*`) é mais limpa e expõe mais informação ao chamador (iteradores
  separados para patterns/matches/metadata), o que simplificará a extração de
  match detail nas próximas iterações.
