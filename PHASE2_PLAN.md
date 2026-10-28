# Fase 2 — Box64 glibc + rootfs Debian arm64 no app

Plano de empacotamento. Continuação de [LINUX_MODE_MAP.md](LINUX_MODE_MAP.md).
Motivo: a imagefs e o Box64 deste fork são **Bionic** e não rodam programas Linux glibc
(`__libc_start_main` / `__errno_location` ausentes — teste com SuperTuxKart 1.5).

---

## 1. Decisão: PRoot (não glibc com prefixo)

| | PRoot + Debian sem modificação | glibc com prefixo |
|---|---|---|
| Pacotes | `apt` / `.deb` do Debian direto | Tudo recompilado para `/data/data/<pacote>/...` |
| Prefixo amarrado ao nome do pacote | Não | **Sim** (os pacotes glibc do termux-pacman são fixos em `com.termux` — inúteis aqui) |
| Libs x86_64 para o Box64 | Multiarch `amd64` do Debian | Teria que recompilar ou patchear o RPATH |
| Overhead | ptrace nas syscalls de caminho (com seccomp, o resto passa direto) | Nenhum |
| Já existe no repo | **`app/src/main/cpp/proot/` pronto** (gera `libproot.so` + `libproot-loader.so`), só não está no CMake | Não |

**Escolha: PRoot.** Jogos são limitados por GPU, não por syscalls de arquivo. A troca por glibc com
prefixo pode ser feita depois, se o perfil mostrar que o PRoot pesa.

Licença: PRoot é **GPL-2**, mas roda como **executável separado** (agregação, não linkagem), então não
contamina o app (MIT). Os pacotes GPL do Debian dentro da rootfs exigem disponibilizar o código-fonte
(publicar junto do release os `.dsc`/fontes, ou uma oferta por escrito).

---

## 2. Arquitetura em tempo de execução

```
App (Java)
 ├─ XServer Java ──────── socket  imagefs/usr/tmp/.X11-unix/X0 ──┐
 ├─ PulseAudio (bionic) ─ socket  imagefs/usr/tmp/.sound/PS0 ────┤  bind mounts
 ├─ SysVSHM server ────── socket  imagefs/usr/tmp/.sysvshm/SM0 ──┤
 └─ libproot.so  (nativeLibraryDir — sempre executável)          │
      └─ rootfs Debian arm64  (files/linux/rootfs-<ver>/)  ◄──────┘
           ├─ /usr/local/bin/box64            (glibc, compilado por nós)
           ├─ /usr/lib/aarch64-linux-gnu/...  (SDL2, OpenAL, X11, libpulse, Vulkan loader)
           ├─ /usr/lib/x86_64-linux-gnu/...   (libc6:amd64 etc. para libs emuladas)
           ├─ Mesa próprio: Turnip (KGSL) + Zink   ← o Mesa do Debian não tem KGSL
           ├─ /usr/lib/winlator/libandroid-sysvshm.so  (glibc)  ← MIT-SHM
           ├─ /usr/lib/winlator/libfakeinput.so        (glibc)  ← gamepad evdev
           └─ /home/user  ← bind do home do container (imagefs/home/xuser-N)
```

### Comando de execução (exemplo)
```
libproot.so --kill-on-exit -r <rootfs> \
  -b /dev -b /proc -b /sys -b /system -b /apex -b /vendor \
  -b <imagefs>/usr/tmp/.X11-unix:/tmp/.X11-unix \
  -b <imagefs>/usr/tmp/.sound:/tmp/.sound \
  -b <imagefs>/usr/tmp/.sysvshm:/tmp/.sysvshm \
  -b <container_home>:/home/user \
  -b /storage/emulated/0:/mnt/sdcard \
  -w /home/user/Games/<jogo> \
  /usr/bin/env -i HOME=/home/user DISPLAY=:0 ... /usr/local/bin/box64 ./bin/jogo
```
Ambiente do processo do PRoot: `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`, `PROOT_TMP_DIR=<files>/tmp`.

