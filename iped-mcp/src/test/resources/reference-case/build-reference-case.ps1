<#
.SYNOPSIS
    Lays out the source material for the small reference case, deterministically.

.DESCRIPTION
    Content is fixed by this script so the answer key in ../evaluation/questions.md keeps
    describing the case after a rebuild. Nothing here is real personal data and nothing is
    illicit: the case is meant to be shared, copied between workstations and attached to bug
    reports.

.PARAMETER WorkDir
    Folder to build under. Source material lands in <WorkDir>\source.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$WorkDir
)

$ErrorActionPreference = 'Stop'

$src = Join-Path $WorkDir 'source'
$ev1 = Join-Path $src 'evidence-one'
$ev2 = Join-Path $src 'evidence-two'

if (Test-Path $src) { Remove-Item -Recurse -Force $src }
foreach ($dir in @(
        (Join-Path $ev1 'documentos\contratos'),
        (Join-Path $ev1 'fotos'),
        (Join-Path $ev1 'emails'),
        (Join-Path $ev2 'mensagens'),
        (Join-Path $ev2 'diversos\nivel-1\nivel-2\nivel-3'))) {
    New-Item -ItemType Directory -Force $dir | Out-Null
}

# --- Documents with the known vocabulary (Q01, Q21-Q25) --------------------------------
Set-Content -Encoding UTF8 (Join-Path $ev1 'documentos\contratos\contrato-servicos.txt') @'
CONTRATO DE PRESTACAO DE SERVICOS

As partes acordam o pagamento mediante transferência bancária mensal.
O contrato vigora por doze meses a partir da assinatura.
Valor: R$ 12.000,00 por mês, pago por transferência.
'@

Set-Content -Encoding UTF8 (Join-Path $ev1 'documentos\contratos\minuta-contrato.txt') @'
MINUTA - RASCUNHO

Este contrato ainda está em rascunho e não foi assinado.
Discussão pendente sobre a forma de pagamento.
'@

Set-Content -Encoding UTF8 (Join-Path $ev1 'documentos\relatorio-anual.txt') @'
RELATORIO ANUAL

Resumo das transferências realizadas no exercício.
Nenhuma irregularidade identificada no período.
'@

# --- Personal data, synthetic (Q18, Q30) -----------------------------------------------
# The CPF is a well-known invalid test value; the address is the public genesis-block
# bitcoin address. Neither identifies a person.
Set-Content -Encoding UTF8 (Join-Path $ev1 'documentos\dados-pessoais.txt') @'
Ficha de cadastro (dados fictícios para teste)

CPF: 123.456.789-09
Carteira: 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa
'@

# --- A file with a fixed, documented hash (Q08) ----------------------------------------
# Empty file: MD5 d41d8cd98f00b204e9800998ecf8427e, which the answer key names.
New-Item -ItemType File -Force (Join-Path $ev1 'documentos\vazio.dat') | Out-Null

# --- Large files, above 100 KB (Q15) ---------------------------------------------------
[System.IO.File]::WriteAllText((Join-Path $ev1 'documentos\grande-a.txt'), ('A' * 150000))
[System.IO.File]::WriteAllText((Join-Path $ev2 'diversos\grande-b.txt'), ('B' * 250000))

# --- Emails and an attachment (Q09-Q11) ------------------------------------------------
Set-Content -Encoding UTF8 (Join-Path $ev1 'emails\mensagem-01.eml') @'
From: ana.silva@exemplo.test
To: bruno.costa@exemplo.test
Subject: Contrato de servicos
Date: Wed, 13 Mar 2024 10:15:00 -0300
Content-Type: text/plain; charset=UTF-8

Bruno, segue o contrato para revisão. O pagamento sai por transferência.
Ana
'@

Set-Content -Encoding UTF8 (Join-Path $ev1 'emails\mensagem-02.eml') @'
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
'@

# --- Chat-like messages (Q12) ----------------------------------------------------------
Set-Content -Encoding UTF8 (Join-Path $ev2 'mensagens\conversa.txt') @'
[2024-03-13 10:20] ana: você recebeu o contrato?
[2024-03-13 10:22] bruno: recebi, vou revisar o pagamento
[2024-03-13 10:25] ana: ok, a transferência sai amanhã
'@

# --- Archive with documents inside (Q11, Q20) ------------------------------------------
$stage = Join-Path $WorkDir '.zipstage'
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $ev1 'documentos\relatorio-anual.txt') $stage
Copy-Item (Join-Path $ev1 'documentos\contratos\contrato-servicos.txt') $stage
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath (Join-Path $ev2 'diversos\documentos.zip') -Force
Remove-Item -Recurse -Force $stage

# --- Nested directories (Q17, Q26) -----------------------------------------------------
Set-Content -Encoding UTF8 (Join-Path $ev2 'diversos\nivel-1\nivel-2\nivel-3\fundo.txt') 'arquivo no fundo da arvore'

# --- Fixed timestamps spanning several years (Q05, Q19) --------------------------------
$stamps = @{
    'documentos\relatorio-anual.txt'             = '2021-06-01 08:00:00'
    'documentos\contratos\contrato-servicos.txt' = '2024-03-13 10:15:00'
    'documentos\contratos\minuta-contrato.txt'   = '2024-03-20 17:30:00'
    'emails\mensagem-02.eml'                     = '2024-03-14 09:00:00'
}
foreach ($relative in $stamps.Keys) {
    $file = Get-Item (Join-Path $ev1 $relative)
    $file.LastWriteTime = [datetime]::Parse($stamps[$relative])
    $file.CreationTime = [datetime]::Parse($stamps[$relative])
}

@"

Source material written to: $src

Still to do by hand, because they cannot be produced from a plain folder:

  * Photographs with and without EXIF GPS (Q04). Add at least two geotagged JPEGs and one
    without coordinates under $ev1\fotos.

  * A deleted file (Q06) and a carved-only file (Q07). These need a filesystem image rather
    than a folder: create a small FAT or NTFS image, copy a file in, delete it, and use that
    image as the evidence instead of $ev2.

Then process:

  java -jar <IPED>\iped.jar -d $src -o $WorkDir\case -profile forensic

And run the suites:

  mvn -pl iped-mcp test "-Diped.mcp.test.referenceCase=$WorkDir\case"
"@ | Write-Output
