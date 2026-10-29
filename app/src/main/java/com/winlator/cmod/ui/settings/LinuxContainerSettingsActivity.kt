package com.winlator.cmod.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Settings > Linux container: renderer, renderer driver and Box64 preset of Linux mode. */
class LinuxContainerSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)

        val settings = LinuxContainerSettings(this)
        val rootfs = LinuxRootfs.find(this)
        val rootfsStatus = if (rootfs.isInstalled) "Rootfs Debian v${rootfs.version} instalado" else "Rootfs Debian não instalado"

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
                    rootfsStatus = rootfsStatus,
                    initialRenderer = settings.renderer,
                    initialDriver = settings.rendererDriverId,
                    initialBox64Preset = settings.box64Preset,
                    drivers = drivers,
                    box64Presets = box64Presets,
                    onRenderer = settings::setRenderer,
                    onDriver = onDriver,
                    onBox64Preset = settings::setBox64Preset,
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
            Toast.makeText(this, "A área de trabalho precisa do rootfs v10 ou mais novo", Toast.LENGTH_LONG).show()
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

    private companion object {
        const val DESKTOP_SHORTCUT_NAME = "Área de trabalho Linux"
    }
}

@Composable
private fun LinuxContainerSettingsScreen(
    rootfsStatus: String,
    initialRenderer: String,
    initialDriver: String,
    initialBox64Preset: String,
    drivers: List<SettingChoice>,
    box64Presets: List<SettingChoice>,
    onRenderer: (String) -> Unit,
    onDriver: (String) -> Unit,
    onBox64Preset: (String) -> Unit,
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
                GroupCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallIcon(Icons.Outlined.Terminal)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Modo Linux", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(rootfsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
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