### Ambiente dentro da rootfs
`HOME=/home/user`, `USER=user`, `LANG=C.UTF-8`, `DISPLAY=:0`, `XDG_RUNTIME_DIR=/tmp/runtime`,
`PULSE_SERVER=unix:/tmp/.sound/PS0`, `ANDROID_SYSVSHM_SERVER=/tmp/.sysvshm/SM0`,
`LD_PRELOAD=/usr/lib/winlator/libandroid-sysvshm.so:/usr/lib/winlator/libfakeinput.so`,
`VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json`, `MESA_LOADER_DRIVER_OVERRIDE=zink`,
`TU_DEBUG=noconform`, `MESA_VK_WSI_DEBUG=sw` (ver risco R3), `BOX64_*` dos presets existentes
(`Box64PresetManager`), `BOX64_LD_LIBRARY_PATH=<pasta do jogo>/lib:...`, mais as variáveis do atalho.

---

## 3. Conteúdo da rootfs e como gerar

### Pacotes (Debian 13 "trixie", `mmdebstrap --variant=minbase --architectures=arm64,amd64`)
- **arm64:** `libc6 libstdc++6 libsdl2-2.0-0 libopenal1 libvulkan1 libgl1 libegl1 libgles2 libglx-mesa0`
  (só pela estrutura do GLVND — os drivers vêm do nosso Mesa), `libx11-6 libxext6 libxrandr2 libxi6
  libxcursor1 libxinerama1 libxss1 libxkbcommon0 libxfixes3 libpulse0 libasound2t64 libfreetype6
  libharfbuzz0b libpng16-16t64 libvorbisfile3 libcurl4t64 libssl3t64 ca-certificates dbus`
- **amd64:** `libc6:amd64 libstdc++6:amd64 libgcc-s1:amd64 zlib1g:amd64` — base das libs que o
  Box64 emula (é o que resolve o `libresolv.so.2` do SuperTuxKart).
- **Compilados por nós, com versão fixa:**
  - **Box64** (tag fixa, `-DARM_DYNAREC=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo`, build genérica para ARM64 em vez de específica de SoC).
  - **Mesa** (`-Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers=zink -Dplatforms=x11 -Dglx=dri -Degl=enabled`).
  - **Shims do Winlator para glibc:** `android_sysvshm/` (já tem `cross-arm64.cmake`) e `cpp/winlator/fakeinput.cpp`.
- **Limpeza:** remover `/usr/share/doc`, `man`, `locale` (menos C.UTF-8) e o cache do apt.
- **Tamanho estimado:** ~150–250 MB em `tar.zst`.

### Onde gerar (não há WSL/Docker neste PC)
| Opção | Prós | Contras |
|---|---|---|
| **GitHub Actions `ubuntu-24.04-arm`** (recomendado) | arm64 nativo (Mesa/Box64 compilam rápido, sem qemu), reprodutível, gera release com SHA-256 | Precisa de um repositório no GitHub |
| WSL2 + `qemu-user-static` neste PC | Local | Exige admin + reboot; compilar Mesa sob qemu é lento (horas) |
| Termux no celular | Nativo | A versão da Play Store restringe execução; nada reprodutível |

---

## 4. Entrega no app

**Fora do APK.** O APK já tem 569 MB, então a rootfs vira um **pacote de conteúdo**, reaproveitando o sistema de
conteúdos que já existe (`ContentsManager`, `profile.json`, catálogo remoto).

| Peça | Mudança |
|---|---|
| `cpp/CMakeLists.txt` | `add_subdirectory(proot)` → `libproot.so` e `libproot-loader.so` no APK (pequenos; `useLegacyPackaging = true` já extrai as libs) |
| `contents/ContentProfile.java` | Novo `CONTENT_TYPE_LINUX_ROOTFS` |
| `contents/ContentsManager.java` | Instalar em `files/linux/rootfs-<ver>/` (fora da imagefs: o `ImageFsInstaller.clearRootDir()` apaga tudo da imagefs exceto `home/`). **Exigir SHA-256** no catálogo |
| `xenvironment/LinuxRootfs.java` (novo) | Caminhos, versão, `isInstalled()`, criar `/home/user`, `/tmp/runtime` |
| `xenvironment/components/LinuxProgramLauncherComponent.java` (novo) | Monta o comando do PRoot (seção 2). Sai do `GuestProgramLauncherComponent`, para não misturar com o Wine |
| `XServerDisplayActivity.setupXEnvironment()` | No modo Linux: usar o novo componente se a rootfs estiver instalada; senão, mostrar "Instale o runtime Linux" |
| `SysVSharedMemoryComponent` | O servidor já existe; só precisa fazer o bind do socket |
| Importar jogo (UI) | Seletor de pasta (SAF) → copiar para `<container>/Games/<nome>` (o `/sdcard` é noexec), `chmod +x` e gerar o `.desktop` automaticamente |
| Remover depois | O caminho Bionic da prova de conceito (`buildLinuxCommand`), quando o PRoot estiver estável |

