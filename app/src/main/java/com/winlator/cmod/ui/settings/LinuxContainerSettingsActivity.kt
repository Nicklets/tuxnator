package com.winlator.cmod.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.core.FileUtils
import java.io.File
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.xenvironment.LinuxContainerSettings
import com.winlator.cmod.xenvironment.LinuxRootfs

/** Settings > Linux container: renderer, renderer driver, Box64 preset and shared folders of Linux mode. */
class LinuxContainerSettingsActivity : AppCompatActivity() {
    private lateinit var settings: LinuxContainerSettings
    private val sharedFolders = mutableStateListOf<String>()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            Toast.makeText(this, "Escolha uma pasta do armazenamento do aparelho", Toast.LENGTH_LONG).show()
        } else if (path !in sharedFolders) {
            sharedFolders.add(path)
            settings.sharedFolders = sharedFolders.toList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)

        settings = LinuxContainerSettings(this)
        sharedFolders.addAll(settings.sharedFolders)
        if (!RootfsInstall.running) RootfsInstall.version = LinuxRootfs.find(this).version

        val adrenotools = AdrenotoolsManager(this)
        // Drivers shipped in the APK (extracted on first use) plus the ones the user installed
        val bundled = assets.list("graphics_driver").orEmpty()
            .filter { it.startsWith("adrenotools-") && it.endsWith(".tzst") }
            .map { it.removePrefix("adrenotools-").removeSuffix(".tzst") }
        val drivers = listOf(SettingChoice("system", "Sistema (Qualcomm)")) +
            (bundled + adrenotools.enumarateInstalledDrivers()).distinct().map { id ->
                val label = "${adrenotools.getDriverName(id)} ${adrenotools.getDriverVersion(id)}".trim()
                SettingChoice(id, label.ifEmpty { id })
            }
        val onDriver: (String) -> Unit = { id ->
            settings.rendererDriverId = id
            if (id in bundled) Thread { adrenotools.extractDriverFromResources(id) }.start()
        }
        val box64Presets = listOf(SettingChoice(LinuxContainerSettings.BOX64_PRESET_DEFAULT, "Padrão do Box64")) +
            Box64PresetManager.getPresets("box64", this).map { SettingChoice(it.id, it.name) }

        setContent {
            WinZTheme {
                LinuxContainerSettingsScreen(
                    onInstallRootfs = { RootfsInstall.start(applicationContext) },
                    initialRenderer = settings.renderer,
                    initialDriver = settings.rendererDriverId,
                    initialBox64Preset = settings.box64Preset,
                    drivers = drivers,
                    box64Presets = box64Presets,
                    onRenderer = settings::setRenderer,
                    onDriver = onDriver,
                    onBox64Preset = settings::setBox64Preset,
                    sharedFolders = sharedFolders,
                    onAddFolder = { pickFolder.launch(null) },
                    onRemoveFolder = { path ->
                        sharedFolders.remove(path)
                        settings.sharedFolders = sharedFolders.toList()
                    },
                    onOpenDesktop = ::openDesktop,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAppFullscreen(this)
    }

    /**
     * Starts the Openbox/LXPanel/PCManFM session from a shortcut in the first container's
     * desktop, created here, so it also shows up in the library like any Linux shortcut.
     */
    private fun openDesktop() {
        val rootfs = LinuxRootfs.find(this)
        if (!File(rootfs.rootDir, "usr/local/bin/tuxnator-desktop").isFile) {
            Toast.makeText(this, "Instale o rootfs Debian primeiro", Toast.LENGTH_LONG).show()
            return
        }
        val container = ContainerManager(this).containers.firstOrNull()
        if (container == null) {
            Toast.makeText(this, "Crie um contêiner primeiro", Toast.LENGTH_LONG).show()
            return
        }
        val shortcutFile = File(container.desktopDir, "$DESKTOP_SHORTCUT_NAME.desktop")
        if (!shortcutFile.isFile) {
            container.desktopDir.mkdirs()
            FileUtils.writeString(shortcutFile, """
                [Desktop Entry]
                Name=$DESKTOP_SHORTCUT_NAME
                Exec=dbus-run-session -- tuxnator-desktop
                Type=Application

                [Extra Data]
                linuxRuntime=rootfs
            """.trimIndent() + "\n")
        }
        startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", container.id)
            putExtra("shortcut_path", shortcutFile.path)
            putExtra("shortcut_name", DESKTOP_SHORTCUT_NAME)
        })
    }

    /**
     * The folder behind a document tree from the system picker, e.g. "primary:Download/Games" from
     * the external storage provider. The guest gets it as a PRoot bind, which needs a real path.
     */
    private fun treeUriToPath(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (uri.authority == "com.android.providers.downloads.documents") {
            return if (docId == "downloads") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                else docId.removePrefix("raw:").takeIf { it.startsWith("/") }
        }
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage", volume)
        val folder = if (relative.isEmpty()) root else File(root, relative)
        return folder.path.takeIf { folder.isDirectory }
    }

    private companion object {
        const val DESKTOP_SHORTCUT_NAME = "Área de trabalho Linux"
    }
}

