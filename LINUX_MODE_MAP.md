# Mapa: transformar o Winlator Ludashi em um "Linuxlator"

Análise do código do Winlator Ludashi 4.1 (`com.winlator.cmod`, ~65 mil linhas Java/Kotlin).
Caminhos relativos a `app/src/main/`.

---

## 0. Resumo executivo

| Área | Situação para apps Linux | Esforço |
|---|---|---|
| Execução / CPU (Box64) | **Já existe** o caminho `box64 <binário>` (modo não-ARM64EC) | Baixo |
| FEX | **Não serve**: só existe como DLL do Wine (`libwow64fex.dll`) | Alto (adiar) |
| Rootfs (imagefs) | Prefixo estilo Termux, sem `/lib`, `/usr` reais. Apps de distro não rodam direto | **Médio/Alto — decisão chave** |
| Servidor X11 | Implementação Java **mínima**, feita sob medida para o Wine. Serve para jogos SDL2/GLFW, **não** para GTK/Qt/Electron | **Alto — decisão chave** |
| Window manager | Não existe; quem fazia isso era o `wine explorer /desktop` | Médio |
| GPU (Turnip/Zink/adrenotools) | Reaproveitável | Baixo |
| Áudio (PulseAudio/ALSA) | Reaproveitável como está | Baixo |
| Controles na tela / gamepad | `libfakeinput.so` já emula evdev Linux → ótimo | Baixo |
| UI / containers / atalhos | Muito acoplada ao Wine, precisa de limpeza | Médio |

**Recomendação de estratégia:** não apagar o Wine de cara. Adicione um **tipo de container**
(`runtime = windows | linux`) e faça o modo Linux funcionar primeiro. Só depois de estável, faça um
build "Linux-only" removendo o que está na seção 3. Assim você não quebra 60 mil linhas de uma vez.

---

## 1. Decisões de arquitetura (resolva antes de codar)

### 1.1 Rootfs: como os apps Linux vão achar suas bibliotecas

Hoje a imagefs é um **prefixo** (`/data/data/<pkg>/files/imagefs/usr/...`), com a glibc compilada
para esse caminho ([GuestProgramLauncherComponent.java:345-378](java/com/winlator/cmod/xenvironment/components/GuestProgramLauncherComponent.java:345)).
Não há `/lib/ld-linux-aarch64.so.1` nem `/usr/lib/x86_64-linux-gnu`. Um binário de Debian/Ubuntu
procura esses caminhos absolutos e falha.

| Opção | Como | Prós | Contras |
|---|---|---|---|
| **A. PRoot + rootfs Debian/Ubuntu arm64** (recomendado) | O código-fonte do PRoot **já está** em `cpp/proot/`, mas **não é compilado** (falta `add_subdirectory(proot)` em `cpp/CMakeLists.txt`). Rootfs Debian arm64 + multiarch `amd64` para as libs x86_64 do Box64 | `apt install` funciona, compatibilidade máxima, `.desktop` e ícones padrão | Overhead de syscalls (ptrace), ~10-20% em apps pesados de I/O |
| B. patchelf no interpretador | `core/PatchElf.java` + `cpp/patchelf` já existem | Sem overhead | Só funciona para binários isolados, quebra com pacotes de distro e caminhos hardcoded |
| C. Rootfs próprio estilo imagefs | Compilar tudo para o prefixo | Desempenho | Você vira mantenedor de uma distro inteira |

> **GPU no rootfs Debian:** o Mesa do Debian **não** tem o backend KGSL (o driver Adreno do Android).
> Você precisa empacotar o Turnip do Winlator (`assets/graphics_driver/adrenotools-turnip*.tzst`) e
> apontar `VK_ICD_FILENAMES` para ele, em vez de usar o Mesa da distro.

### 1.2 Servidor X11: manter o Java ou trocar

O servidor em `java/.../xserver/` implementa **58 opcodes do core** e estas extensões
([XServer.java:239-248](java/com/winlator/cmod/xserver/XServer.java:239)):
BIG-REQUESTS, MIT-SHM, DRI3, Present, RandR, SYNC, GLX, XInput2, Composite.

