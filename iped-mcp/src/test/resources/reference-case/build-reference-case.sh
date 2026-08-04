#!/usr/bin/env bash
# Lays out the source material for the small reference case, deterministically.
#
# Content is fixed by this script so the answer key in ../evaluation/questions.md keeps describing
# the case after a rebuild. Nothing here is real personal data and nothing is illicit: the case is
# meant to be shared, copied between workstations and attached to bug reports.
#
# Usage: ./build-reference-case.sh <workdir>

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <workdir>" >&2
    exit 1
fi

WORKDIR="$1"
SRC="$WORKDIR/source"
EV1="$SRC/evidence-one"
EV2="$SRC/evidence-two"

rm -rf "$SRC"
mkdir -p "$EV1/documentos/contratos" "$EV1/fotos" "$EV1/emails" "$EV2/mensagens" "$EV2/diversos"

# --- Documents with the known vocabulary (Q01, Q21-Q25) --------------------------------
cat > "$EV1/documentos/contratos/contrato-servicos.txt" <<'EOF'
CONTRATO DE PRESTACAO DE SERVICOS

As partes acordam o pagamento mediante transferência bancária mensal.
O contrato vigora por doze meses a partir da assinatura.
Valor: R$ 12.000,00 por mês, pago por transferência.
EOF

cat > "$EV1/documentos/contratos/minuta-contrato.txt" <<'EOF'
MINUTA - RASCUNHO

Este contrato ainda está em rascunho e não foi assinado.
Discussão pendente sobre a forma de pagamento.
EOF

cat > "$EV1/documentos/relatorio-anual.txt" <<'EOF'
RELATORIO ANUAL

Resumo das transferências realizadas no exercício.
Nenhuma irregularidade identificada no período.
EOF

# --- Personal data, synthetic (Q18, Q30) -----------------------------------------------
# The CPF below is a well-known invalid/test value and the bitcoin address is the public
# genesis-block address. Neither identifies a person.
cat > "$EV1/documentos/dados-pessoais.txt" <<'EOF'
Ficha de cadastro (dados fictícios para teste)

CPF: 123.456.789-09
Carteira: 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa
EOF

# --- A file with a fixed, documented hash (Q08) ----------------------------------------
# Empty file: MD5 d41d8cd98f00b204e9800998ecf8427e, which the answer key names.
: > "$EV1/diversos-vazio.dat" 2>/dev/null || : > "$EV1/documentos/vazio.dat"

# --- Large files, above 100 KB (Q15) ---------------------------------------------------
head -c 150000 /dev/zero | tr '\0' 'A' > "$EV1/documentos/grande-a.txt"
head -c 250000 /dev/zero | tr '\0' 'B' > "$EV2/diversos/grande-b.txt"

# --- Emails and an attachment (Q09-Q11) ------------------------------------------------
cat > "$EV1/emails/mensagem-01.eml" <<'EOF'
From: ana.silva@exemplo.test
To: bruno.costa@exemplo.test
Subject: Contrato de servicos
Date: Wed, 13 Mar 2024 10:15:00 -0300
Content-Type: text/plain; charset=UTF-8

Bruno, segue o contrato para revisão. O pagamento sai por transferência.
Ana
EOF

cat > "$EV1/emails/mensagem-02.eml" <<'EOF'
From: ana.silva@exemplo.test
To: carla.dias@exemplo.test
Subject: Transferencia confirmada
Date: Thu, 14 Mar 2024 09:00:00 -0300
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="sep"

--sep
Content-Type: text/plain; charset=UTF-8

Carla, a transferência bancária foi confirmada. Comprovante em anexo.
--sep
Content-Type: text/plain; charset=UTF-8
Content-Disposition: attachment; filename="comprovante.txt"

Comprovante de pagamento numero 998877.
--sep--
EOF

# --- Chat-like messages (Q12) ----------------------------------------------------------
cat > "$EV2/mensagens/conversa.txt" <<'EOF'
[2024-03-13 10:20] ana: você recebeu o contrato?
[2024-03-13 10:22] bruno: recebi, vou revisar o pagamento
[2024-03-13 10:25] ana: ok, a transferência sai amanhã
EOF

# --- Archive with documents inside (Q11, Q20) ------------------------------------------
mkdir -p "$WORKDIR/.zipstage"
cp "$EV1/documentos/relatorio-anual.txt" "$WORKDIR/.zipstage/"
cp "$EV1/documentos/contratos/contrato-servicos.txt" "$WORKDIR/.zipstage/"
(cd "$WORKDIR/.zipstage" && zip -q -X "$EV2/diversos/documentos.zip" ./*)
rm -rf "$WORKDIR/.zipstage"

# --- Nested directories (Q17, Q26) -----------------------------------------------------
mkdir -p "$EV2/diversos/nivel-1/nivel-2/nivel-3"
echo "arquivo no fundo da arvore" > "$EV2/diversos/nivel-1/nivel-2/nivel-3/fundo.txt"

# --- Fixed timestamps spanning several years (Q05, Q19) --------------------------------
touch -d "2021-06-01 08:00:00" "$EV1/documentos/relatorio-anual.txt"
touch -d "2024-03-13 10:15:00" "$EV1/documentos/contratos/contrato-servicos.txt"
touch -d "2024-03-20 17:30:00" "$EV1/documentos/contratos/minuta-contrato.txt"
touch -d "2024-03-14 09:00:00" "$EV1/emails/mensagem-02.eml"

cat <<EOF

Source material written to: $SRC

Still to do by hand, because they cannot be produced from a plain folder:

  * Photographs with and without EXIF GPS (Q04). Add at least two geotagged JPEGs and one
    without coordinates under $EV1/fotos. Use photographs you took yourself, or a public
    domain image with coordinates written using exiftool.

  * A deleted file (Q06) and a carved-only file (Q07). These need a filesystem image rather
    than a folder: create a small FAT or NTFS image, copy a file in, delete it, and use that
    image as the evidence instead of $EV2.

Then process:

  java -jar <IPED>/iped.jar -d $SRC -o $WORKDIR/case -profile forensic

And run the suites:

  mvn -pl iped-mcp test -Diped.mcp.test.referenceCase=$WORKDIR/case
EOF