---

## 5. Riscos — validar nesta ordem (cada um é um spike curto)

| # | Risco | Como validar | Plano B |
|---|---|---|---|
| R1 | PRoot do repo funcionar no Android 14 / Moto (ptrace + seccomp em processo de app) | `libproot.so -r rootfs /bin/true` e `uname -a` | Usar o PRoot mais novo do Termux (`termux/proot`, também GPL-2) |
| R2 | Turnip glibc abrir `/dev/kgsl-3d0` dentro do PRoot | `vulkaninfo --summary` | Ajustar o bind de `/dev`; conferir a versão do KGSL do Adreno 730 |
| R3 | **Apresentação na tela**: a DRI3 Java só aceita `AHardwareBuffer` via socket, não dma-buf do Mesa | Começar com `MESA_VK_WSI_DEBUG=sw` (cópia via MIT-SHM/PutImage) e medir FPS no `vkcube` | (a) aceitar dma-buf na DRI3 (importar via `EGL_EXT_image_dma_buf_import` no renderer); (b) portar o ICD "wrapper" do Winlator para glibc |
| R4 | Shim de SysV SHM (glibc) com MIT-SHM | `xdpyinfo -ext MIT-SHM` + `vkcube` | Desativar o MIT-SHM no cliente (mais lento) |
| R5 | Desempenho Box64 + PRoot | SuperTuxKart em 720p, comparando com o Wine no mesmo aparelho | Glibc com prefixo (seção 1) |
| R6 | Espaço de endereçamento de 39 bits (o Box64 já avisou) | Jogos x86_64 grandes | `BOX64_MMAP32`/presets |

---

## 6. Marcos

1. **M1: rootfs.** Workflow do Actions gera `linux-rootfs-<ver>.tar.zst` + SHA-256 (Debian + Box64 + Mesa + shims).
2. **[Feito, validado no Moto Edge 30 Pro]** **M2: PRoot no app.** R1 e R2 OK: Debian 13 roda no PRoot,
   Box64 v0.4.4 glibc executa, `vulkaninfo` vê "Turnip Adreno (TM) 730" (Mesa 26.2.5, Vulkan 1.4).
   Dois consertos no PRoot 5.1 do repo: o loader precisa ser freestanding (sem `memset@plt`) e a
   syscall `statx` (usada pelo coreutils do Debian 13) não tinha tradução de caminho.
   Plano original do M2: Compilar `cpp/proot`; botão de debug que roda `/bin/true`, `uname` e `vulkaninfo` dentro da rootfs, instalada a partir de um arquivo local (adb push). Valida R1 e R2.
3. **[Vulkan e OpenGL feitos]** **M3: imagem na tela.** `vkcube` renderiza e anima no X server
   do app (WSI por software). Custo alto de CPU (~1 núcleo no cliente + ~1 no app) → R3 continua para
   desempenho. Pendências: `MESA_VK_WSI_DEBUG=sw` com MIT-SHM dá segfault no shim SysV; GLX/Zink resolvido com `LIBGL_KOPPER_DRI2=1` (glxgears ~1500 FPS renderizados);
   ("couldn't find RGB GLX visual or fbconfig", "DRI3 not available"); o X server só tem um visual de
   32 bits; PRoot 5.1 não tem `-0`/`link2symlink`/`sysvipc` → avaliar o PRoot do Termux.
   Plano original do M3: `vkcube` e `glxgears` (Zink) aparecendo no X server do app. Valida R3 e R4.
4. **M4: SuperTuxKart jogável.** Áudio pelo PulseAudio, controle pelo fakeinput. Valida R5.
5. **M5: produto.** Download da rootfs pelo catálogo com SHA-256, UI de "Importar jogo", container `runtime=linux`.
6. **M6: limpeza.** Remover o caminho Bionic da prova de conceito; atualizar o `LINUX_MODE_MAP.md`.