**Faltam coisas que todo app de desktop usa:**
- **RENDER**: cairo/GTK, Qt, Xft (fontes suavizadas)
- **XKEYBOARD (XKB)**: Qt e GTK usam para o teclado
- **XFIXES, SHAPE, DAMAGE**: cursores, janelas não retangulares, compositores
- **Fontes core**: `OpenFont` só aceita `"cursor"` ([FontRequests.java](java/com/winlator/cmod/xserver/requests/FontRequests.java)); o `xterm` padrão já quebra
- Opcodes ausentes como `GrabKeyboard`, `QueryFont`, `AllocColor`, `ListExtensions`, `GetKeyboardControl`, `ClearArea`...

**Pior:** opcode desconhecido só gera log, **sem resposta e sem pular o corpo da requisição**
([XClientRequestHandler.java:449-455](java/com/winlator/cmod/xserver/XClientRequestHandler.java:449)).
Um cliente que espera resposta **trava para sempre**, ou o stream dessincroniza.

| Opção | Para quê | Esforço |
|---|---|---|
| **Manter o Java** + corrigir o `default:` para sempre responder com `BadRequest` | **Jogos** nativos Linux (SDL2, GLFW, Unity, Godot) usando Vulkan/GLX: o uso de X11 é parecido com o do Wine | Baixo |
| **Estender o Java** (RENDER + XKB + XFIXES no mínimo) | Desktop completo mantendo tudo seu | Muito alto (meses) |
| **Trocar pelo Termux:X11** (servidor Xorg real portado para Android) | Qualquer app: GTK, Qt, Electron, xterm, XFCE | Médio. **Atenção: Termux:X11 é GPL-3.0; este projeto é MIT.** Integrar torna o app inteiro GPL-3 |

**Sugestão:** comece **focado em jogos** (mantém o servidor Java, que integra com DRI3/HUD/renderers
do Winlator) e, numa fase 2, adicione o Termux:X11 como servidor alternativo para apps de desktop.

### 1.3 Window manager
O Wine fornecia a "área de trabalho" (`wine explorer /desktop=shell,...` em
[XServerDisplayActivity.java:1053](java/com/winlator/cmod/XServerDisplayActivity.java:1053)).
Apps Linux sem WM abrem em (0,0), sem bordas e sem como mover. Opções:
- **Jogos:** forçar tela cheia (o `WindowManager` Java já trata `_NET_WM_STATE_FULLSCREEN`).
- **Desktop:** rodar `openbox`/`xfwm4` no rootfs antes do app. Isso exige o Termux:X11 (precisa de RENDER/SHAPE).

### 1.4 Emulador de CPU
- **Box64**: binário Linux em `imagefs/usr/bin/box64`, já usado no ramo não-ARM64EC
  ([GuestProgramLauncherComponent.java:517](java/com/winlator/cmod/xenvironment/components/GuestProgramLauncherComponent.java:517)). Precisa de `BOX64_LD_LIBRARY_PATH` / `BOX64_PATH` apontando para as libs x86_64 do rootfs.
- **FEX**: o pacote `assets/fexcore/` só traz `libwow64fex.dll` / `libarm64ecfex.dll` para o Wine
  ([ContentsManager.java:36-39](java/com/winlator/cmod/contents/ContentsManager.java:36)). Para Linux
  seria preciso o `FEXInterpreter` standalone + rootfs x86_64, o que não existe aqui. **Deixe para depois.**
- **Detecção de arquitetura:** [ElfHelper.java](java/com/winlator/cmod/core/ElfHelper.java) só lê
  32/64 bits. Adicione a leitura de `e_machine` (offset 18): `0x3E` = x86_64 → Box64; `0xB7` = aarch64 → nativo.

---

## 2. O que ADAPTAR (arquivo por arquivo)

### Núcleo de execução (prioridade 1)

