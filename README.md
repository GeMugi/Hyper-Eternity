# Ramo `relogios`

Publica a extensão **Hyper Eternity Relógios** na release fixa `relogios`, sem criar uma release nova a cada versão.

1. Coloque o APK assinado em `dist/` com o nome `Hyper-Eternity-Relogios-<versão>.apk` (pode apagar o anterior).
2. Faça o commit neste ramo. O workflow *Publicar extensão Relógios* troca o APK dentro da release `relogios`.

O app do Hyper Eternity (botão de instalar/atualizar a extensão) procura nesta release primeiro e, depois, nas últimas releases.

## Testes

APKs de teste vão em `dist-teste/` com o nome `Hyper-Eternity-Clocks-Teste-<n>.apk` (sem "Relogios" no nome, para o app não oferecer o teste a todo mundo) e são publicados na pré-release `relogios-teste`. O app do Hyper Eternity **não** oferece essa versão; os testadores baixam o APK direto da release. A assinatura é de teste: é preciso desinstalar a versão publicada antes.
