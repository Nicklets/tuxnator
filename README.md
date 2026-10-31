# Tuxnator

O Tuxnator roda programas e jogos **Linux** no Android: Debian arm64 com PRoot, Box64 para programas x86_64 e
Mesa Turnip/Zink para Vulkan e OpenGL em GPUs Adreno. Ele nasceu de um fork do
[Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) e continua rodando programas Windows com
Wine como o Winlator.

> **Beta.** Funciona no aparelho em que foi desenvolvido (Snapdragon 8 Gen 1). Em outros aparelhos ainda é
> pouco testado: relatos em [Issues](https://github.com/Nicklets/tuxnator/issues) ajudam muito.

## Requisitos

- Android 8 ou mais novo, arm64
- GPU Adreno (Snapdragon) para os drivers Turnip. Outras GPUs só têm o driver do sistema, mais lento.
- Cerca de 1,2 GB livres para o sistema Debian, além do espaço dos jogos

## Instalação

1. Baixe o `Tuxnator-<versão>.apk` em [Releases](https://github.com/Nicklets/tuxnator/releases) e instale.
   Ele não substitui o Winlator: os dois podem ficar instalados juntos.
2. Abra o app e espere a preparação inicial terminar.
3. Em **Configurações > Contêiner Linux**, toque em **Instalar**. O app baixa o sistema Debian (cerca de
   200 MB), confere o hash SHA-256 e instala.

## Uso

- **Área de trabalho Linux** (Configurações > Contêiner Linux): gerenciador de arquivos, barra de tarefas e
  terminal. Dali dá para abrir AppImages e programas Linux com dois toques.
- **Pastas compartilhadas**: o Linux só acessa os arquivos do próprio app e as pastas que você escolher nessa
  mesma tela. Elas aparecem em `Android` na área de trabalho.
- **Atalhos**: um atalho `.desktop` cujo `Exec=` aponta para um programa Linux roda no modo Linux, com os
  controles virtuais e perfis de teclado do Winlator.
- Renderizador (Vulkan ou OpenGL), driver Turnip e precisão do Box64 ficam na mesma tela.

Já testados: SuperTuxKart (jogável com toque, controle virtual e som), apps GTK3/GTK4 e AppImages x86_64.

## Limitações conhecidas

- O servidor X é o do Winlator, em Java. Ainda faltam as extensões RENDER, SHAPE e XKB: programas que exigem
  alguma delas podem não abrir.
- Atualizar o sistema Debian troca ele inteiro: pacotes instalados com `apt` se perdem. A pasta pessoal fica.
- Jogos x86_64 pesados esquentam o aparelho; jogue com o celular carregado.

## Compilar

- APK: `./gradlew assembleRelease` (veja [.github/workflows/build.yml](.github/workflows/build.yml) para as
  dependências nativas). Releases são assinadas com a chave do projeto, guardada nos secrets do GitHub.
- Sistema Debian: [.github/workflows/linux-rootfs.yml](.github/workflows/linux-rootfs.yml). As versões de
  Debian, Box64 e Mesa ficam em [tools/linux-rootfs/config.env](tools/linux-rootfs/config.env).

## Código-fonte de terceiros

- **Debian**: cada release `linux-rootfs-N` traz a lista de pacotes e o código-fonte Debian correspondente
  (`linux-rootfs-N-sources.tar.part-*`; junte com `cat linux-rootfs-N-sources.tar.part-* | tar -x`).
- **Box64 e Mesa**: o `manifest.json` de cada release `linux-rootfs-N` traz o commit usado. Os patches estão em
  [tools/linux-rootfs/build-components.sh](tools/linux-rootfs/build-components.sh).
- **PRoot**: compilado de [app/src/main/cpp/proot](app/src/main/cpp/proot).

## Créditos

- [Winlator](https://github.com/brunodev85/winlator) de brunodev85 e
  [Winlator Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) de StevenMX e pipetto-crypto
- [Box64](https://github.com/ptitSeb/box64) de ptitSeb
- [Mesa](https://www.mesa3d.org) (Turnip e Zink)
- [PRoot](https://proot-me.github.io)
- [Debian](https://www.debian.org)
- Wine, DXVK, VKD3D, D8VK, CNC DDraw, dxwrapper, FEX-Emu e libadrenotools, usados no modo Windows

Licença MIT (veja [LICENSE](LICENSE)). Os componentes de terceiros seguem as próprias licenças.