| Arquivo | O que mudar |
|---|---|
| `xenvironment/components/GuestProgramLauncherComponent.java` | **`start()`** (l.201): pular `extractEmulatorsDlls()` no modo Linux. **`execGuestProgram()`** (l.313): remover `WINE_*`, `PROTON_*`, `SteamGameId`, `HODLL`, `WINE_X11FORCEGLX`; montar `command` por arquitetura (nativo / `box64` / `proot ... `). Manter `LD_PRELOAD` (sysvshm + fakeinput), `DISPLAY`, `PULSE_SERVER`, `ALSA_*`, `VK_*`, DNS. **`checkDependencies()`** (l.212) é debug inútil (roda `ldd` em `libXau.so`) e pode sair |
| `XServerDisplayActivity.java` | **`onCreate` runnable** (l.635-645): no modo Linux chamar só `extractGraphicsDriverFiles()` + `setupXEnvironment()`. **`setupXEnvironment()`** (l.1026): trocar o `guestExecutable` do `wine explorer` pelo `Exec=` do atalho; remover `WINEPREFIX`, `WINEDEBUG`, `WINEESYNC`, `WINE_FAST_YIELD`, `WINE_CPU_TOPOLOGY`; não iniciar `winHandler`/`wineRequestHandler`. **l.1222** `setUnviewableWMClasses("explorer.exe")`: remover |
| `xenvironment/ImageFs.java` | Remover `WINEPREFIX`, `winePath`, `getInstalledWineDir()`; adicionar caminhos do novo rootfs (`/usr/lib/aarch64-linux-gnu`, `/usr/lib/x86_64-linux-gnu`) |
| `xenvironment/ImageFsInstaller.java` | Instalar o rootfs Linux (Debian arm64 + libs amd64 + box64 + turnip + libs do Winlator: sysvshm, fakeinput, plugin ALSA) |
| `core/ElfHelper.java` | Adicionar `getMachine()` (x86_64 vs aarch64) |
| `cpp/CMakeLists.txt` | Se for usar a opção A: `add_subdirectory(proot)` e gerar `libproot.so` |
| `xserver/XClientRequestHandler.java` | `default:` (l.449): pular o corpo da requisição e responder `BadRequest` em vez de ficar em silêncio |

### Container e atalhos (prioridade 2)

| Arquivo | O que mudar |
|---|---|
| `container/Container.java` | Adicionar campo `runtime` (windows/linux). No modo Linux ficam sem uso: `dxwrapper`, `dxwrapperConfig`, `wincomponents`, `wineVersion`, `desktopTheme`, `startupSelection`, `exclusiveXInput`, `emulator`/`fexcorePreset`. `drives` (letras C:/D:) vira lista de **bind mounts** (`/sdcard` → `/mnt/sdcard`). `DEFAULT_ENV_VARS` (l.28): tirar `WINE*`, `DXVK_*`, `VKD3D_*` |
| `container/ContainerManager.java` | Criação de container Linux: não extrair `container_pattern_*.tzst` (prefixo Wine); criar `home/` |
| `container/Shortcut.java` | Já lê `.desktop`! O parse de `Exec` corta em `"wine "` (l.106). No modo Linux, usar o `Exec` inteiro (tirando `%f/%u/%F/%U`) e ler `Icon=` |
| `ShortcutsFragment.java` | Importar atalhos de `rootfs/usr/share/applications/*.desktop`; ícones do tema `hicolor` em vez do `ExeIconExtractor` |
| `FileManagerFragment.java` | Trocar a navegação por letras de drive por caminhos Linux |
| `winhandler/TaskManagerSidebar.java` | Listar processos via `/proc` em vez do `winhandler.exe` |

### Entrada (prioridade 2)
- `winhandler/WinHandler.java` mistura três coisas: (1) protocolo UDP 7946/7947 com o `winhandler.exe`
  dentro do Wine (mouse, teclado, processos, gamepad XInput); (2) **servidor de vibração** (l.69, `startVibrationListener()` l.313); (3) gerência do `FakeInputWriter` (l.614-622).
  → **Extraia (2) e (3) para uma classe nova** (ex.: `inputcontrols/GamepadBridge.java`) antes de remover o WinHandler. No Linux o gamepad vai só via `libfakeinput.so` (evdev falso em `FAKE_EVDEV_DIR`), que o SDL2 lê nativamente.
- `widget/TouchpadView.java`, `widget/InputControlsView.java`: trocar chamadas `winHandler.mouseEvent/keyboardEvent` por eventos do X server (`xServer.injectPointer*`/`injectKey*`, que já existem para o caminho X11).