/** Download and install of the release rootfs. Lives in the process, so leaving the screen doesn't stop it. */
private object RootfsInstall {
    var version by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    /** Download progress (0..1), or null while connecting, extracting or when the size is unknown */
    var progress by mutableStateOf<Float?>(null)
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)

    fun start(context: Context) {
        if (running) return
        running = true
        failed = false
        progress = null
        message = "Conectando…"
        val main = Handler(Looper.getMainLooper())
        val rootfs = LinuxRootfs.find(context)
        Thread {
            var lastMessage = ""
            val ok = rootfs.installRelease(
                { done, total ->
                    main.post {
                        progress = if (total > 0) done.toFloat() / total else null
                        message = if (total > 0) "Baixando: ${done shr 20} de ${total shr 20} MB" else "Baixando: ${done shr 20} MB"
                    }
                },
                { msg ->
                    lastMessage = msg
                    main.post { progress = null; message = "Instalando…" }
                }
            )
            main.post {
                running = false
                progress = null
                version = rootfs.version
                failed = !ok
                message = if (ok) null else "Falhou: $lastMessage"
            }
        }.start()
    }
}

@Composable
private fun LinuxContainerSettingsScreen(
    onInstallRootfs: () -> Unit,
    initialRenderer: String,
    initialDriver: String,
    initialBox64Preset: String,
    drivers: List<SettingChoice>,
    box64Presets: List<SettingChoice>,
    onRenderer: (String) -> Unit,
    onDriver: (String) -> Unit,
    onBox64Preset: (String) -> Unit,
    sharedFolders: List<String>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenDesktop: () -> Unit,
    onBack: () -> Unit
) {
    var renderer by remember { mutableStateOf(initialRenderer) }
    var driver by remember { mutableStateOf(initialDriver) }
    var box64Preset by remember { mutableStateOf(initialBox64Preset) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Contêiner Linux") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item("status") {
                val version = RootfsInstall.version
                val status = RootfsInstall.message ?: when {
                    version == 0 -> "Rootfs Debian não instalado"
                    version < LinuxRootfs.RELEASE_VERSION -> "Rootfs Debian v$version instalado, v${LinuxRootfs.RELEASE_VERSION} disponível"
                    else -> "Rootfs Debian v$version instalado"
                }
                val action = when {
                    RootfsInstall.running -> null
                    RootfsInstall.failed -> "Tentar de novo"
                    version == 0 -> "Instalar"
                    version < LinuxRootfs.RELEASE_VERSION -> "Atualizar"
                    else -> null
                }
                // A phone that sleeps mid-download drops the connection
                val view = LocalView.current
                DisposableEffect(RootfsInstall.running) {
                    view.keepScreenOn = RootfsInstall.running
                    onDispose { view.keepScreenOn = false }
                }
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallIcon(Icons.Outlined.Terminal)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Modo Linux", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (RootfsInstall.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (action != null) {
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = onInstallRootfs) { Text(action) }
                        }
                    }
                    if (RootfsInstall.running) {
                        val progress = RootfsInstall.progress
                        val modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = modifier)
                        else LinearProgressIndicator(modifier = modifier)
                    }
                }
            }
            if (RootfsInstall.version in 1 until LinuxRootfs.RELEASE_VERSION) item("status-hint") {
                Hint("Atualizar baixa cerca de 200 MB e troca o sistema Debian inteiro: pacotes instalados com apt se perdem. A pasta pessoal (home) e as pastas compartilhadas ficam.")
            } else if (RootfsInstall.version == 0) item("status-hint") {
                Hint("O modo Linux precisa de um sistema Debian com Box64 e drivers Turnip. Instalar baixa cerca de 200 MB e ocupa uns 850 MB.")
            }

            item("desktop-title") { SectionTitle("ÁREA DE TRABALHO") }
            item("desktop") {
                Surface(
                    onClick = onOpenDesktop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallIcon(Icons.Outlined.DesktopWindows)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Abrir área de trabalho Linux", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("Gerenciador de arquivos, barra de tarefas e janelas: mova e descompacte arquivos e rode programas Linux",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item("folders-title") { SectionTitle("PASTAS COMPARTILHADAS") }
            item("folders") {
                GroupCard {
                    sharedFolders.forEach { path ->
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SmallIcon(Icons.Outlined.Folder)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(File(path).name, fontWeight = FontWeight.Medium)
                                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onRemoveFolder(path) }) {
                                Icon(Icons.Outlined.Close, "Remover", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        GroupDivider()
                    }
                    Surface(onClick = onAddFolder, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            SmallIcon(Icons.Outlined.CreateNewFolder)
                            Spacer(Modifier.width(11.dp))
                            Text("Adicionar pasta", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item("folders-hint") {
                Hint("O Linux só acessa os arquivos do próprio app e as pastas desta lista, que aparecem em \"Android\" na área de trabalho. Mudanças valem na próxima vez que o emulador abrir.")
            }

            item("render-title") { SectionTitle("RENDERIZAÇÃO") }
            item("render") {
                GroupCard {
                    ChoiceRow(
                        icon = Icons.Outlined.Monitor,
                        title = "Renderizador",
                        choices = listOf(
                            SettingChoice(LinuxContainerSettings.RENDERER_VULKAN, "Vulkan"),
                            SettingChoice(LinuxContainerSettings.RENDERER_OPENGL, "OpenGL")
                        ),
                        selectedId = renderer
                    ) { renderer = it; onRenderer(it) }
                    if (renderer == LinuxContainerSettings.RENDERER_VULKAN) {
                        GroupDivider()
                        ChoiceRow(
                            icon = Icons.Outlined.DeveloperBoard,
                            title = "Driver para Snapdragon",
                            choices = drivers,
                            selectedId = driver
                        ) { driver = it; onDriver(it) }
                    }
                }
            }
            item("render-hint") {
                Hint(
                    if (renderer == LinuxContainerSettings.RENDERER_VULKAN)
                        "Com um driver Turnip, os quadros dos jogos chegam à tela sem cópia (zero-copy). Com o driver do sistema, cada quadro é copiado pela CPU."
                    else
                        "O renderizador OpenGL copia cada quadro pela CPU. Prefira Vulkan, a menos que ele dê problemas no seu aparelho."
                )
            }

            item("box64-title") { SectionTitle("BOX64") }
            item("box64") {
                GroupCard {
                    ChoiceRow(
                        icon = Icons.Outlined.Memory,
                        title = "Precisão do Box64",
                        choices = box64Presets,
                        selectedId = box64Preset
                    ) { box64Preset = it; onBox64Preset(it) }
                }
            }
            item("box64-hint") {
                Hint("Vale para programas x86_64. Presets mais estáveis emulam a CPU com mais precisão e rodam mais devagar. As variáveis de ambiente de um atalho têm prioridade.")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 3.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column { content() } }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun SmallIcon(icon: ImageVector) {
    Surface(Modifier.size(38.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(21.dp)) }
    }
}

/** Expandable single choice, like the preset rows in Settings */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    choices: List<SettingChoice>,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallIcon(icon)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    Text(selected?.name ?: selectedId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (expanded) {
            GroupDivider()
            choices.forEach { choice ->
                Surface(
                    onClick = {
                        onSelected(choice.id)
                        expanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            choice.name,
                            modifier = Modifier.weight(1f),
                            color = if (choice.id == selectedId) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (choice.id == selectedId) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (choice.id == selectedId) Text("✓", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