### UI Kotlin/Compose (prioridade 3)
Esconder ou condicionar ao `runtime` as seções de Wine/DXVK/VKD3D/WineD3D/Wincomponents/Proton:
`ui/container/ContainerEditorV2.kt`, `ContainerRuntimePane.kt`, `ContainerVideoCompatibilityInline.kt`,
`ContainerAdvancedComposeDialog.kt`, `ContainerSectionComposeHost.kt`, `ContainerSystemStorageInline.kt`,
`ui/shortcut/ShortcutEditorV2.kt`, `ui/settings/RuntimeSettingsSupport.kt`, `SettingsComposeHost.kt`,
`CompatibilityRules.kt`, `ui/onboarding/OnboardingRuntimeSelectionUi.kt`, `OnboardingComponentsUi.kt`,
e os equivalentes legados `ContainerDetailFragment.java` (150 referências a Wine), `ContainerSectionFragment.java`,
`SettingsFragment.java`, `OnboardingActivity.java`, `contentdialog/ShortcutSettingsDialog.java`.

### Novo (não existe hoje)
- **Gerenciador de pacotes**: um terminal (o `xterm` precisa do Termux:X11; dá para usar uma lib de terminal Android, como a `terminal-view` do Termux, que é Apache-2.0) ou uma UI que roda `apt install` dentro do proot.
- **D-Bus de sessão**: iniciar `dbus-daemon --session` antes do app (muitos apps GTK/Qt/Electron precisam).
- **Variáveis de ambiente Linux**: `XDG_RUNTIME_DIR`, `SDL_VIDEODRIVER=x11`, `SDL_AUDIODRIVER=pulseaudio`, `VK_ICD_FILENAMES` (Turnip), `MESA_LOADER_DRIVER_OVERRIDE=zink` para OpenGL.

---

## 3. O que pode ser EXCLUÍDO (build Linux-only)

### Java/Kotlin
| Arquivo(s) | Por quê |
|---|---|
| `core/WineUtils.java`, `WineInfo.java`, `WineRegistryEditor.java`, `WineRequestHandler.java`, `WineRuntimeGuard.java`, `WineStartMenuCreator.java`, `WineThemeManager.java`, `ProtonPackageManager.java` | 100% Wine/Proton |
| `core/ExeIconExtractor.java`, `MSLink.java`, `MSBitmap.java`, `MSLogFont.java` | Formatos Windows (.exe, .lnk, .bmp, LOGFONT) |
| `core/VKD3DVersionItem.java` | DirectX 12 |
| `contentdialog/DXVKConfigDialog.java`, `WineD3DConfigDialog.java` | DirectX → Vulkan/GL |
| `fexcore/*` (4 arquivos) | Só funciona como DLL do Wine (pode voltar no futuro com FEXInterpreter) |
| `winhandler/WinHandler.java`, `RequestCodes.java`, `MouseEventFlags.java`, `ProcessInfo.java`, `OnGetProcessInfoListener.java` | Protocolo com `winhandler.exe`; **antes, extraia vibração e FakeInputWriter** |
| `midi/*` | Repasse de MIDI do Wine; apps Linux usam FluidSynth/ALSA seq direto (opcional manter) |
| Em `XServerDisplayActivity.java`: `setupWineSystemFiles()` (l.951), `extractDXWrapperFiles()` (l.2538), `extractWinComponentFiles()` (l.2652), `restoreOriginalDllFiles()` (l.2696), lógica do `wfm.exe` (l.2776), `changeWineAudioDriver()` (l.2818), `applyGameRefreshRateUnlock()` (l.2835), `isSelectedWineXrandrCapable()` (l.2854), `applyGeneralPatches()` (l.2919), `assignTaskAffinity()` via WinHandler (l.2927) | Tudo mexe em `drive_c`, registro ou DLLs |
| Em `GuestProgramLauncherComponent.java`: `extractEmulatorsDlls()` (l.137) | WOWBox64/FEX como DLL |
| `contents/ContentProfile.java`: tipos `WINE`, `PROTON`, `DXVK`, `VKD3D`, `WOWBOX64`, `FEXCORE` | Manter só `BOX64` (+ drivers) |
| `contents/ContentsManager.java`: `DXVK_TRUST_FILES`, `VKD3D_TRUST_FILES`, `WOWBOX64_TRUST_FILES`, FEX trust files, templates `${system32}`/`${syswow64}` | Caminhos do Wine |

### Assets (`assets/`): economiza **centenas de MB** no APK
| Remover | Manter |
|---|---|
| `dxwrapper/` (DXVK, D8VK, VKD3D) | `box64/` |
| `ddrawrapper/` | `graphics_driver/` (Turnip, adrenotools, Zink, Freedreno, wrapper) |
| `wincomponents/` | `layers.tzst` (camadas Vulkan, incluindo DisplayX) |
| `wowbox64/`, `fexcore/`, `fexcore_env_vars.json` | `pulseaudio*.tzst` |
| `proton-9.0-*_container_pattern.tzst`, `container_pattern_common.tzst` | `inputcontrols/`, `cursor.png` |
| `common_dlls.json`, `wine_debug_channels.json`, `wine_startmenu.json` | `gpu_cards.json`, `box64_env_vars.json` |
| `system.reg.LOG1`, `system.reg.LOG2`, `input_dlls.tzst` | `soundfonts/`, `default_music.mp3` (opcional) |

### Build
- `app/build.gradle`: tarefa **`downloadProton`** (baixa o Proton ARM64EC de ~130 MB) e trocar a **`downloadImageFS`** pelo download do novo rootfs, **com verificação SHA-256** (a atual não verifica nada).

### Pode manter sem mudança
`xserver/*` (com a correção do `default:`), `xconnector/*`, `renderer/*`, `widget/*XServerView*`,
`widget/WinlatorHUD.java`, `inputcontrols/*`, `alsaserver/*`, `sysvshm/*`, `box64/*`,
`contents/AdrenotoolsManager.java`, `AdrenotoolsFragment.java`, `xenvironment/components/{XServer,PulseAudio,ALSAServer,SysVSharedMemory}Component.java`,
`core/{ProcessHelper,EnvVars,FileUtils,TarCompressorUtils,GPUInformation,CPUStatus}.java`,
`cpp/winlator/*` (exceto `winlauncher.c`, que é do Wine), `XrActivity.java` (opcional), `BigPictureActivity` + SteamGridDB (funciona para jogos Linux também).

---

## 4. Roteiro sugerido

1. **[Implementado, não testado em aparelho]** Atalho `.desktop` com `Exec=` apontando para um ELF → modo Linux
   (`XServerDisplayActivity.resolveLinuxExecutable()`, `GuestProgramLauncherComponent.buildLinuxCommand()`,
   `ElfHelper.getMachine()`), e opcode X desconhecido agora responde `BadRequest`.
   **Resultado do teste com SuperTuxKart 1.5 x86_64 (Moto Edge 30 Pro):** o pipeline funciona até o Box64
   carregar o ELF e as libs, mas **a imagefs deste fork é Bionic** (`usr/lib/libc.so -> /system/lib64/libc.so`)
   e o Box64 0.4.2 incluído não tem os wrappers de glibc (`__libc_start_main`, `__errno_location`,
   `__ctype_b_loc`, `libresolv.so.2`...). Programas Linux glibc **não rodam** com ele → a fase 2
   (Box64 compilado para glibc + rootfs glibc arm64) é obrigatória, não opcional.
   Também: binários em `/sdcard` não executam (noexec); o jogo precisa ficar no armazenamento privado do app.
   **Prova de conceito (1-2 semanas):** rodar um jogo Linux x86_64 simples (ex.: um jogo SDL2/Godot) pela imagefs atual:
   `box64 /caminho/jogo` com as libs x86_64 do jogo ao lado + `BOX64_LD_LIBRARY_PATH`. Corrigir o `default:` do X server. Sem UI nova: só um atalho com `Exec` hardcoded.
2. **Rootfs + PRoot:** compilar o `cpp/proot`, montar o rootfs Debian arm64 + amd64, empacotar o Turnip KGSL, `apt` funcionando.
3. **Container `runtime=linux`:** campos no `Container`, launcher condicional, atalhos via `.desktop`, extrair vibração/fakeinput do WinHandler.
4. **UI:** esconder as opções de Wine no modo Linux, terminal/instalador de pacotes.
5. **Fase 2 (desktop apps):** Termux:X11 como servidor alternativo + openbox (decidir a questão GPL antes).
6. **Opcional:** build Linux-only removendo a seção 3; FEXInterpreter standalone.
